package infra.icons;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Real encoded images for the icon-cache tests, so validation, storage and delivery are exercised with
 * bytes a decoder actually accepts rather than with placeholders (TEST_STRATEGY.md §31.2). Nothing here
 * touches the network or the GW2 API.
 */
final class TestImages {

    private TestImages() {}

    static byte[] png() {
        return encode(image(8, 8, Color.RED), "png");
    }

    /** A second, visibly different PNG, for tests that must tell two committed images apart. */
    static byte[] otherPng() {
        return encode(image(8, 8, Color.BLUE), "png");
    }

    static byte[] jpg() {
        return encode(image(8, 8, Color.GREEN), "jpg");
    }

    /** Larger than the accepted decoded-pixel bound. */
    static byte[] oversizedPixelsPng() {
        int side = (int) Math.sqrt(IconCacheBounds.MAX_DECODED_PIXELS) + 100;
        return encode(image(side, side, Color.GRAY), "png");
    }

    static byte[] htmlErrorBody() {
        return "<html><body>404 Not Found</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A valid PNG header followed by nothing usable, which is what an interrupted download looks like. */
    static byte[] truncatedPng() {
        byte[] complete = png();
        byte[] truncated = new byte[complete.length / 2];
        System.arraycopy(complete, 0, truncated, 0, truncated.length);
        return truncated;
    }

    private static BufferedImage image(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, bytes)) {
                throw new IllegalStateException("no encoder for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
