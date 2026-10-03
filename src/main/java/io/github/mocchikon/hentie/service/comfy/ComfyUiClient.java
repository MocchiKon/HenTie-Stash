package io.github.mocchikon.hentie.service.comfy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.comfy.ComfyUiException.Reason;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * The only class that speaks ComfyUI's protocol; it decides nothing ({@link PageProcessingService} does).
 *
 * <p>A run is followed on the websocket rather than by polling {@code /history}, because the websocket also
 * carries the progress and, with {@link ApiWorkflow#WEBSOCKET_OUTPUT}, the result itself. One socket per run
 * under its own client id, since ComfyUI sends a prompt's events only to the client that queued it.
 *
 * <p>The address is read from Settings on every call, so a change needs no restart. HTTP/1.1, because
 * ComfyUI has no HTTP/2 and a websocket is an HTTP/1.1 upgrade anyway.
 */
@Component
public class ComfyUiClient
{
    private static final Logger log = LoggerFactory.getLogger(ComfyUiClient.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code protocol.BinaryEventTypes.PREVIEW_IMAGE}: an encoded image follows the 8-byte header. */
    private static final int PREVIEW_IMAGE_EVENT = 1;

    /** The header's format word for PNG, the only format the websocket output sends. */
    private static final int PNG_FORMAT = 2;

    /** A node appears only when ComfyUI restarts, so asking per page would be waste; short enough to notice one. */
    private static final Duration NODE_CHECK_TTL = Duration.ofSeconds(30);

    /** Small calls must not wait as long as an upload may. */
    private static final Duration SMALL_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** The Settings field's {@code maxlength}. */
    public static final int MAX_ADDRESS_LENGTH = 255;

    /**
     * A refused connection costs ~2 s on Windows (it retries the SYN), so calls within this window after one fail
     * at once instead. Short, because it is also how long a ComfyUI that just came up is still reported down.
     */
    private static final long REFUSAL_MEMORY_MILLIS = 3_000;

    private final AppProperties appProperties;
    private final SettingsService settingsService;
    private final HttpClient http;
    private final String defaultUrl;

    private volatile NodeCheck websocketOutputCheck;
    private volatile Refusal lastRefusal;

    private record NodeCheck(String baseUrl, boolean present, long checkedAtMillis) {}

    private record Refusal(String baseUrl, long atMillis, String message) {}

    public record SystemInfo(String version, String device) {}

    public record UserFile(String path, long size, long modifiedMillis) {}

    /** Called on the websocket's thread, so it must not block. */
    public interface RunListener
    {
        /** {@code queueRemaining} includes this prompt. */
        void waiting(int queueRemaining);

        /** {@code max} is 0 until the node reports how far it has got. */
        void running(String node, int value, int max);

        /**
         * Only fetching the result is left. Called on the thread that called {@link #run}, not the websocket's,
         * so it may decide what an interrupt of that thread means from now on.
         */
        void finished();
    }

    public ComfyUiClient(AppProperties appProperties, SettingsService settingsService)
    {
        this.appProperties = appProperties;
        this.settingsService = settingsService;
        // Fails startup rather than falling back to ComfyUI's usual port, which would reach whatever runs there.
        String configured = appProperties.getComfyui().getDefaultUrl();
        this.defaultUrl = normalizeAddress(configured).filter(StringUtils::isNotEmpty).orElseThrow(() ->
                new IllegalStateException("app.comfyui.default-url is not an http(s) address: '" + configured + "'"));
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.max(1, appProperties.getComfyui().getConnectTimeoutSeconds())))
                .build();
    }

    @PreDestroy
    void close()
    {
        http.shutdownNow();
    }

    // ---- the address ------------------------------------------------------

    /** {@code scheme://host[:port][/path]}, never ending in a slash. */
    public String baseUrl()
    {
        return normalizeAddress(settingsService.getComfyUiUrl()).filter(StringUtils::isNotEmpty).orElse(defaultUrl);
    }

    /**
     * Blank stays blank (the default); a missing scheme means {@code http://}. A path is kept for a reverse
     * proxy, which is why request URLs are appended to the address, never resolved against it.
     *
     * @return empty when the value cannot be an address
     */
    public static Optional<String> normalizeAddress(String raw)
    {
        String value = StringUtils.strip(raw);
        if (StringUtils.isEmpty(value))
        {
            return Optional.of("");
        }
        // SQLite enforces no VARCHAR(n), so the limit is kept here.
        if (value.length() > MAX_ADDRESS_LENGTH)
        {
            return Optional.empty();
        }
        if (!value.contains("://"))
        {
            value = "http://" + value;
        }
        try
        {
            var uri = new URI(value);
            String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme) || StringUtils.isBlank(uri.getHost())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null)
            {
                return Optional.empty();
            }
            String path = StringUtils.stripEnd(StringUtils.defaultString(uri.getRawPath()), "/");
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            return Optional.of(scheme + "://" + uri.getRawAuthority().replaceFirst(":\\d+$", "") + port + path);
        }
        catch (URISyntaxException e)
        {
            return Optional.empty();
        }
    }

    // ---- small calls ------------------------------------------------------

    public SystemInfo systemStats() throws ComfyUiException, InterruptedException
    {
        return systemStats(false);
    }

    private SystemInfo systemStats(boolean probe) throws ComfyUiException, InterruptedException
    {
        HttpResponse<byte[]> response = sendRaw(get("/system_stats", SMALL_REQUEST_TIMEOUT), "reach ComfyUI", probe);
        requireOk(response, "reach ComfyUI");
        JsonNode stats = parse(response.body(), "reach ComfyUI");
        JsonNode device = stats.path("devices").path(0);
        return new SystemInfo(stats.path("system").path("comfyui_version").asText(""),
                device.path("name").asText(device.path("type").asText("")));
    }

    /** Never answered from a remembered refusal: the launcher waiting for ComfyUI to come up needs the truth. */
    public boolean isReachable() throws InterruptedException
    {
        try
        {
            systemStats(true);
            return true;
        }
        catch (ComfyUiException e)
        {
            return false;
        }
    }

    /**
     * @param dir relative to the user's folder ({@code user/default} in a single-user ComfyUI)
     * @return empty when the folder does not exist
     */
    public Optional<List<UserFile>> listUserFiles(String dir) throws ComfyUiException, InterruptedException
    {
        String action = "list the workflows in '" + dir + "'";
        HttpResponse<byte[]> response = sendRaw(get("/userdata?dir=" + encode(dir) + "&recurse=true&full_info=true",
                SMALL_REQUEST_TIMEOUT), action);
        if (response.statusCode() == 404)
        {
            return Optional.empty();
        }
        requireOk(response, action);
        var files = new ArrayList<UserFile>();
        for (JsonNode file : parse(response.body(), action))
        {
            files.add(new UserFile(file.path("path").asText(), file.path("size").asLong(),
                    file.path("modified").asLong()));
        }
        return Optional.of(files);
    }

    public byte[] readUserFile(String path) throws ComfyUiException, InterruptedException
    {
        String action = "read '" + path + "'";
        HttpResponse<byte[]> response = sendRaw(get("/userdata/" + encode(path), SMALL_REQUEST_TIMEOUT), action);
        if (response.statusCode() == 404)
        {
            throw new ComfyUiException(Reason.WORKFLOW_NOT_FOUND, "ComfyUI has no file '" + path + "' in its user folder.");
        }
        requireOk(response, action);
        return response.body();
    }

    public boolean supportsWebsocketOutput() throws ComfyUiException, InterruptedException
    {
        String baseUrl = baseUrl();
        NodeCheck check = websocketOutputCheck;
        long now = System.currentTimeMillis();
        if (check != null && check.baseUrl().equals(baseUrl) && now - check.checkedAtMillis() < NODE_CHECK_TTL.toMillis())
        {
            return check.present();
        }
        String action = "look up the " + ApiWorkflow.WEBSOCKET_OUTPUT + " node";
        JsonNode info = parse(send(get("/object_info/" + ApiWorkflow.WEBSOCKET_OUTPUT, SMALL_REQUEST_TIMEOUT), action),
                action);
        boolean present = info.has(ApiWorkflow.WEBSOCKET_OUTPUT);
        websocketOutputCheck = new NodeCheck(baseUrl, present, now);
        return present;
    }

    // ---- running a workflow -----------------------------------------------

    /**
     * ComfyUI skips writing an upload whose name and content it already has, so naming the image after its
     * content writes each page once, however many workflows run on it.
     *
     * @return the reference a Load Image node takes
     */
    public String uploadTempImage(byte[] image, String fileName) throws ComfyUiException, InterruptedException
    {
        String boundary = "----hentie" + UUID.randomUUID().toString().replace("-", "");
        var body = new ByteArrayOutputStream(image.length + 512);
        writePart(body, boundary, "Content-Disposition: form-data; name=\"image\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream", image);
        writePart(body, boundary, "Content-Disposition: form-data; name=\"type\"", "temp".getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(uri("/upload/image"))
                .timeout(requestTimeout())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        JsonNode answer = parse(send(request, "upload the page"), "upload the page");
        String name = answer.path("name").asText("");
        if (name.isEmpty())
        {
            throw new ComfyUiException(Reason.PROTOCOL, "ComfyUI accepted the page but did not say where it put it: " + answer);
        }
        String subfolder = answer.path("subfolder").asText("");
        // "[temp]" tells Load Image to read from the temp folder rather than from input/.
        return (subfolder.isEmpty() ? name : subfolder + "/" + name) + " [temp]";
    }

    /**
     * Only the output node's branch runs ({@code partial_execution_targets}), so debug Save/Preview nodes write
     * nothing. A timed-out or interrupted run is cancelled in ComfyUI, so it stops holding the GPU.
     *
     * @param websocketOutput whether {@code prompt} was prepared with {@link ApiWorkflow#WEBSOCKET_OUTPUT}
     * @return the first image the output produced, as PNG
     */
    public byte[] run(ApiWorkflow workflow, ObjectNode prompt, boolean websocketOutput, RunListener listener)
            throws ComfyUiException, InterruptedException
    {
        String clientId = "hentie-" + UUID.randomUUID();
        // Chosen here, so the socket recognizes the prompt's events from the very first.
        String promptId = UUID.randomUUID().toString();
        var session = new RunSession(workflow, promptId, websocketOutput, listener);
        WebSocket socket = connect(clientId, session);
        try
        {
            String queued;
            try
            {
                queued = queue(workflow, prompt, promptId, clientId);
            }
            catch (InterruptedException e)
            {
                // The prompt may have reached ComfyUI all the same, under the id chosen above.
                cancel(promptId);
                throw e;
            }
            session.alsoKnownAs(queued);
            RunSession.Finished finished = await(workflow, session, queued);
            listener.finished();
            return websocketOutput ? finished.image() : download(workflow, queued, finished.files());
        }
        finally
        {
            close(socket, session);
        }
    }

    private RunSession.Finished await(ApiWorkflow workflow, RunSession session, String promptId)
            throws ComfyUiException, InterruptedException
    {
        long timeoutSeconds = Math.max(1, appProperties.getComfyui().getJobTimeoutSeconds());
        try
        {
            return session.result().get(timeoutSeconds, TimeUnit.SECONDS);
        }
        catch (TimeoutException e)
        {
            cancel(promptId);
            throw new ComfyUiException(Reason.TIMEOUT, "Workflow '" + workflow.name() + "' did not finish within "
                    + timeoutSeconds + " s, so it was cancelled in ComfyUI.");
        }
        catch (InterruptedException e)
        {
            cancel(promptId);
            throw e;
        }
        catch (ExecutionException e)
        {
            if (e.getCause() instanceof ComfyUiException failure)
            {
                throw failure;
            }
            throw new ComfyUiException(Reason.PROTOCOL, "Following workflow '" + workflow.name() + "' failed", e.getCause());
        }
    }

    private WebSocket connect(String clientId, RunSession session) throws ComfyUiException, InterruptedException
    {
        String base = baseUrl();
        String socketBase = base.startsWith("https://") ? "wss://" + base.substring(8) : "ws://" + base.substring(7);
        var opening = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, appProperties.getComfyui().getConnectTimeoutSeconds())))
                .buildAsync(URI.create(socketBase + "/ws?clientId=" + encode(clientId)), session);
        try
        {
            return opening.get(SMALL_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
        catch (ExecutionException | TimeoutException e)
        {
            // A socket that opens after all belongs to no run, and ComfyUI would keep it open.
            opening.thenAccept(WebSocket::abort);
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            throw new ComfyUiException(Reason.UNREACHABLE, "Cannot open ComfyUI's websocket at " + base
                    + " (" + cause + "). Is ComfyUI running?", cause);
        }
        catch (InterruptedException e)
        {
            opening.thenAccept(WebSocket::abort);
            throw e;
        }
    }

    /**
     * With the handshake: aborting resets the connection, and on Windows ComfyUI then logs a
     * {@code ConnectionResetError} traceback per page into the console Settings shows.
     */
    private static void close(WebSocket socket, RunSession session)
    {
        try
        {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
            session.closed().get(2, TimeUnit.SECONDS);
        }
        catch (ExecutionException | TimeoutException ignored)
        {
            // closed already or not answering; aborted below either way
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            if (!session.closed().isDone())
            {
                socket.abort();
            }
        }
    }

    /** @return ours, unless this ComfyUI version picks its own id */
    private String queue(ApiWorkflow workflow, ObjectNode prompt, String promptId, String clientId)
            throws ComfyUiException, InterruptedException
    {
        ObjectNode payload = JSON.createObjectNode();
        payload.set("prompt", prompt);
        payload.put("prompt_id", promptId);
        payload.put("client_id", clientId);
        payload.putArray("partial_execution_targets").add(workflow.outputNodeId());

        String action = "queue workflow '" + workflow.name() + "'";
        HttpResponse<byte[]> response = sendRaw(postJson("/prompt", payload, requestTimeout()), action);
        if (response.statusCode() == 400)
        {
            throw new ComfyUiException(Reason.WORKFLOW_INVALID, "ComfyUI rejected workflow '" + workflow.name() + "': "
                    + describeValidationError(parseQuietly(response.body()), workflow));
        }
        requireOk(response, action);
        String queued = parse(response.body(), action).path("prompt_id").asText("");
        return queued.isEmpty() ? promptId : queued;
    }

    /** Turns ComfyUI's validation answer into e.g. "Load Upscale Model: Value not in list: model_name: …". */
    static String describeValidationError(JsonNode body, ApiWorkflow workflow)
    {
        var problems = new ArrayList<String>();
        for (var entry : body.path("node_errors").properties())
        {
            String title = workflow.titleOf(entry.getKey());
            for (JsonNode error : entry.getValue().path("errors"))
            {
                String details = error.path("details").asText("");
                problems.add(title + ": " + error.path("message").asText("") + (details.isEmpty() ? "" : ": " + details));
            }
        }
        // Errors tied to no node, e.g. a node type that is not installed.
        return problems.isEmpty() ? body.path("error").path("message").asText(body.toString()) : String.join("; ", problems);
    }

    private byte[] download(ApiWorkflow workflow, String promptId, List<JsonNode> announced)
            throws ComfyUiException, InterruptedException
    {
        List<JsonNode> files = announced;
        if (files.isEmpty())
        {
            // A result from ComfyUI's own cache is not always announced on the socket; the history names it.
            JsonNode history = parse(send(get("/history/" + encode(promptId), SMALL_REQUEST_TIMEOUT), "read the result"),
                    "read the result");
            files = new ArrayList<>();
            history.path(promptId).path("outputs").path(workflow.outputNodeId()).path("images").forEach(files::add);
        }
        if (files.isEmpty())
        {
            throw new ComfyUiException(Reason.JOB_FAILED,
                    "Workflow '" + workflow.name() + "' finished, but its output node produced no image.");
        }
        JsonNode file = files.getFirst();
        String query = "filename=" + encode(file.path("filename").asText())
                + "&subfolder=" + encode(file.path("subfolder").asText(""))
                + "&type=" + encode(file.path("type").asText("temp"));
        return send(get("/view?" + query, requestTimeout()), "download the result");
    }

    /**
     * Best effort. {@code /interrupt} is sent only when {@code /queue} lists the prompt as running: an older
     * ComfyUI ignores the prompt id and would stop the user's own generation. Deleted first, a waiting prompt
     * cannot start, so checking after the delete cannot miss it.
     */
    private void cancel(String promptId)
    {
        try
        {
            ObjectNode delete = JSON.createObjectNode();
            delete.putArray("delete").add(promptId);
            http.send(postJson("/queue", delete, SMALL_REQUEST_TIMEOUT), HttpResponse.BodyHandlers.discarding());
            if (mayBeRunning(promptId))
            {
                http.send(postJson("/interrupt", JSON.createObjectNode().put("prompt_id", promptId), SMALL_REQUEST_TIMEOUT),
                        HttpResponse.BodyHandlers.discarding());
            }
        }
        catch (IOException e)
        {
            log.warn("Could not cancel ComfyUI prompt {}: {}", promptId, e.toString());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Each entry is {@code [number, prompt_id, ...]}. An unreadable answer counts as running: left running, the
     * prompt would hold the GPU for minutes for nobody.
     */
    private boolean mayBeRunning(String promptId) throws IOException, InterruptedException
    {
        HttpResponse<byte[]> response = http.send(get("/queue", SMALL_REQUEST_TIMEOUT),
                HttpResponse.BodyHandlers.ofByteArray());
        JsonNode running = parseQuietly(response.body()).path("queue_running");
        if (response.statusCode() != 200 || !running.isArray())
        {
            return true;
        }
        for (JsonNode entry : running)
        {
            if (promptId.equals(entry.path(1).asText()))
            {
                return true;
            }
        }
        return false;
    }

    /** The JDK calls a listener's methods one at a time, so the partial-message buffers need no locking. */
    private static final class RunSession implements WebSocket.Listener
    {
        record Finished(byte[] image, List<JsonNode> files) {}

        private final ApiWorkflow workflow;
        private final boolean websocketOutput;
        private final RunListener listener;
        private final Set<String> promptIds = ConcurrentHashMap.newKeySet();
        /**
         * Messages (String or byte[]) held until {@link #alsoKnownAs}: a ComfyUI that picks its own id can finish
         * before that id is known, and judging them early would drop the completion. Guarded by this session.
         */
        private final List<Object> unclaimed = new ArrayList<>();
        private boolean claimed;
        private final CompletableFuture<Finished> result = new CompletableFuture<>();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        private final List<JsonNode> files = new ArrayList<>();
        private String executing;
        private boolean started;
        private byte[] image;

        RunSession(ApiWorkflow workflow, String promptId, boolean websocketOutput, RunListener listener)
        {
            this.workflow = workflow;
            this.websocketOutput = websocketOutput;
            this.listener = listener;
            this.promptIds.add(promptId);
        }

        CompletableFuture<Finished> result()
        {
            return result;
        }

        CompletableFuture<Void> closed()
        {
            return closed;
        }

        /** An older ComfyUI ignores the id it was given and answers with one of its own. */
        synchronized void alsoKnownAs(String promptId)
        {
            promptIds.add(promptId);
            claimed = true;
            unclaimed.forEach(this::deliver);
            unclaimed.clear();
        }

        private synchronized void deliver(Object message)
        {
            if (!claimed)
            {
                unclaimed.add(message);
            }
            else if (message instanceof String text)
            {
                handle(text);
            }
            else
            {
                handleBinary((byte[]) message);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket)
        {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            text.append(data);
            if (last)
            {
                String message = text.toString();
                text.setLength(0);
                deliver(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last)
        {
            // The buffer is only valid until this method returns.
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binary.writeBytes(chunk);
            if (last)
            {
                byte[] frame = binary.toByteArray();
                binary.reset();
                deliver(frame);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
        {
            fail(Reason.UNREACHABLE, "ComfyUI closed the connection while running workflow '" + workflow.name() + "'.");
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error)
        {
            fail(Reason.UNREACHABLE, "The connection to ComfyUI broke while running workflow '" + workflow.name()
                    + "': " + error);
            closed.complete(null);
        }

        private void handle(String message)
        {
            JsonNode event = parseQuietly(message.getBytes(StandardCharsets.UTF_8));
            JsonNode data = event.path("data");
            switch (event.path("type").asText())
            {
                case "status" ->
                {
                    if (!started)
                    {
                        listener.waiting(data.path("status").path("exec_info").path("queue_remaining").asInt(0));
                    }
                }
                case "execution_start" ->
                {
                    if (ours(data))
                    {
                        started = true;
                        listener.running(null, 0, 0);
                    }
                }
                case "executing" ->
                {
                    if (ours(data))
                    {
                        JsonNode node = data.path("node");
                        if (node.isNull())
                        {
                            // "Done" in every ComfyUI version; newer ones send execution_success first.
                            succeed();
                        }
                        else
                        {
                            started = true;
                            executing = node.asText();
                            listener.running(workflow.titleOf(executing), 0, 0);
                        }
                    }
                }
                case "progress" ->
                {
                    if (ours(data))
                    {
                        listener.running(workflow.titleOf(data.path("node").asText(executing)),
                                data.path("value").asInt(), data.path("max").asInt());
                    }
                }
                case "executed" ->
                {
                    if (ours(data) && workflow.outputNodeId().equals(data.path("node").asText()))
                    {
                        data.path("output").path("images").forEach(files::add);
                    }
                }
                case "execution_success" ->
                {
                    if (ours(data))
                    {
                        succeed();
                    }
                }
                case "execution_error" ->
                {
                    if (ours(data))
                    {
                        fail(Reason.JOB_FAILED, "ComfyUI failed to run workflow '" + workflow.name() + "': "
                                + workflow.titleOf(data.path("node_id").asText(data.path("node_type").asText()))
                                + ": " + data.path("exception_message").asText("").strip());
                    }
                }
                case "execution_interrupted" ->
                {
                    if (ours(data))
                    {
                        fail(Reason.JOB_FAILED, "Workflow '" + workflow.name() + "' was interrupted in ComfyUI.");
                    }
                }
                default ->
                {
                    // other events (crystools, feature flags, ...) are not needed
                }
            }
        }

        /** Samplers send live previews the same way, so the executing node tells the result apart. */
        private void handleBinary(byte[] frame)
        {
            if (!websocketOutput || image != null || frame.length <= 8
                    || !workflow.outputNodeId().equals(executing))
            {
                return;
            }
            ByteBuffer header = ByteBuffer.wrap(frame, 0, 8);
            if (header.getInt() == PREVIEW_IMAGE_EVENT && header.getInt() == PNG_FORMAT)
            {
                image = Arrays.copyOfRange(frame, 8, frame.length);
            }
        }

        private boolean ours(JsonNode data)
        {
            return promptIds.contains(data.path("prompt_id").asText());
        }

        private void succeed()
        {
            if (websocketOutput && image == null)
            {
                fail(Reason.JOB_FAILED, "Workflow '" + workflow.name() + "' finished, but its output node sent no image.");
                return;
            }
            result.complete(new Finished(image, List.copyOf(files)));
        }

        private void fail(Reason reason, String message)
        {
            result.completeExceptionally(new ComfyUiException(reason, message));
        }
    }

    // ---- plumbing ---------------------------------------------------------

    private URI uri(String pathAndQuery)
    {
        return URI.create(baseUrl() + pathAndQuery);
    }

    private HttpRequest get(String pathAndQuery, Duration timeout)
    {
        return HttpRequest.newBuilder(uri(pathAndQuery)).timeout(timeout).GET().build();
    }

    private HttpRequest postJson(String path, JsonNode body, Duration timeout)
    {
        return HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private Duration requestTimeout()
    {
        return Duration.ofSeconds(Math.max(1, appProperties.getComfyui().getRequestTimeoutSeconds()));
    }

    private static void writePart(ByteArrayOutputStream body, String boundary, String headers, byte[] content)
    {
        body.writeBytes(("--" + boundary + "\r\n" + headers + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private byte[] send(HttpRequest request, String action) throws ComfyUiException, InterruptedException
    {
        HttpResponse<byte[]> response = sendRaw(request, action);
        requireOk(response, action);
        return response.body();
    }

    private HttpResponse<byte[]> sendRaw(HttpRequest request, String action) throws ComfyUiException, InterruptedException
    {
        return sendRaw(request, action, false);
    }

    /** @param probe connect even when a refusal was just seen - see {@link #REFUSAL_MEMORY_MILLIS} */
    private HttpResponse<byte[]> sendRaw(HttpRequest request, String action, boolean probe)
            throws ComfyUiException, InterruptedException
    {
        String baseUrl = baseUrl();
        Refusal refusal = lastRefusal;
        if (!probe && refusal != null && refusal.baseUrl().equals(baseUrl)
                && System.currentTimeMillis() - refusal.atMillis() < REFUSAL_MEMORY_MILLIS)
        {
            throw new ComfyUiException(Reason.UNREACHABLE, refusal.message());
        }
        try
        {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            lastRefusal = null;
            return response;
        }
        catch (ConnectException | HttpConnectTimeoutException e)
        {
            String message = "Cannot connect to ComfyUI at " + baseUrl + ". Is ComfyUI running?";
            lastRefusal = new Refusal(baseUrl, System.currentTimeMillis(), message);
            throw new ComfyUiException(Reason.UNREACHABLE, message, e);
        }
        catch (HttpTimeoutException e)
        {
            // Connected, so only slow; no reason to tell the user to start it.
            throw new ComfyUiException(Reason.TIMEOUT, "ComfyUI at " + baseUrl() + " did not answer in time (trying to "
                    + action + ").", e);
        }
        catch (IOException e)
        {
            throw new ComfyUiException(Reason.UNREACHABLE, "Talking to ComfyUI at " + baseUrl() + " failed (trying to "
                    + action + "): " + e, e);
        }
    }

    private static void requireOk(HttpResponse<byte[]> response, String action) throws ComfyUiException
    {
        if (response.statusCode() != 200)
        {
            throw new ComfyUiException(Reason.PROTOCOL, "ComfyUI answered HTTP " + response.statusCode() + " when asked to "
                    + action + ": " + StringUtils.abbreviate(new String(response.body(), StandardCharsets.UTF_8), 300));
        }
    }

    private static JsonNode parse(byte[] body, String action) throws ComfyUiException
    {
        try
        {
            JsonNode node = JSON.readTree(body);
            if (node == null)
            {
                throw new IOException("empty body");
            }
            return node;
        }
        catch (IOException e)
        {
            throw new ComfyUiException(Reason.PROTOCOL, "ComfyUI's answer to '" + action + "' is not JSON: "
                    + StringUtils.abbreviate(new String(body, StandardCharsets.UTF_8), 300), e);
        }
    }

    private static JsonNode parseQuietly(byte[] body)
    {
        try
        {
            JsonNode node = JSON.readTree(body);
            return node == null ? JSON.createObjectNode() : node;
        }
        catch (IOException e)
        {
            return JSON.createObjectNode();
        }
    }

    /**
     * {@code %20}, not {@code +}: ComfyUI decodes {@code /userdata/{file}} with {@code unquote()}, which keeps a
     * {@code +}. A {@code /} is encoded too, since the file is one path segment of that route.
     */
    static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
