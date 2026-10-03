package io.github.mocchikon.hentie.dto;

/**
 * @param custom true for the trailing "Custom" entry, which navigates instead of selecting a mode
 */
public record CompressionModeOption(String key, String label, boolean custom)
{
    public static CompressionModeOption of(String key, String label)
    {
        return new CompressionModeOption(key, label, false);
    }
}
