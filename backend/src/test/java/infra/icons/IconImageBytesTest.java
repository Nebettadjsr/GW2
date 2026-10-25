package infra.icons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What may become a cache entry (TARGET_ARCHITECTURE.md §12.1: a complete, nonempty, decodable
 * PNG/JPEG matching the extension, within the decoded-pixel bound). These are the checks that keep an
 * error page, a truncated download or a format substitution out of the persistent cache.
 */
class IconImageBytesTest {

    @Test
    void acceptsACompletePngAndJpgUnderTheirOwnExtension() {
        assertTrue(IconImageBytes.validate(TestImages.png(), "png").valid());
        assertTrue(IconImageBytes.validate(TestImages.jpg(), "jpg").valid());
    }

    @Test
    void refusesAnEmptyBody() {
        IconImageBytes.Validation validation = IconImageBytes.validate(new byte[0], "png");

        assertFalse(validation.valid());
        assertNotNull(validation.reason(), "a refusal must say why, so the failure is diagnosable");
    }

    @Test
    void refusesAnHtmlOrTextBodyDeliveredWithAnImageExtension() {
        assertFalse(IconImageBytes.validate(TestImages.htmlErrorBody(), "png").valid());
        assertFalse(IconImageBytes.validate(TestImages.htmlErrorBody(), "jpg").valid());
    }

    @Test
    void refusesATruncatedImage() {
        assertFalse(IconImageBytes.validate(TestImages.truncatedPng(), "png").valid(),
                "an interrupted download must never be published as an entry");
    }

    @Test
    void refusesAnImageWhoseFormatDoesNotMatchTheExtension() {
        assertFalse(IconImageBytes.validate(TestImages.png(), "jpg").valid());
        assertFalse(IconImageBytes.validate(TestImages.jpg(), "png").valid());
    }

    @Test
    void refusesAnImageBeyondTheDecodedPixelBound() {
        assertFalse(IconImageBytes.validate(TestImages.oversizedPixelsPng(), "png").valid());
    }

    @Test
    void signatureCheckAgreesWithTheFormatWithoutDecoding() {
        assertTrue(IconImageBytes.hasSignatureOf(TestImages.png(), "png"));
        assertTrue(IconImageBytes.hasSignatureOf(TestImages.jpg(), "jpg"));
        assertFalse(IconImageBytes.hasSignatureOf(TestImages.png(), "jpg"));
        assertFalse(IconImageBytes.hasSignatureOf(TestImages.htmlErrorBody(), "png"));
        assertFalse(IconImageBytes.hasSignatureOf(new byte[]{1, 2}, "png"), "fewer bytes than a signature");
    }
}
