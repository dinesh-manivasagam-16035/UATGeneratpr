package com.uat.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a BRD into section-aligned chunks small enough for one LLM request.
 *
 * Replaces the old behaviour of silently cutting the BRD at 12,000 characters.
 * Chunks never split a section unless that section alone is larger than the
 * chunk limit (then it is split on paragraph boundaries). If the BRD needs more
 * chunks than allowed, the extra sections are reported in droppedHeadings so
 * the caller can warn the user instead of losing requirements silently.
 *
 * Pure Java, no dependencies.
 */
public final class BrdChunker {

    /** A heading line: markdown, numbered section, requirement id, or a short ALL-CAPS line. */
    private static final Pattern HEADING = Pattern.compile(
            "^(?:#{1,6}\\s+\\S.*"
          + "|\\d+(?:\\.\\d+){0,3}[.)]?\\s+\\S.{0,80}"
          + "|(?:FR|BR|NFR|REQ|UC|US|AC)[-_ ]?\\d+\\b.{0,100}"
          + "|[A-Z][A-Z0-9 &/,'()\\-]{3,80}:?)$");

    private static final int MAX_OUTLINE_CHARS = 2000;

    private BrdChunker() {}

    public static final class Chunk {
        public final int index;          // 0-based
        public final String text;
        public final List<String> headings;

        Chunk(int index, String text, List<String> headings) {
            this.index = index;
            this.text = text;
            this.headings = Collections.unmodifiableList(headings);
        }

        /** First heading of the chunk, or "Part N" when the chunk has none. */
        public String label() {
            return headings.isEmpty() ? "Part " + (index + 1) : headings.get(0);
        }
    }

    public static final class Result {
        public final List<Chunk> chunks;
        public final List<String> droppedHeadings;
        public final String outline;

        Result(List<Chunk> chunks, List<String> droppedHeadings, String outline) {
            this.chunks = Collections.unmodifiableList(chunks);
            this.droppedHeadings = Collections.unmodifiableList(droppedHeadings);
            this.outline = outline;
        }

        public boolean truncated() { return !droppedHeadings.isEmpty(); }
    }

    private static final class Piece {
        final String heading;   // may be null
        final String text;
        Piece(String heading, String text) { this.heading = heading; this.text = text; }
    }

    public static boolean isHeading(String line) {
        String t = line.trim();
        return !t.isEmpty() && t.length() <= 120 && HEADING.matcher(t).matches();
    }

    public static Result chunk(String brd, int maxChars, int maxChunks) {
        if (maxChars < 500) maxChars = 500;
        if (maxChunks < 1) maxChunks = 1;
        String text = brd == null ? "" : brd.replace("\r\n", "\n").trim();

        // 1. Split into sections at heading lines.
        List<String> sectionHeadings = new ArrayList<>();
        List<String> sectionTexts = new ArrayList<>();
        String currentHeading = null;
        StringBuilder current = new StringBuilder();
        boolean currentHasContent = false;
        for (String line : text.split("\n", -1)) {
            if (isHeading(line) && currentHasContent) {
                sectionHeadings.add(currentHeading);
                sectionTexts.add(current.toString().trim());
                current.setLength(0);
                currentHasContent = false;
                currentHeading = null;
            }
            if (isHeading(line) && currentHeading == null) currentHeading = cleanHeading(line);
            current.append(line).append('\n');
            if (!line.trim().isEmpty()) currentHasContent = true;
        }
        if (currentHasContent) {
            sectionHeadings.add(currentHeading);
            sectionTexts.add(current.toString().trim());
        }

        // 2. Break oversized sections into pieces on paragraph boundaries.
        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < sectionTexts.size(); i++) {
            String h = sectionHeadings.get(i);
            List<String> parts = splitOversized(sectionTexts.get(i), maxChars);
            for (int p = 0; p < parts.size(); p++) {
                String ph = (h != null && p > 0) ? h + " (cont.)" : h;
                pieces.add(new Piece(ph, parts.get(p)));
            }
        }

        // 3. Greedily group pieces into chunks.
        List<StringBuilder> chunkTexts = new ArrayList<>();
        List<List<String>> chunkHeadings = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        List<String> bufHeadings = new ArrayList<>();
        for (Piece piece : pieces) {
            int added = piece.text.length() + (buf.length() > 0 ? 2 : 0);
            if (buf.length() > 0 && buf.length() + added > maxChars) {
                chunkTexts.add(buf);
                chunkHeadings.add(bufHeadings);
                buf = new StringBuilder();
                bufHeadings = new ArrayList<>();
            }
            if (buf.length() > 0) buf.append("\n\n");
            buf.append(piece.text);
            if (piece.heading != null) bufHeadings.add(piece.heading);
        }
        if (buf.length() > 0) {
            chunkTexts.add(buf);
            chunkHeadings.add(bufHeadings);
        }

        // 4. Enforce the chunk cap, reporting what is dropped.
        List<Chunk> chunks = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int i = 0; i < chunkTexts.size(); i++) {
            if (i < maxChunks) {
                chunks.add(new Chunk(i, chunkTexts.get(i).toString(), chunkHeadings.get(i)));
            } else if (chunkHeadings.get(i).isEmpty()) {
                dropped.add("Part " + (i + 1));
            } else {
                dropped.addAll(chunkHeadings.get(i));
            }
        }

        return new Result(chunks, dropped, buildOutline(sectionHeadings));
    }

    private static List<String> splitOversized(String section, int maxChars) {
        List<String> out = new ArrayList<>();
        if (section.length() <= maxChars) {
            out.add(section);
            return out;
        }
        StringBuilder buf = new StringBuilder();
        for (String para : section.split("\n\\s*\n")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            while (p.length() > maxChars) {           // a single huge paragraph
                int cut = p.lastIndexOf(' ', maxChars);
                if (cut < maxChars / 2) cut = maxChars;
                if (buf.length() > 0) { out.add(buf.toString()); buf.setLength(0); }
                out.add(p.substring(0, cut).trim());
                p = p.substring(cut).trim();
            }
            if (buf.length() > 0 && buf.length() + p.length() + 2 > maxChars) {
                out.add(buf.toString());
                buf.setLength(0);
            }
            if (buf.length() > 0) buf.append("\n\n");
            buf.append(p);
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    private static String cleanHeading(String line) {
        return line.trim().replaceFirst("^#{1,6}\\s+", "").replaceFirst(":$", "").trim();
    }

    private static String buildOutline(List<String> headings) {
        StringBuilder sb = new StringBuilder();
        for (String h : headings) {
            if (h == null) continue;
            String line = "- " + h + "\n";
            if (sb.length() + line.length() > MAX_OUTLINE_CHARS) {
                sb.append("- ...\n");
                break;
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }
}
