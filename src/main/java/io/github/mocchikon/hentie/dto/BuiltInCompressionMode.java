package io.github.mocchikon.hentie.dto;

/**
 * Built-in modes are not rows, so every install has them and none can be edited into something that no
 * longer matches its name.
 *
 * <p>The constant name <i>is</i> the stored key, so renaming one orphans every stored choice.
 */
public enum BuiltInCompressionMode
{
    /** The default everywhere: bytes are stored exactly as they arrived. */
    NONE("None"),
    LOSSLESS("Lossless"),
    HIGH_REDUCTION("High reduction"),
    VERY_HIGH_REDUCTION("Very high reduction");

    /** Not a mode: the dropdown entry that opens the custom-mode page. Outside the enum because nothing may store it. */
    public static final String CUSTOM_KEY = "CUSTOM";

    /** Reserved as a mode name, so no user mode shows up twice as "Custom" in the list. */
    public static final String CUSTOM_LABEL = "Custom";

    /** A user mode's key is {@code custom:<id>}. */
    public static final String CUSTOM_PREFIX = "custom:";

    private final String displayName;

    BuiltInCompressionMode(String displayName)
    {
        this.displayName = displayName;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public String getKey()
    {
        return name();
    }
}
