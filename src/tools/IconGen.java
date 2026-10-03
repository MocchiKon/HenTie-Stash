import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the exe's icon from the logo at package time, so the icon cannot drift from the logo and no
 * binary copy of it lives in the repository. Run by the windows-exe profile as a single-file program
 * ({@code java IconGen.java <logo.svg> <out.ico>}), so it needs no compile step and stays out of the app.
 *
 * <p>Small sizes zoom in on the head: the whole rooster at 16 px is a few pixels of mush. The frames are
 * squares anchored at the drawing's top-left corner, where the head is, sized as a share of its height,
 * so they follow the logo as long as it faces left with the head at the top.
 *
 * <p>Each size is rendered at about 2048 px and averaged down in premultiplied alpha. Rendering at the
 * final size would anti-alias each stacked shape on its own, and where shapes meet, the background would
 * show through as faint seams; averaging unpremultiplied colours would darken the transparent edges.
 */
public class IconGen {

    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 96, 128, 256};
    /** Comb, eye, beak and the top of the wattle. */
    private static final float HEAD = 0.505f;
    /** The head with the whole wattle. */
    private static final float HEAD_AND_WATTLE = 0.63f;

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: IconGen <logo.svg> <out.ico>");
        }
        Path svg = Path.of(args[0]);
        Path out = Path.of(args[1]);
        SVGDocument doc = new SVGLoader().load(svg.toUri().toURL());
        if (doc == null) {
            throw new IOException("Cannot read " + svg);
        }
        ViewBox vb = doc.viewBox();
        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) {
            float side = size < 32 ? HEAD * vb.height : size < 96 ? HEAD_AND_WATTLE * vb.height : vb.height;
            // the whole drawing is centred; a close-up keeps the head's top-left corner
            float x = size < 96 ? vb.x : vb.x + (vb.width - side) / 2;
            pngs.add(png(render(doc, vb, x, vb.y, side, size)));
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (OutputStream os = Files.newOutputStream(out)) {
            writeIco(os, pngs);
        }
        System.out.println("IconGen: " + out + " (" + SIZES.length + " sizes from " + svg.getFileName() + ")");
    }

    /** The square (x, y, side) of the drawing, in its own units, as a size x size image. */
    private static BufferedImage render(SVGDocument doc, ViewBox vb, float x, float y, float side, int size) {
        int ss = Math.max(8, 2048 / size);
        int big = size * ss;
        BufferedImage hi = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = hi.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            float scale = big / side;
            g.scale(scale, scale);
            g.translate(vb.x - x, vb.y - y);
            // one drawing unit per graphics unit, with the view box's origin at (0, 0)
            doc.render(null, g, new ViewBox(0, 0, vb.width, vb.height));
        } finally {
            g.dispose();
        }
        int[] src = ((DataBufferInt) hi.getRaster().getDataBuffer()).getData();
        BufferedImage lo = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int n = ss * ss;
        for (int oy = 0; oy < size; oy++) {
            for (int ox = 0; ox < size; ox++) {
                long a = 0, r = 0, gr = 0, b = 0;
                for (int dy = 0; dy < ss; dy++) {
                    int row = (oy * ss + dy) * big + ox * ss;
                    for (int dx = 0; dx < ss; dx++) {
                        int p = src[row + dx];
                        a += p >>> 24;
                        r += (p >> 16) & 0xFF;
                        gr += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                    }
                }
                int argb = 0;
                if (a > 0) {
                    int alpha = (int) ((a + n / 2) / n);
                    argb = alpha << 24 | unpremultiply(r, a) << 16 | unpremultiply(gr, a) << 8 | unpremultiply(b, a);
                }
                lo.setRGB(ox, oy, argb);
            }
        }
        return lo;
    }

    private static int unpremultiply(long channelSum, long alphaSum) {
        return (int) Math.min(255, (channelSum * 255 + alphaSum / 2) / alphaSum);
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", bytes)) {
            throw new IOException("No PNG writer");
        }
        return bytes.toByteArray();
    }

    /** Every image stored as PNG: Windows reads PNG entries at any size, and Launch4j embeds them as they are. */
    private static void writeIco(OutputStream os, List<byte[]> pngs) throws IOException {
        int count = pngs.size();
        ByteBuffer head = ByteBuffer.allocate(6 + 16 * count).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) count);
        int offset = 6 + 16 * count;
        for (int i = 0; i < count; i++) {
            int size = SIZES[i];
            head.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size)); // 0 means 256
            head.put((byte) 0).put((byte) 0);                  // no palette, reserved
            head.putShort((short) 1).putShort((short) 32);     // planes, bits per pixel
            head.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        os.write(head.array());
        for (byte[] png : pngs) {
            os.write(png);
        }
    }
}
