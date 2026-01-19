package com.divinebanitem;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern PATTERN = Pattern.compile("(\\d+)([smhdw])");

    private DurationParser() {
    }

    public static long parseToMillis(String input) {
        if (input == null || input.isBlank()) {
            return 0L;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = PATTERN.matcher(normalized);
        long totalSeconds = 0;
        int matched = 0;
        while (matcher.find()) {
            matched++;
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            switch (unit) {
                case "s":
                    totalSeconds += value;
                    break;
                case "m":
                    totalSeconds += value * 60;
                    break;
                case "h":
                    totalSeconds += value * 3600;
                    break;
                case "d":
                    totalSeconds += value * 86400;
                    break;
                case "w":
                    totalSeconds += value * 604800;
                    break;
                default:
                    break;
            }
        }
        if (matched == 0) {
            return 0L;
        }
        return Duration.ofSeconds(totalSeconds).toMillis();
    }
}
