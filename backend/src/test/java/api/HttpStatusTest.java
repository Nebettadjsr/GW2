package api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HttpStatusTest {

    @Test
    void generalSuccessAccepts2xxAndRejectsOthers() {
        assertTrue(HttpStatus.isOk(200));
        assertTrue(HttpStatus.isOk(204));
        assertTrue(HttpStatus.isOk(206));
        assertTrue(HttpStatus.isOk(299));
        assertFalse(HttpStatus.isOk(199));
        assertFalse(HttpStatus.isOk(300));
        assertFalse(HttpStatus.isOk(404));
        assertFalse(HttpStatus.isOk(429));
    }

    @Test
    void jsonPolicyAccepts200And206Only() {
        assertTrue(HttpStatus.isOkOrPartial(200));
        assertTrue(HttpStatus.isOkOrPartial(206));
        assertFalse(HttpStatus.isOkOrPartial(204));
        assertFalse(HttpStatus.isOkOrPartial(404));
        assertFalse(HttpStatus.isOkOrPartial(429));
    }

    @Test
    void requireOkAcceptsNoContentButRejectsClientErrorsWithContext() {
        assertDoesNotThrow(() -> HttpStatus.requireOk(204, "items", null));

        RuntimeException notFound =
                assertThrows(RuntimeException.class, () -> HttpStatus.requireOk(404, "items", "missing"));
        assertEquals("items failed: HTTP 404", notFound.getMessage());

        RuntimeException limited =
                assertThrows(RuntimeException.class, () -> HttpStatus.requireOk(429, "prices", ""));
        assertEquals("prices failed: HTTP 429", limited.getMessage());
    }

    @Test
    void requireOkOrPartialAcceptsPartialButRejectsNoContentWithContext() {
        assertDoesNotThrow(() -> HttpStatus.requireOkOrPartial(200, "items", "[]"));
        assertDoesNotThrow(() -> HttpStatus.requireOkOrPartial(206, "items", "[]"));

        RuntimeException noContent =
                assertThrows(RuntimeException.class, () -> HttpStatus.requireOkOrPartial(204, "items", null));
        assertEquals("items failed: HTTP 204", noContent.getMessage());

        RuntimeException limited = assertThrows(RuntimeException.class,
                () -> HttpStatus.requireOkOrPartial(429, "prices", "x".repeat(500)));
        assertEquals("prices failed: HTTP 429", limited.getMessage());
    }
}
