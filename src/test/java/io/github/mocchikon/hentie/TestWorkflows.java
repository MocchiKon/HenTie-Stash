package io.github.mocchikon.hentie;

/** ComfyUI workflows in the shapes a user really produces. */
public final class TestWorkflows
{
    private TestWorkflows()
    {
    }

    /** As ComfyUI's File > Export (API) writes it. */
    public static final String UPSCALE = """
            {
              "1": {"inputs": {"image": "example.png"}, "class_type": "LoadImage", "_meta": {"title": "Load Image"}},
              "2": {"inputs": {"model_name": "4x.pth"}, "class_type": "UpscaleModelLoader", "_meta": {"title": "Load Upscale Model"}},
              "3": {"inputs": {"upscale_model": ["2", 0], "image": ["1", 0]}, "class_type": "ImageUpscaleWithModel",
                    "_meta": {"title": "Upscale Image (using Model)"}},
              "4": {"inputs": {"filename_prefix": "ComfyUI", "images": ["3", 0]}, "class_type": "SaveImage",
                    "_meta": {"title": "Save Image"}}
            }
            """;

    /** Another model: another version of {@link #UPSCALE}, or another workflow. */
    public static final String UPSCALE_2X = UPSCALE.replace("4x.pth", "2x.pth");

    /** What File > Save writes instead of File > Export (API). */
    public static final String REGULAR_FORMAT = "{\"last_node_id\": 4, \"nodes\": [], \"links\": [], \"version\": 0.4}";

    /** Two Load Image nodes and none titled "Input", so nothing says which one gets the page. */
    public static final String AMBIGUOUS = """
            {
              "1": {"inputs": {"image": "a.png"}, "class_type": "LoadImage", "_meta": {"title": "Load Image"}},
              "2": {"inputs": {"image": "b.png"}, "class_type": "LoadImage", "_meta": {"title": "Load Image"}},
              "3": {"inputs": {"images": ["1", 0]}, "class_type": "SaveImage", "_meta": {"title": "Save Image"}}
            }
            """;
}
