package com.uat.generator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a requirement list out of a chunked BRD, so every generated case can
 * be traced back to a requirement and coverage gaps become visible.
 *
 * Two modes:
 *   - Explicit IDs: if the BRD labels requirements (FR-1, BR-02, REQ-3, UC-4,
 *     US-5, AC-6, NFR-7 ...) at the start of a line or heading, those are used
 *     as they are.
 *   - Derived IDs: otherwise each substantive section heading becomes REQ-01,
 *     REQ-02 ..., skipping boilerplate sections (Background, Goals, Scope ...).
 *
 * Pure Java, no dependencies.
 */
public final class Requirements {

    private static final Pattern EXPLICIT = Pattern.compile(
            "^\\s*(?:#{1,6}\\s*|[-*•]\\s+|\\*\\*|\\|\\s*)?"
          + "((?:FR|BR|NFR|REQ|UC|US|AC)[-_ ]?\\d+(?:\\.\\d+)*)"
          + "\\b(?:\\*\\*)?\\s*[:.\\-–—)|]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** Any requirement id inside free text, e.g. a spec_ref like "FR-2 Auto-assignment". */
    private static final Pattern ID_IN_TEXT = Pattern.compile(
            "\\b((?:FR|BR|NFR|REQ|UC|US|AC)[-_ ]?\\d+(?:\\.\\d+)*)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ID_PARTS = Pattern.compile("^([A-Za-z]+)[-_ ]?(\\d+(?:\\.\\d+)*)$");

    private static final Pattern BOILERPLATE = Pattern.compile(
            "^(?:.*\\bbrd\\b.*|.*\\brequirements? document\\b.*|business requirements?( document)?|background|overview|introduction|purpose"
          + "|goals?|objectives?|summary|executive summary|scope|in scope|out of scope|glossary"
          + "|definitions|terminology|assumptions|constraints|dependencies|risks|table of contents"
          + "|contents|revision history|document (?:history|control|information)|references"
          + "|appendix.*|approvals?|sign[- ]?off|stakeholders|contacts|open questions"
          + "|functional requirements|requirements)$",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_REQUIREMENTS = 80;
    private static final int MAX_TITLE = 100;

    private Requirements() {}

    public static final class Requirement {
        public final String id;
        public final String title;
        public final int part;          // 1-based chunk number where it first appears
        public final boolean derived;   // true when the id was generated (REQ-nn)

        Requirement(String id, String title, int part, boolean derived) {
            this.id = id;
            this.title = title;
            this.part = part;
            this.derived = derived;
        }
    }

    public static List<Requirement> extract(List<BrdChunker.Chunk> chunks) {
        Map<String, Requirement> explicit = new LinkedHashMap<>();
        for (BrdChunker.Chunk c : chunks) {
            for (String line : c.text.split("\n")) {
                Matcher m = EXPLICIT.matcher(line);
                if (!m.matches()) continue;
                String id = normalizeId(m.group(1));
                if (id.isEmpty() || explicit.containsKey(id)) continue;
                explicit.put(id, new Requirement(id, cleanTitle(m.group(2)), c.index + 1, false));
                if (explicit.size() >= MAX_REQUIREMENTS) break;
            }
        }
        if (!explicit.isEmpty()) return new ArrayList<>(explicit.values());

        List<Requirement> derived = new ArrayList<>();
        int n = 1;
        int totalHeadings = 0;
        for (BrdChunker.Chunk c : chunks) totalHeadings += c.headings.size();
        boolean first = true;
        for (BrdChunker.Chunk c : chunks) {
            for (String h : c.headings) {
                // The opening heading of a multi-section document is its title, not a requirement.
                boolean isDocTitle = first && totalHeadings > 1 && c.index == 0
                        && c.text.trim().startsWith("#") && !c.text.trim().startsWith("##");
                first = false;
                if (isDocTitle) continue;
                String title = cleanTitle(h.replace(" (cont.)", ""));
                if (!isSubstantive(title)) continue;
                boolean dup = false;
                for (Requirement r : derived) if (r.title.equalsIgnoreCase(title)) { dup = true; break; }
                if (dup) continue;
                derived.add(new Requirement(String.format("REQ-%02d", n++), title, c.index + 1, true));
                if (derived.size() >= MAX_REQUIREMENTS) return derived;
            }
        }
        return derived;
    }

    /** "fr_1" / "FR 1" / "fr1" → "FR-1". Returns "" for anything that isn't an id. */
    public static String normalizeId(String raw) {
        if (raw == null) return "";
        Matcher m = ID_PARTS.matcher(raw.trim());
        if (!m.matches()) return "";
        return m.group(1).toUpperCase(Locale.ROOT) + "-" + m.group(2);
    }

    /** Requirement ids mentioned anywhere in a piece of text, normalized. */
    public static List<String> idsIn(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = ID_IN_TEXT.matcher(text);
        while (m.find()) {
            String id = normalizeId(m.group(1));
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /**
     * Best-effort mapping when a case has no usable req_ids: an id mentioned in
     * its spec_ref, or a requirement whose title matches the spec_ref.
     */
    public static List<String> infer(String specRef, Collection<Requirement> reqs) {
        List<String> out = new ArrayList<>();
        if (specRef == null || specRef.trim().isEmpty()) return out;
        for (String id : idsIn(specRef)) {
            for (Requirement r : reqs) if (r.id.equals(id) && !out.contains(id)) out.add(id);
        }
        if (!out.isEmpty()) return out;
        String ref = specRef.trim().toLowerCase(Locale.ROOT);
        for (Requirement r : reqs) {
            String t = r.title.toLowerCase(Locale.ROOT);
            if (t.length() < 4) continue;
            if (ref.equals(t) || ref.contains(t) || (ref.length() >= 6 && t.contains(ref))) {
                out.add(r.id);
            }
        }
        return out;
    }

    /** Compact "ID — title" lines for the requirements in one part, for the prompt. */
    public static String promptList(Collection<Requirement> reqs, int part, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (Requirement r : reqs) {
            if (r.part != part) continue;
            String line = "- " + r.id + (r.title.isEmpty() ? "" : " — " + r.title) + "\n";
            if (sb.length() + line.length() > maxChars) break;
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private static boolean isSubstantive(String title) {
        if (title.isEmpty()) return false;
        if (BOILERPLATE.matcher(title).matches()) return false;
        if (title.length() > 70) return false;                 // a sentence, not a heading
        if (title.matches("^\\d+(\\.\\d+)*[.)]?\\s.*\\.$")) return false;   // numbered sentence
        return true;
    }

    private static String cleanTitle(String raw) {
        if (raw == null) return "";
        String t = raw.trim()
                .replaceAll("\\*\\*", "")
                .replaceAll("^[:.\\-–—)|\\s]+", "")
                .replaceAll("[|:\\s]+$", "")
                .trim();
        return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE) + "…" : t;
    }
}
