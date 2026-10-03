package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.ImageCompressionMode;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * Built-in and user-defined modes are both flattened into this, so nothing downstream branches on which
 * kind it was handed.
 *
 * @param key                  e.g. {@code LOSSLESS} or {@code custom:7}
 * @param magickArgs           empty = ImageMagick is not run
 * @param minRelativeReduction percent that must be saved, else the original is kept; 0 = off
 * @param minAbsoluteReduction KB that must be saved, else the original is kept; 0 = off
 * @param formats              lower-case input extensions; empty = every image
 */
public record CompressionProfile(String key,
                                  String name,
                                  ImageEncoder encoder,
                                  List<String> encoderArgs,
                                  List<String> magickArgs,
                                  int minRelativeReduction,
                                  int minAbsoluteReduction,
                                  Set<String> formats)
{
    public static CompressionProfile of(String key, ImageCompressionMode mode)
    {
        return new CompressionProfile(key, mode.getName(), mode.getEncoder(),
                splitArgs(mode.getEncoderArgs()), splitArgs(mode.getMagickArgs()),
                mode.getMinRelativeReduction(), mode.getMinAbsoluteReduction(),
                parseFormats(mode.getFormats()));
    }

    /** Naming {@code jpg} or {@code jpeg} covers both spellings. */
    public boolean processes(String extension)
    {
        if (formats.isEmpty())
        {
            return true;
        }
        String ext = StringUtils.lowerCase(StringUtils.trimToEmpty(extension), Locale.ROOT);
        return formats.contains(ext) || (ext.equals("jpg") && formats.contains("jpeg"))
                || (ext.equals("jpeg") && formats.contains("jpg"));
    }

    public boolean usesImageMagick()
    {
        return !magickArgs.isEmpty();
    }

    /** Whitespace separates, double quotes group - for an argument with a space, like {@code -resize "50% 50%"}. */
    public static List<String> splitArgs(String raw)
    {
        if (StringUtils.isBlank(raw))
        {
            return List.of();
        }
        var args = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        for (char c : raw.toCharArray())
        {
            if (c == '"')
            {
                quoted = !quoted;
                started = true;
            }
            else if (!quoted && Character.isWhitespace(c))
            {
                if (started)
                {
                    args.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            }
            else
            {
                current.append(c);
                started = true;
            }
        }
        if (started)
        {
            args.add(current.toString());
        }
        return List.copyOf(args);
    }

    public static Set<String> parseFormats(String raw)
    {
        if (StringUtils.isBlank(raw))
        {
            return Set.of();
        }
        // Two backslashes: a single "\s" is Java's escape for one plain space, not the regex class.
        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(s -> StringUtils.lowerCase(StringUtils.removeStart(s.trim(), "."), Locale.ROOT))
                .filter(StringUtils::isNotBlank)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }
}
