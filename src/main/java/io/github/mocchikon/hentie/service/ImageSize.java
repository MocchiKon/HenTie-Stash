package io.github.mocchikon.hentie.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The size of an image, read from its header by hand because ImageIO has no reader for WebP or AVIF.
 * JPEG XL is not read: such a page is always decoded to PNG before anything asks for its size.
 */
public record ImageSize(int width, int height)
{
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    public long pixels()
    {
        return (long) width * height;
    }

    /** Empty for a format not read here, or a header too short or too damaged to say. */
    public static Optional<ImageSize> of(byte[] image)
    {
        try
        {
            if (startsWith(image, 0, PNG_SIGNATURE) && startsWith(image, 12, "IHDR"))
            {
                // IHDR is always the first chunk, and width and height are its first eight bytes.
                return size(u32be(image, 16), u32be(image, 20));
            }
            if (startsWith(image, 0, "GIF87a") || startsWith(image, 0, "GIF89a"))
            {
                return size(u16le(image, 6), u16le(image, 8));
            }
            if (startsWith(image, 0, "BM"))
            {
                return bmp(image);
            }
            if ((image[0] & 0xff) == 0xff && (image[1] & 0xff) == 0xd8)
            {
                return jpeg(image);
            }
            if (startsWith(image, 0, "RIFF") && startsWith(image, 8, "WEBP"))
            {
                return webp(image);
            }
            if (startsWith(image, 4, "ftyp"))
            {
                return avif(image);
            }
            return Optional.empty();
        }
        catch (IndexOutOfBoundsException e)
        {
            // A header cut short ends here instead of in a bounds check per read.
            return Optional.empty();
        }
    }

    private static Optional<ImageSize> bmp(byte[] image)
    {
        if (u32le(image, 14) == 12)
        {
            // The OS/2 header, with 16-bit sizes.
            return size(u16le(image, 18), u16le(image, 20));
        }
        // A negative height means the rows are stored top-down.
        return size(Math.abs((long) (int) u32le(image, 18)), Math.abs((long) (int) u32le(image, 22)));
    }

    /** The size is in the frame header, which comes after whatever tables and metadata precede it. */
    private static Optional<ImageSize> jpeg(byte[] image)
    {
        int at = 2;
        while (true)
        {
            if ((image[at] & 0xff) != 0xff)
            {
                return Optional.empty();
            }
            int marker = image[at + 1] & 0xff;
            if (marker == 0xff)
            {
                at++;
            }
            else if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd8)
            {
                at += 2;
            }
            else if (marker == 0xd9 || marker == 0xda)
            {
                // The end of the image, or its data, with no frame header before it.
                return Optional.empty();
            }
            else if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc)
            {
                // Length, sample precision, then height and width.
                return size(u16be(image, at + 7), u16be(image, at + 5));
            }
            else
            {
                at += 2 + u16be(image, at + 2);
            }
        }
    }

    /** The first chunk names one of three bitstreams, each storing the size differently. */
    private static Optional<ImageSize> webp(byte[] image)
    {
        if (startsWith(image, 12, "VP8 "))
        {
            // Lossy: a frame tag and the 9d 01 2a start code, then 14-bit width and height.
            return size(u16le(image, 26) & 0x3fff, u16le(image, 28) & 0x3fff);
        }
        if (startsWith(image, 12, "VP8L"))
        {
            // Lossless: a signature byte, then width - 1 and height - 1 in 14 bits each.
            long bits = u32le(image, 21);
            return size((bits & 0x3fff) + 1, (bits >> 14 & 0x3fff) + 1);
        }
        if (startsWith(image, 12, "VP8X"))
        {
            // Extended (alpha, animation, metadata): the canvas, minus one, in 24 bits each.
            return size(u24le(image, 24) + 1, u24le(image, 27) + 1);
        }
        return Optional.empty();
    }

    /**
     * The size is in an {@code ispe} property under {@code meta/iprp/ipco}. The largest one is the picture;
     * the others are an alpha plane or a thumbnail.
     */
    private static Optional<ImageSize> avif(byte[] image)
    {
        int[] meta = box(image, 0, image.length, "meta");
        // meta is a full box: its children follow four bytes of version and flags.
        int[] iprp = meta == null ? null : box(image, meta[0] + 4, meta[1], "iprp");
        int[] ipco = iprp == null ? null : box(image, iprp[0], iprp[1], "ipco");
        Optional<ImageSize> largest = Optional.empty();
        for (int[] ispe = ipco == null ? null : box(image, ipco[0], ipco[1], "ispe"); ispe != null;
             ispe = box(image, ispe[1], ipco[1], "ispe"))
        {
            // A full box too: version and flags, then width and height.
            Optional<ImageSize> size = size(u32be(image, ispe[0] + 4), u32be(image, ispe[0] + 8));
            if (size.isPresent() && (largest.isEmpty() || size.get().pixels() > largest.get().pixels()))
            {
                largest = size;
            }
        }
        return largest;
    }

    /** The first box of this type among those in [from, to): where its content starts and where it ends. */
    private static int[] box(byte[] image, int from, int to, String type)
    {
        int at = from;
        while (at + 8 <= to)
        {
            long size = u32be(image, at);
            int header = 8;
            if (size == 1)
            {
                size = u32be(image, at + 8) << 32 | u32be(image, at + 12);
                header = 16;
            }
            else if (size == 0)
            {
                size = to - at;
            }
            if (size < header || size > to - at)
            {
                return null;
            }
            if (startsWith(image, at + 4, type))
            {
                return new int[] {at + header, at + (int) size};
            }
            at += (int) size;
        }
        return null;
    }

    private static Optional<ImageSize> size(long width, long height)
    {
        return width > 0 && height > 0 && width <= Integer.MAX_VALUE && height <= Integer.MAX_VALUE
                ? Optional.of(new ImageSize((int) width, (int) height)) : Optional.empty();
    }

    private static boolean startsWith(byte[] image, int at, String ascii)
    {
        return startsWith(image, at, ascii.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean startsWith(byte[] image, int at, byte[] prefix)
    {
        if (at + prefix.length > image.length)
        {
            return false;
        }
        for (int i = 0; i < prefix.length; i++)
        {
            if (image[at + i] != prefix[i])
            {
                return false;
            }
        }
        return true;
    }

    private static int u16le(byte[] b, int at)
    {
        return b[at] & 0xff | (b[at + 1] & 0xff) << 8;
    }

    private static int u16be(byte[] b, int at)
    {
        return (b[at] & 0xff) << 8 | b[at + 1] & 0xff;
    }

    private static long u24le(byte[] b, int at)
    {
        return b[at] & 0xff | (b[at + 1] & 0xff) << 8 | (long) (b[at + 2] & 0xff) << 16;
    }

    private static long u32le(byte[] b, int at)
    {
        return u24le(b, at) | (long) (b[at + 3] & 0xff) << 24;
    }

    private static long u32be(byte[] b, int at)
    {
        return (long) (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | b[at + 3] & 0xff;
    }
}
