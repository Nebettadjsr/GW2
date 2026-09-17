package util;

public final class CoinUtils {
    private CoinUtils() {}

    // already have: format(int copper)

    /** Accepts inputs like: "12g 34s 56c", "12g34s", "5000", "10s", "7c" */
    public static int parseToCopper(String input) {
        if (input == null) return 0;
        String s = input.trim().toLowerCase();
        if (s.isEmpty()) return 0;

        // If user just types a number => treat it as copper
        if (s.matches("-?\\d+")) {
            return Integer.parseInt(s);
        }

        int sign = 1;
        if (s.startsWith("-")) { sign = -1; s = s.substring(1).trim(); }

        int g = extractUnit(s, 'g');
        int si = extractUnit(s, 's');
        int c = extractUnit(s, 'c');

        return sign * (g * 10000 + si * 100 + c);
    }

    private static int extractUnit(String s, char unit) {
        int idx = s.indexOf(unit);
        if (idx < 0) return 0;

        // walk backwards to capture the number immediately before the unit
        int end = idx;
        int start = end - 1;
        while (start >= 0 && Character.isWhitespace(s.charAt(start))) start--;
        int numEnd = start + 1;
        while (start >= 0 && Character.isDigit(s.charAt(start))) start--;
        int numStart = start + 1;

        if (numStart >= numEnd) return 0; // no digits found
        return Integer.parseInt(s.substring(numStart, numEnd));
    }

    public static String format(int copper) {
        int abs = Math.abs(copper);
        int g = abs / 10000;
        int s = (abs % 10000) / 100;
        int c = abs % 100;
        return (copper < 0 ? "-" : "") + g + "g " + s + "s " + c + "c";
    }

    public static String formatSigned(int copper) {
        if (copper == 0) return "0g 0s 0c";
        return  format(Math.abs(copper));
    }
}