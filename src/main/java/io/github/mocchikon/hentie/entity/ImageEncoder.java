package io.github.mocchikon.hentie.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The command line is built here, not by the caller, because {@code cjxl} takes {@code INPUT OUTPUT [OPTIONS]}
 * and {@code avifenc} takes {@code [OPTIONS] INPUT OUTPUT}: the same user-typed arguments go in two positions.
 *
 * <p>ORDINAL in the database: <b>never reorder these constants</b>.
 */
public enum ImageEncoder
{
    /** JPEG XL via {@code cjxl}. Reads PNG/APNG/GIF/JPEG and the PNM family. */
    JXL("jxl", "cjxl", Set.of("png", "apng", "gif", "jpg", "jpeg", "ppm", "pnm", "pfm", "pam", "pgx")),

    /** AVIF via {@code avifenc}. Reads JPEG, PNG and y4m - nothing else. */
    AVIF("avif", "avifenc", Set.of("png", "jpg", "jpeg", "y4m"));

    /** Enough for the longest signature {@link #producedValidFile} checks (JXL's box). */
    public static final int HEADER_BYTES = 12;

    private final String extension;
    private final String binary;
    private final Set<String> inputExtensions;

    ImageEncoder(String extension, String binary, Set<String> inputExtensions)
    {
        this.extension = extension;
        this.binary = binary;
        this.inputExtensions = inputExtensions;
    }

    public String getExtension()
    {
        return extension;
    }

    /** Without a platform suffix; {@code ImageToolLocator} resolves it. */
    public String getBinary()
    {
        return binary;
    }

    /** False means the input needs a lossless PNG detour first. */
    public boolean accepts(String extension)
    {
        return extension != null && inputExtensions.contains(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * Guards against a tool that exited 0 without writing a usable file: an empty output is always "smaller",
     * and the original is deleted right after. A floor, not a guarantee - a file truncated at the end needs a
     * full decode to catch.
     */
    public boolean producedValidFile(byte[] header)
    {
        return switch (this)
        {
            // Either a raw codestream (FF 0A) or the ISOBMFF container starting with the JXL signature box.
            case JXL -> startsWith(header, 0xFF, 0x0A)
                    || startsWith(header, 0x00, 0x00, 0x00, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, 0x87, 0x0A);
            // ISOBMFF: a size field, then the 'ftyp' box type. The brand itself is not pinned - avifenc
            // picks between avif/avis, and refusing a valid file over a brand would cost a real saving.
            case AVIF -> header.length >= HEADER_BYTES && header[4] == 'f' && header[5] == 't'
                    && header[6] == 'y' && header[7] == 'p';
        };
    }

    private static boolean startsWith(byte[] header, int... signature)
    {
        if (header.length < signature.length)
        {
            return false;
        }
        for (int i = 0; i < signature.length; i++)
        {
            if (header[i] != (byte) signature[i])
            {
                return false;
            }
        }
        return true;
    }

    public List<String> commandLine(String executable, String input, String output, List<String> args)
    {
        return switch (this)
        {
            case JXL -> concat(List.of(executable, input, output), args);
            case AVIF -> concat(concat(List.of(executable), args), List.of(input, output));
        };
    }

    private static List<String> concat(List<String> first, List<String> second)
    {
        var all = new ArrayList<String>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }
}
