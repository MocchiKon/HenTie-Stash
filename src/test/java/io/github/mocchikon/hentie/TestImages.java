package io.github.mocchikon.hentie;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Real image bytes for the Image Compression suites. The tools decode their input, so a text file named
 * {@code 1.webp} would only test the failure path; {@code ImageIO} builds them, so no binary lives in the repo.
 *
 * <p>The seeded noise keeps PNG from compressing the image away, so a lossy re-encode has something real to
 * save and the size assertions are not measuring a rounding error.
 */
public final class TestImages
{
    private TestImages()
    {
    }

    public static byte[] png(int width, int height)
    {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var random = new Random(42);
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int base = (x * 255 / width + y * 255 / height) / 2;
                image.setRGB(x, y, rgb(base, random));
            }
        }
        var out = new ByteArrayOutputStream();
        try
        {
            ImageIO.write(image, "png", out);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Could not build the test PNG", e);
        }
        return out.toByteArray();
    }

    public static Path writePng(Path file, int width, int height) throws IOException
    {
        Files.createDirectories(file.getParent());
        Files.write(file, png(width, height));
        return file;
    }

    private static int rgb(int base, Random random)
    {
        int r = clamp(base + random.nextInt(64) - 32);
        int g = clamp(base + random.nextInt(64) - 32);
        int b = clamp(255 - base + random.nextInt(64) - 32);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value)
    {
        return Math.clamp(value, 0, 255);
    }
}
