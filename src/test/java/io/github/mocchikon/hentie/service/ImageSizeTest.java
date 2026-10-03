package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.TestImages;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** WebP and AVIF, which ImageIO cannot write, are built byte by byte from their specifications. */
class ImageSizeTest
{
    private static final Optional<ImageSize> SIZE = Optional.of(new ImageSize(37, 21));

    @Test
    void shouldReadThePngHeader()
    {
        assertThat(ImageSize.of(TestImages.png(37, 21))).isEqualTo(SIZE);
    }

    @Test
    void shouldFindTheJpegFrameHeaderPastTheSegmentsBeforeIt() throws IOException
    {
        assertThat(ImageSize.of(written("jpg"))).isEqualTo(SIZE);
    }

    @Test
    void shouldReadTheGifHeader() throws IOException
    {
        assertThat(ImageSize.of(written("gif"))).isEqualTo(SIZE);
    }

    /** A negative height is a BMP stored top-down, not a negative size. */
    @Test
    void shouldReadTheBmpHeaderStoredEitherWayUp() throws IOException
    {
        // GIVEN ImageIO's bottom-up BMP, and the same one marked top-down
        byte[] bottomUp = written("bmp");
        byte[] topDown = bottomUp.clone();
        writeIntLe(topDown, 22, -21);

        // THEN
        assertThat(ImageSize.of(bottomUp)).isEqualTo(SIZE);
        assertThat(ImageSize.of(topDown)).isEqualTo(SIZE);
    }

    @Test
    void shouldReadEachOfTheThreeWebpBitstreams()
    {
        // GIVEN lossy: frame tag, start code, 14-bit width and height
        var lossy = new Bytes().ascii("VP8 ").le32(10).raw(0x30, 0x01, 0x00, 0x9d, 0x01, 0x2a).le16(1000).le16(1500);
        // lossless: signature, then width - 1 and height - 1 in 14 bits each, alpha and version above them
        var lossless = new Bytes().ascii("VP8L").le32(5).raw(0x2f).le32((1000 - 1) | (1500 - 1) << 14 | 1 << 28);
        // extended: flags, reserved, then the canvas minus one in 24 bits each
        var extended = new Bytes().ascii("VP8X").le32(10).raw(0x10, 0, 0, 0).le24(20000 - 1).le24(1500 - 1);

        // THEN
        assertThat(ImageSize.of(webp(lossy))).contains(new ImageSize(1000, 1500));
        assertThat(ImageSize.of(webp(lossless))).contains(new ImageSize(1000, 1500));
        assertThat(ImageSize.of(webp(extended))).contains(new ImageSize(20000, 1500));
    }

    /** Several images in one file: the largest is the picture, a smaller one its thumbnail. */
    @Test
    void shouldReadTheLargestImageOfAnAvif()
    {
        // GIVEN
        var avif = new Bytes()
                .box("ftyp", new Bytes().ascii("avif").be32(0).ascii("avifmif1"))
                .box("meta", new Bytes().be32(0)
                        .box("hdlr", new Bytes().be32(0).be32(0).ascii("pict").raw(new byte[13]))
                        .box("iprp", new Bytes()
                                .box("ipco", new Bytes()
                                        .box("ispe", new Bytes().be32(0).be32(160).be32(90))
                                        .box("ispe", new Bytes().be32(0).be32(1920).be32(1080)))));

        // THEN
        assertThat(ImageSize.of(avif.bytes())).contains(new ImageSize(1920, 1080));
    }

    @Test
    void shouldSayNothingAboutWhatItCannotRead()
    {
        // GIVEN a PNG cut off in the middle of its width
        byte[] cutShort = Arrays.copyOf(TestImages.png(37, 21), 18);

        // THEN - likewise for no bytes, text, a JPEG with no frame header, an MP4-like file
        assertThat(ImageSize.of(new byte[0])).isEmpty();
        assertThat(ImageSize.of("not an image".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageSize.of(cutShort)).isEmpty();
        assertThat(ImageSize.of(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xda})).isEmpty();
        assertThat(ImageSize.of(new Bytes().box("ftyp", new Bytes().ascii("isom")).bytes())).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------

    private static byte[] written(String format) throws IOException
    {
        var image = new BufferedImage(37, 21, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }

    private static byte[] webp(Bytes firstChunk)
    {
        byte[] chunk = firstChunk.bytes();
        return new Bytes().ascii("RIFF").le32(4 + chunk.length).ascii("WEBP").raw(chunk).bytes();
    }

    private static void writeIntLe(byte[] b, int at, int value)
    {
        for (int i = 0; i < 4; i++)
        {
            b[at + i] = (byte) (value >> 8 * i);
        }
    }

    /** Just enough of a byte writer to lay out a header from its specification. */
    private static final class Bytes
    {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Bytes ascii(String text)
        {
            out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
            return this;
        }

        Bytes raw(int... values)
        {
            for (int value : values)
            {
                out.write(value);
            }
            return this;
        }

        Bytes raw(byte[] values)
        {
            out.writeBytes(values);
            return this;
        }

        Bytes le16(int value)
        {
            return raw(value & 0xff, value >> 8 & 0xff);
        }

        Bytes le24(int value)
        {
            return raw(value & 0xff, value >> 8 & 0xff, value >> 16 & 0xff);
        }

        Bytes le32(int value)
        {
            return raw(value & 0xff, value >> 8 & 0xff, value >> 16 & 0xff, value >>> 24);
        }

        Bytes be32(int value)
        {
            return raw(value >>> 24, value >> 16 & 0xff, value >> 8 & 0xff, value & 0xff);
        }

        /** An ISOBMFF box: its size, its type, its content. */
        Bytes box(String type, Bytes content)
        {
            byte[] body = content.bytes();
            return be32(8 + body.length).ascii(type).raw(body);
        }

        byte[] bytes()
        {
            return out.toByteArray();
        }
    }
}
