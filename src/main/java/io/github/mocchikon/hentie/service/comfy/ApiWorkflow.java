package io.github.mocchikon.hentie.service.comfy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One API-format workflow, checked against the contract in {@code COMFYUI.md}: Input is the node titled
 * "Input", else the only Load Image; Output is the node titled "Output", else the only Save/Preview Image.
 * Everything else, seeds included, runs as exported, so a page always gives the same result, which is what
 * makes caching results correct.
 *
 * <p>Titles, not node ids, because ids depend on the order the graph was built and a title is what the user
 * can see and set. The fallback covers the common one-loader, one-saver workflow with no renaming.
 *
 * <p>Immutable: {@link #prepare} works on a copy, so one parsed workflow serves every run.
 */
public final class ApiWorkflow
{
    static final String INPUT_TITLE = "Input";
    static final String OUTPUT_TITLE = "Output";

    /**
     * Sends the result over the websocket, so nothing is written to ComfyUI's disk. Not every distribution
     * ships it, hence the {@link #PREVIEW_OUTPUT} fallback.
     */
    public static final String WEBSOCKET_OUTPUT = "SaveImageWebsocket";

    /** Writes into ComfyUI's temp folder, never its output folder as Save Image would. */
    public static final String PREVIEW_OUTPUT = "PreviewImage";

    private static final String LOAD_IMAGE = "LoadImage";
    private static final Set<String> OUTPUT_TYPES = Set.of("SaveImage", PREVIEW_OUTPUT, WEBSOCKET_OUTPUT);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final ObjectNode nodes;
    private final String inputId;
    private final String outputId;
    private final String version;

    private ApiWorkflow(String name, ObjectNode nodes, String inputId, String outputId, String version)
    {
        this.name = name;
        this.nodes = nodes;
        this.inputId = inputId;
        this.outputId = outputId;
        this.version = version;
    }

    /**
     * @param name the file name without {@code .json}, relative to the workflow folder
     * @throws ComfyUiException {@code WORKFLOW_INVALID}, with a message that says how to fix the file
     */
    public static ApiWorkflow parse(String name, byte[] content) throws ComfyUiException
    {
        JsonNode root;
        try
        {
            root = JSON.readTree(content);
        }
        catch (IOException e)
        {
            throw invalid(name, "the file is not valid JSON");
        }
        if (root == null || !root.isObject() || root.isEmpty())
        {
            throw invalid(name, "the file is empty or not a workflow");
        }
        if (root.has("nodes") && root.has("links"))
        {
            throw invalid(name, "it was saved in ComfyUI's regular format. Export it with File > Export (API) instead");
        }
        for (JsonNode node : root)
        {
            if (!node.path("class_type").isTextual() || !node.path("inputs").isObject())
            {
                throw invalid(name, "it is not an API-format workflow (export it with File > Export (API))");
            }
        }
        var nodes = (ObjectNode) root;
        String inputId = findNode(name, nodes, INPUT_TITLE, node -> LOAD_IMAGE.equals(classOf(node)),
                "\"Load Image\" node");
        if (!nodes.get(inputId).path("inputs").has("image"))
        {
            throw invalid(name, "the node titled \"" + INPUT_TITLE + "\" has no image input");
        }
        String outputId = findNode(name, nodes, OUTPUT_TITLE, node -> OUTPUT_TYPES.contains(classOf(node)),
                "\"Save Image\" or \"Preview Image\" node");
        JsonNode images = nodes.get(outputId).path("inputs").path("images");
        // A link is [source node id, output index]; anything else is not an image arriving from the graph.
        if (!images.isArray() || images.size() != 2)
        {
            throw invalid(name, "the node titled \"" + OUTPUT_TITLE + "\" has no images input connected to the workflow");
        }
        return new ApiWorkflow(name, nodes, inputId, outputId, sha256(content));
    }

    /**
     * Only the output's {@code images} link is kept: the old node's other inputs (such as
     * {@code filename_prefix}) would not be valid on the replacement node.
     *
     * @param imageReference what ComfyUI answered the upload with, e.g. {@code "hentie-….png [temp]"}
     * @param websocketOutput whether {@link #WEBSOCKET_OUTPUT} is installed in that ComfyUI
     */
    public ObjectNode prepare(String imageReference, boolean websocketOutput)
    {
        ObjectNode prompt = nodes.deepCopy();
        ((ObjectNode) prompt.get(inputId).get("inputs")).put("image", imageReference);
        var output = (ObjectNode) prompt.get(outputId);
        JsonNode images = output.path("inputs").path("images");
        output.put("class_type", websocketOutput ? WEBSOCKET_OUTPUT : PREVIEW_OUTPUT);
        output.putObject("inputs").set("images", images.deepCopy());
        return prompt;
    }

    public String name()
    {
        return name;
    }

    /**
     * SHA-256 of the file, which cached results are keyed on: a changed export makes old results unreachable,
     * an unchanged one keeps them.
     */
    public String version()
    {
        return version;
    }

    public String outputNodeId()
    {
        return outputId;
    }

    /** For progress and error messages. */
    public String titleOf(String nodeId)
    {
        JsonNode node = nodes.path(nodeId);
        String title = node.path("_meta").path("title").asText("");
        return title.isBlank() ? node.path("class_type").asText(nodeId) : title;
    }

    private static String classOf(JsonNode node)
    {
        return node.path("class_type").asText();
    }

    private static String findNode(String name, ObjectNode workflow, String title, Predicate<JsonNode> fallback,
                                   String fallbackDescription) throws ComfyUiException
    {
        var titled = new ArrayList<String>();
        var candidates = new ArrayList<String>();
        for (var entry : workflow.properties())
        {
            if (title.equalsIgnoreCase(entry.getValue().path("_meta").path("title").asText().strip()))
            {
                titled.add(entry.getKey());
            }
            if (fallback.test(entry.getValue()))
            {
                candidates.add(entry.getKey());
            }
        }
        if (titled.size() == 1)
        {
            return titled.getFirst();
        }
        if (titled.size() > 1)
        {
            throw invalid(name, "more than one node is titled \"" + title + "\"");
        }
        if (candidates.size() == 1)
        {
            return candidates.getFirst();
        }
        throw invalid(name, candidates.isEmpty()
                ? "it has no " + fallbackDescription
                : "it has several nodes that could be the " + title.toLowerCase(Locale.ROOT)
                        + "; rename the right one to \"" + title + "\"");
    }

    private static ComfyUiException invalid(String name, String problem)
    {
        return new ComfyUiException(ComfyUiException.Reason.WORKFLOW_INVALID,
                "Workflow '" + name + "' can't be used: " + problem + ".");
    }

    static String sha256(byte[] content)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("Every JVM ships SHA-256", e);
        }
    }

    static String sha256(String text)
    {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
