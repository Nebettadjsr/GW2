package api;

public final class HttpStatus {
    private HttpStatus() {}

    public static boolean isOk(int code) {
        return code >= 200 && code < 300;
    }

    public static boolean isOkOrPartial(int code) {
        return code == 200 || code == 206;
    }

    public static void requireOkOrPartial(int code, String context, String body) {
        if (!isOkOrPartial(code)) {
            logError(code, context, body);
            throw new RuntimeException(context + " failed: HTTP " + code);
        }
    }

    public static void requireOk(int code, String context, String body) {
        if (!isOk(code)) {
            logError(code, context, body);
            throw new RuntimeException(context + " failed: HTTP " + code);
        }
    }

    // ---------- logging helpers ----------

    public static void logWarn(int code, String context) {
        System.out.println("⚠ HTTP " + code + " " + context);
    }

    public static void logError(int code, String context, String body) {
        System.out.println("❌ HTTP " + code + " " + context);
        if (body != null && body.length() > 0) {
            System.out.println("Body: " + body.substring(0, Math.min(200, body.length())));
        }
    }
}