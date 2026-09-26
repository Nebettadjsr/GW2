package infra.icons;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * Decides whether a byte array really is the complete image its extension claims
 * (TARGET_ARCHITECTURE.md §12.1: "a complete, nonempty, decodable PNG/JPEG matching the extension").
 * An HTML error body, a truncated download and a PNG delivered under a {@code .jpg} key all fail here,
 * which is what keeps them out of the cache.
 *
 * <p>Two levels, used at different moments:
 * <ul>
 *   <li>{@link #validate} fully decodes and bounds the pixel count. Used before publication, once per
 *       acquired image.</li>
 *   <li>{@link #hasSignatureOf} checks the file signature only. Used on every disk read, so a served
 *       {@code Content-Type} is verified against the bytes without decoding an image per request.</li>
 * </ul>
 */
public final class IconImageBytes {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private IconImageBytes() {}

    /**
     * The outcome of validating bytes against an extension.
     *
     * @param reason why the bytes were refused; null when {@code valid}
     */
    public record Validation(boolean valid, String reason) {

        static Validation accepted() {
            return new Validation(true, null);
        }

        static Validation refused(String reason) {
            return new Validation(false, reason);
        }
    }

    /** Whether these bytes decode completely as the image format {@code extension} names. */
    public static Validation validate(byte[] bytes, String extension) {
        if (bytes == null || bytes.length == 0) return Validation.refused("empty response body");
        if (!hasSignatureOf(bytes, extension)) {
            return Validation.refused("bytes are not a " + extension + " image");
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return Validation.refused("image bytes could not be opened");

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return Validation.refused("no decoder accepted the bytes");

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);

                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!formatMatches(format, extension)) {
                    return Validation.refused("decoded format " + format + " does not match ." + extension);
                }

                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > IconCacheBounds.MAX_DECODED_PIXELS) {
                    return Validation.refused("decoded pixels " + pixels + " exceed the accepted maximum");
                }

                // Reading it is the only way to know the bytes are complete rather than merely well-headed.
                BufferedImage decoded = reader.read(0);
                if (decoded == null) return Validation.refused("decoder produced no image");

                return Validation.accepted();
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException undecodable) {
            // ImageIO throws unchecked exceptions on some malformed inputs as well as IIOException.
            return Validation.refused("image is truncated or corrupt: " + undecodable.getClass().getSimpleName());
        }
    }

    /** Whether the bytes start with the file signature {@code extension} implies. */
    public static boolean hasSignatureOf(byte[] bytes, String extension) {
        if (bytes == null) return false;
        if (IconSourcePolicy.EXTENSION_PNG.equals(extension)) return startsWith(bytes, PNG_SIGNATURE);
        if (IconSourcePolicy.EXTENSION_JPG.equals(extension)) return startsWith(bytes, JPEG_SIGNATURE);
        return false;
    }

    private static boolean formatMatches(String format, String extension) {
        if (IconSourcePolicy.EXTENSION_PNG.equals(extension)) return "png".equals(format);
        return "jpeg".equals(format) || "jpg".equals(format);
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) return false;
        }
        return true;
    }
}
