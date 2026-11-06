package moe.shizuku.common.util;

/**
 * Sanitizes untrusted application labels before rendering them in security-critical
 * permission dialogs, ported from Nightzuku.
 */
public final class LabelSanitizer {

    public static final int DEFAULT_MAX_CODE_POINTS = 200;

    private static final String LINE_BREAK_CHARS = "\n\r\u000B\u000C\u0085\u2028\u2029";
    private static final String BIDI_CONTROL_CHARS = "\u200B\u200E\u200F\u061C\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\uFEFF";

    private LabelSanitizer() {
    }

    public static String sanitize(CharSequence rawLabel, String fallback) {
        return sanitize(rawLabel, fallback, DEFAULT_MAX_CODE_POINTS);
    }

    public static String sanitize(CharSequence rawLabel, String fallback, int maxCodePoints) {
        if (rawLabel == null || rawLabel.length() == 0) {
            return fallback;
        }
        int limit = Math.max(1, maxCodePoints);
        StringBuilder sb = new StringBuilder(Math.min(rawLabel.length(), limit + 16));

        for (int i = 0; i < rawLabel.length(); i++) {
            char ch = rawLabel.charAt(i);
            if (LINE_BREAK_CHARS.indexOf(ch) >= 0) {
                break;
            }
            if (Character.isISOControl(ch) || BIDI_CONTROL_CHARS.indexOf(ch) >= 0) {
                continue;
            }
            sb.append(ch);
        }

        String singleLine = sb.toString().replaceAll(" +", " ").trim();
        if (singleLine.isEmpty()) {
            return fallback;
        }
        if (singleLine.codePointCount(0, singleLine.length()) <= limit) {
            return singleLine;
        }

        int end = singleLine.offsetByCodePoints(0, limit);
        String truncated = singleLine.substring(0, end).trim();
        return truncated.isEmpty() ? fallback : truncated;
    }
}
