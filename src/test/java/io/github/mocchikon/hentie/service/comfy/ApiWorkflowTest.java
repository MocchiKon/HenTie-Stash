package io.github.mocchikon.hentie.service.comfy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mocchikon.hentie.TestWorkflows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

/** The workflow contract that {@code COMFYUI.md} promises the user. */
class ApiWorkflowTest
{
    private static final String UPSCALE = TestWorkflows.UPSCALE;

    /** Two loaders and two savers: only the titles say which is which. */
    private static final String TITLED = """
            {
              "1": {"inputs": {"image": "mask.png"}, "class_type": "LoadImage", "_meta": {"title": "Mask"}},
              "2": {"inputs": {"image": "page.png"}, "class_type": "LoadImage", "_meta": {"title": "Input"}},
              "3": {"inputs": {"images": ["2", 0]}, "class_type": "PreviewImage", "_meta": {"title": "Debug"}},
              "4": {"inputs": {"filename_prefix": "x", "images": ["1", 0]}, "class_type": "SaveImage", "_meta": {"title": " output "}}
            }
            """;

    @Test
    void shouldTakeTheOnlyLoadImageAndSaveImageWhenNothingIsTitled() throws Exception
    {
        // WHEN
        ApiWorkflow workflow = ApiWorkflow.parse("upscale", bytes(UPSCALE));

        // THEN
        assertThat(workflow.name()).isEqualTo("upscale");
        assertThat(workflow.outputNodeId()).isEqualTo("4");
        ObjectNode prompt = workflow.prepare("page.png [temp]", true);
        assertThat(prompt.path("1").path("inputs").path("image").asText()).isEqualTo("page.png [temp]");
    }

    /** Titles win over types, and ignore case and surrounding blanks, since users type them by hand. */
    @Test
    void shouldPreferTheNodesTitledInputAndOutput() throws Exception
    {
        // WHEN
        ApiWorkflow workflow = ApiWorkflow.parse("titled", bytes(TITLED));

        // THEN
        assertThat(workflow.outputNodeId()).isEqualTo("4");
        ObjectNode prompt = workflow.prepare("page.png [temp]", false);
        assertThat(prompt.path("2").path("inputs").path("image").asText()).isEqualTo("page.png [temp]");
        assertThat(prompt.path("1").path("inputs").path("image").asText()).isEqualTo("mask.png");
    }

    @Test
    void shouldRefuseSeveralCandidatesWithoutATitleNamingTheFix()
    {
        // GIVEN the titled workflow with its titles taken away.
        String untitled = TITLED.replace("\"Input\"", "\"Page\"").replace("\" output \"", "\"Result\"");

        // WHEN + THEN
        assertThatThrownBy(() -> ApiWorkflow.parse("two", bytes(untitled)))
                .isInstanceOfSatisfying(ComfyUiException.class,
                        e -> assertThat(e.reason()).isEqualTo(ComfyUiException.Reason.WORKFLOW_INVALID))
                .hasMessageContaining("Workflow 'two' can't be used")
                .hasMessageContaining("rename the right one to \"Input\"");
    }

    @Test
    void shouldRefuseTwoNodesWithTheSameTitle()
    {
        String twice = TITLED.replace("\"Debug\"", "\"Output\"");

        assertThatThrownBy(() -> ApiWorkflow.parse("twice", bytes(twice)))
                .hasMessageContaining("more than one node is titled \"Output\"");
    }

    /** The mistake a user makes most: saving with File > Save or Export instead of Export (API). */
    @Test
    void shouldRefuseTheRegularWorkflowFormatSayingHowToExport()
    {
        assertThatThrownBy(() -> ApiWorkflow.parse("ui", bytes(TestWorkflows.REGULAR_FORMAT)))
                .hasMessageContaining("saved in ComfyUI's regular format")
                .hasMessageContaining("File > Export (API)");
    }

