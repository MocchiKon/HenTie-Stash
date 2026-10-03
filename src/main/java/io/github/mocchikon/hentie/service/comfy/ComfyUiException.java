package io.github.mocchikon.hentie.service.comfy;

/**
 * A failure talking to ComfyUI or running a workflow. The message is shown to the user as it is, so it names
 * the workflow and says what to do; {@link #reason()} tells callers which advice applies.
 *
 * <p>Checked, because every caller handles it in its own way (the viewer shows the stored page, Settings
 * reports it beside the field).
 */
public class ComfyUiException extends Exception
{
    private static final long serialVersionUID = 1L;

    public enum Reason
    {
        /** Nothing answers at the configured address. */
        UNREACHABLE,
        /** No workflow file of that name in the configured folder. */
        WORKFLOW_NOT_FOUND,
        /** Not in API format, no or ambiguous Input/Output node, or rejected by ComfyUI (missing model or node). */
        WORKFLOW_INVALID,
        /** ComfyUI started the workflow and it failed (out of memory, a node's own error, interrupted). */
        JOB_FAILED,
        /** The workflow did not finish within {@code app.comfyui.job-timeout-seconds}. */
        TIMEOUT,
        /** An answer the app does not understand, usually from an unexpected ComfyUI version. */
        PROTOCOL
    }

    private final Reason reason;

    public ComfyUiException(Reason reason, String message)
    {
        super(message);
        this.reason = reason;
    }

    public ComfyUiException(Reason reason, String message, Throwable cause)
    {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason()
    {
        return reason;
    }
}
