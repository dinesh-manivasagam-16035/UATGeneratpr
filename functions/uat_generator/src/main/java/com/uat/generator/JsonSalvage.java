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
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int start = arrayStart(text);
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
        int key = text.indexOf("\"cases\"");
        if (key < 0) key = text.indexOf("\"test_cases\"");
        if (key >= 0) {
            int colon = text.indexOf(':', key);
            if (colon >= 0) {
                for (int i = colon + 1; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '[') return i;
                    if (!Character.isWhitespace(c)) break;
                }
            }
        }
        return text.indexOf('[');
    }
}