    @Test
    void shouldRefuseWhatIsNotAWorkflowAtAll()
    {
        assertThatThrownBy(() -> ApiWorkflow.parse("broken", bytes("{not json"))).hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> ApiWorkflow.parse("empty", bytes("{}"))).hasMessageContaining("empty or not a workflow");
        assertThatThrownBy(() -> ApiWorkflow.parse("array", bytes("[1, 2]"))).hasMessageContaining("empty or not a workflow");
        assertThatThrownBy(() -> ApiWorkflow.parse("odd", bytes("{\"1\": {\"inputs\": {}}}")))
                .hasMessageContaining("not an API-format workflow");
    }

    @Test
    void shouldRefuseAWorkflowWithoutALoaderOrASaver()
    {
        String noSaver = UPSCALE.replace("\"SaveImage\"", "\"ImageScale\"");
        String noLoader = UPSCALE.replace("\"LoadImage\"", "\"EmptyImage\"");

        assertThatThrownBy(() -> ApiWorkflow.parse("a", bytes(noSaver)))
                .hasMessageContaining("it has no \"Save Image\" or \"Preview Image\" node");
        assertThatThrownBy(() -> ApiWorkflow.parse("b", bytes(noLoader)))
                .hasMessageContaining("it has no \"Load Image\" node");
    }

    /** An Output node whose image does not come from the graph could never carry the page's result. */
    @Test
    void shouldRefuseAnOutputWhoseImagesAreNotConnected()
    {
        String loose = UPSCALE.replace("\"images\": [\"3\", 0]", "\"images\": \"nothing\"");

        assertThatThrownBy(() -> ApiWorkflow.parse("loose", bytes(loose)))
                .hasMessageContaining("has no images input connected");
    }

    /** The replaced output takes only the image link: Save Image's {@code filename_prefix} has no meaning there. */
    @Test
    void shouldReplaceTheOutputByOneThatWritesNothingToComfyUisOutputFolder() throws Exception
    {
        // GIVEN
        ApiWorkflow workflow = ApiWorkflow.parse("upscale", bytes(UPSCALE));

        // WHEN
        ObjectNode websocket = workflow.prepare("p.png [temp]", true);
        ObjectNode preview = workflow.prepare("p.png [temp]", false);

        // THEN
        assertThat(websocket.path("4").path("class_type").asText()).isEqualTo(ApiWorkflow.WEBSOCKET_OUTPUT);
        assertThat(preview.path("4").path("class_type").asText()).isEqualTo(ApiWorkflow.PREVIEW_OUTPUT);
        for (JsonNode prompt : new JsonNode[] {websocket, preview})
        {
            JsonNode inputs = prompt.path("4").path("inputs");
            assertThat(inputs.size()).isEqualTo(1);
            assertThat(inputs.path("images").path(0).asText()).isEqualTo("3");
            assertThat(inputs.path("images").path(1).asInt()).isZero();
            // Everything else runs exactly as exported.
            assertThat(prompt.path("2").path("inputs").path("model_name").asText()).isEqualTo("4x.pth");
            assertThat(prompt.path("4").path("_meta").path("title").asText()).isEqualTo("Save Image");
        }
    }

    /** One parsed workflow serves every page, so preparing a run must never change it. */
    @Test
    void shouldLeaveTheParsedWorkflowUntouchedByARun() throws Exception
    {
        // GIVEN
        ApiWorkflow workflow = ApiWorkflow.parse("upscale", bytes(UPSCALE));
        workflow.prepare("first.png [temp]", true);

        // WHEN
        ObjectNode second = workflow.prepare("second.png [temp]", false);

        // THEN
        assertThat(second.path("1").path("inputs").path("image").asText()).isEqualTo("second.png [temp]");
        assertThat(second.path("4").path("class_type").asText()).isEqualTo(ApiWorkflow.PREVIEW_OUTPUT);
    }

    /** Results are cached under the version, so it must follow the file's content and nothing else. */
    @Test
    void shouldVersionAWorkflowByItsContent() throws Exception
    {
        String same = ApiWorkflow.parse("a", bytes(UPSCALE)).version();

        assertThat(ApiWorkflow.parse("renamed", bytes(UPSCALE)).version()).isEqualTo(same);
        assertThat(ApiWorkflow.parse("a", bytes(UPSCALE.replace("4x.pth", "2x.pth"))).version()).isNotEqualTo(same);
    }

    @Test
    void shouldNameANodeByItsTitleElseItsType() throws Exception
    {
        ApiWorkflow workflow = ApiWorkflow.parse("upscale",
                bytes(UPSCALE.replace(", \"_meta\": {\"title\": \"Load Upscale Model\"}", "")));

        assertThat(workflow.titleOf("3")).isEqualTo("Upscale Image (using Model)");
        assertThat(workflow.titleOf("2")).isEqualTo("UpscaleModelLoader");
    }

    private static byte[] bytes(String json)
    {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
