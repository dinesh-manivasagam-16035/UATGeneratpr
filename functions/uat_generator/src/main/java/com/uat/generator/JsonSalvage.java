package com.uat.generator;

import java.util.ArrayList;
import java.util.List;

/**
 * Recovers the complete objects from a JSON array that may be cut off.
 *
 * When an LLM response hits its output-token limit the JSON ends mid-object,
 * so the whole response fails to parse and every case is lost. This scanner
 * walks the "cases" array (or the first array in the text), tracking strings
 * and nesting, and returns the source text of each object that was closed.
 * The last, unfinished object is dropped.
 *
 * Pure Java, no dependencies.
 */
public final class JsonSalvage {

    private JsonSalvage() {}

    public static List<String> completeObjects(String text) {
        if (text == null) return new ArrayList<>();
        return scan(text, arrayStart(text));
    }

    /**
     * Complete objects of the array under {@code "key"} only, or an empty list when
     * the key is absent. For responses with several arrays (implementation specs).
     */
    public static List<String> completeObjects(String text, String key) {
        if (text == null) return new ArrayList<>();
        return scan(text, keyedArrayStart(text, key));
    }

    private static List<String> scan(String text, int start) {
        List<String> out = new ArrayList<>();
        if (start < 0) return out;

        boolean inString = false;
        boolean escape = false;
        int depth = 0;          // nesting depth inside the outer array
        int objStart = -1;

        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                if (depth == 0 && c == '{') objStart = i;
                depth++;
            } else if (c == '}' || c == ']') {
                if (depth == 0) break;          // end of the outer array
                depth--;
                if (depth == 0 && c == '}' && objStart >= 0) {
                    out.add(text.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return out;
    }

    /** Index of the '[' that opens the cases array, or -1. */
    private static int arrayStart(String text) {
        int i = keyedArrayStart(text, "cases");
        if (i < 0) i = keyedArrayStart(text, "test_cases");
        return i >= 0 ? i : text.indexOf('[');
    }

    /** Index of the '[' right after {@code "key":}, or -1. */
    private static int keyedArrayStart(String text, String key) {
        int at = text.indexOf("\"" + key + "\"");
        if (at < 0) return -1;
        int colon = text.indexOf(':', at);
        if (colon < 0) return -1;
        for (int i = colon + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') return i;
            if (!Character.isWhitespace(c)) break;
        }
        return -1;
    }
}
