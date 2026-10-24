package com.cyanite.client.modules.donut;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Rewriting a sidebar line while keeping the colours and icon the server sent. */
final class SidebarText {
    private SidebarText() {
    }

    /**
     * Rebuilds {@code original} with {@code custom} in place of its value, keeping the leading
     * label or icon and every colour. {@code wholeLine} replaces the lot instead, wearing the
     * colour the line started with.
     */
    static Component rewrite(Component original, String custom, boolean wholeLine) {
        List<Style> styles = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        original.visit((style, text) -> {
            styles.add(style);
            parts.add(text);
            return Optional.empty();
        }, Style.EMPTY);

        StringBuilder full = new StringBuilder();
        for (String part : parts) full.append(part);
        int cut = wholeLine ? 0 : valueStart(full.toString());

        MutableComponent out = Component.empty();
        Style valueStyle = null;
        int consumed = 0;

        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            int start = consumed;
            consumed += part.length();

            if (consumed <= cut) {
                out.append(Component.literal(part).setStyle(styles.get(i)));
                continue;
            }
            // This part holds the value, so its colour is the one the replacement should wear.
            if (valueStyle == null) valueStyle = styles.get(i);
            if (start < cut) out.append(Component.literal(part.substring(0, cut - start)).setStyle(styles.get(i)));
        }

        if (valueStyle == null) valueStyle = styles.isEmpty() ? Style.EMPTY : styles.getLast();
        return out.append(Component.literal(custom).setStyle(valueStyle));
    }

    /**
     * Where the value starts. The value is the run of words at the end that contain a digit, so
     * "$ 252M" keeps "$ " and "Playtime: 33d 9h" keeps "Playtime: ". A line with no number at
     * all falls back to its last word.
     */
    static int valueStart(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (end == 0) return 0;

        int value = end;
        int cursor = end;
        while (cursor > 0) {
            int wordEnd = cursor;
            while (wordEnd > 0 && Character.isWhitespace(text.charAt(wordEnd - 1))) wordEnd--;
            if (wordEnd == 0) break;

            int wordStart = wordEnd;
            while (wordStart > 0 && !Character.isWhitespace(text.charAt(wordStart - 1))) wordStart--;
            if (!hasDigit(text, wordStart, wordEnd)) break;

            value = wordStart;
            cursor = wordStart;
        }

        if (value == end) {
            // Nothing numeric at the end, so treat the last word as the value.
            while (value > 0 && !Character.isWhitespace(text.charAt(value - 1))) value--;
        }
        return value;
    }

    private static boolean hasDigit(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.isDigit(text.charAt(i))) return true;
        }
        return false;
    }
}
