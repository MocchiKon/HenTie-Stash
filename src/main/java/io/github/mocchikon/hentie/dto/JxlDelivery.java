package io.github.mocchikon.hentie.dto;

/**
 * Whether JPEG XL pages are decoded to PNG for the browser. A choice, not a constant, because decoding is
 * wasted bandwidth for a browser that can show JPEG XL.
 */
public enum JxlDelivery
{
    /** Detected by {@code app.js} into a cookie. Until it arrives the browser is assumed unable, which always renders. */
    AUTO("Automatic (detect what the browser supports)"),

    /** For a browser whose detection is unreliable, or an image proxy in between. */
    ALWAYS("Always send PNG"),

    NEVER("Never (send JPEG XL as stored)");

    private final String displayName;

    JxlDelivery(String displayName)
    {
        this.displayName = displayName;
    }

    public String getDisplayName()
    {
        return displayName;
    }
}
