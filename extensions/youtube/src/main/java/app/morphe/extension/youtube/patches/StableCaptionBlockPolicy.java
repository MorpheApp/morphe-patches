/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.youtube.patches.CaptionXml.children;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Logger;

/** Source-timed complete blocks; the renderer supplies the actual two-line capacity. */
final class StableCaptionBlockPolicy {
    static final int MAX_ATOMS = 20000, MAX_BLOCK_CHARS = 512;
    static final long MAX_PREVIEW_MS = 8000, MIN_DURATION_MS = 1500, MAX_GAP_MS = 2000;

    interface Fit {
        boolean fits(String text);
    }

    static final class Block {
        final long start, end;
        final String text;
        final boolean fits;

        Block(long start, long end, String text, boolean fits) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.fits = fits;
        }
    }

    private static final class Atom {
        final long start, end;
        final String text;
        final boolean barrierBefore;

        Atom(long start, long end, String text, boolean barrier) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.barrierBefore = barrier;
        }
    }

    private final List<Atom> atoms;
    private final int[] characters;

    private StableCaptionBlockPolicy(List<Atom> atoms) {
        this.atoms = List.copyOf(atoms);
        characters = new int[atoms.size() + 1];
        for (int i = 0; i < atoms.size(); i++)
            characters[i + 1] =
                    characters[i] + atoms.get(i).text.codePointCount(0, atoms.get(i).text.length());
    }

    static StableCaptionBlockPolicy parse(Document d) {
        try {
            if (d == null || d.getElementsByTagName("pen").getLength() != 0) return null;
            Element root = d.getDocumentElement(), body = null;
            for (Element e : children(root))
                if (e.getTagName().equals("body")) {
                    if (body != null) return null;
                    body = e;
                }
            if (body == null) return null;
            String window = null;
            List<Atom> atoms = new ArrayList<>();
            long previousParagraph = -1;
            boolean barrier = false;
            for (Element e : children(body)) {
                if (e.getTagName().equals("w")) {
                    if (window != null || number(e, "t") != 0 || e.getAttribute("id").isEmpty())
                        return null;
                    window = e.getAttribute("id");
                    continue;
                }
                if (window == null
                        || !e.getTagName().equals("p")
                        || !window.equals(e.getAttribute("w"))
                        || !attributes(e, "t", "d", "w", "a", "p")) return null;
                if (e.getAttribute("a").equals("1")) {
                    if (!e.getTextContent().equals("\n") || !children(e).isEmpty()) return null;
                    continue;
                }
                if (!e.getAttribute("a").isEmpty() && !e.getAttribute("a").equals("0")) return null;
                long start = number(e, "t"), duration = number(e, "d");
                if (start < 0
                        || start <= previousParagraph
                        || start > 7200000
                        || duration <= 0
                        || duration > 600000) return null;
                previousParagraph = start;
                List<Element> segments = children(e);
                if (segments.size() > 2048) return null;
                int before = atoms.size();
                long previousOffset = -1;
                boolean first = true;
                StringBuilder whitespace = new StringBuilder();
                if (segments.isEmpty()) {
                    if (!add(atoms, start, start + duration, e.getTextContent(), true, barrier))
                        return null;
                } else {
                    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
                        if ((n.getNodeType() == Node.TEXT_NODE
                                        || n.getNodeType() == Node.CDATA_SECTION_NODE)
                                && !n.getTextContent().isBlank()) return null;
                    for (Element segment : segments) {
                        if (!segment.getTagName().equals("s")
                                || !children(segment).isEmpty()
                                || !attributes(segment, "t", "p", "ac")) return null;
                        long offset = segment.hasAttribute("t") ? number(segment, "t") : 0;
                        if (offset < 0 || offset < previousOffset || offset >= duration) return null;
                        previousOffset = offset;
                        String text = segment.getTextContent();
                        if (text.isBlank()) {
                            if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) return null;
                            whitespace.append(text);
                        } else {
                            if (!add(
                                    atoms,
                                    start + offset,
                                    start + duration,
                                    whitespace + text,
                                    first,
                                    first && barrier)) return null;
                            whitespace.setLength(0);
                            first = false;
                        }
                    }
                }
                if (atoms.size() == before && !atoms.isEmpty()) {
                    Atom last = atoms.get(atoms.size() - 1);
                    if (last.start >= start) return null;
                    if (last.end > start)
                        atoms.set(
                                atoms.size() - 1,
                                new Atom(last.start, start, last.text, last.barrierBefore));
                    barrier = true;
                } else if (atoms.size() > before) {
                    barrier = false;
                }
            }
            return atoms.isEmpty() ? null : new StableCaptionBlockPolicy(atoms);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "parse failure", ex);
            return null;
        }
    }

    /**
     * @return False if the source is too complex for stable blocks.
     */
    private static boolean add(
            List<Atom> atoms,
            long time,
            long end,
            String text,
            boolean paragraph,
            boolean barrier) {
        if (text.length() > 4096 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
            return false;
        if (text.isBlank()) return true;
        if (!atoms.isEmpty()) {
            Atom last = atoms.get(atoms.size() - 1);
            // Overlapping source words.
            if (time < last.start) return false;
            if (paragraph
                    && !Character.isWhitespace(last.text.charAt(last.text.length() - 1))
                    && !Character.isWhitespace(text.charAt(0))
                    && ".,!?;:)]}".indexOf(text.charAt(0)) < 0) text = " " + text;
            if (time == last.start) {
                if (last.text.length() + text.length() > 4096) return false;
                atoms.set(
                        atoms.size() - 1,
                        new Atom(
                                time,
                                Math.max(end, last.end),
                                last.text + text,
                                last.barrierBefore));
                return true;
            }
        }
        if (atoms.size() >= MAX_ATOMS) return false;
        atoms.add(new Atom(time, end, text, barrier));
        return true;
    }

    private String text(int from, int to) {
        StringBuilder s = new StringBuilder();
        for (int i = from; i < to; i++) s.append(atoms.get(i).text);
        return s.toString().strip().replaceAll("[ \\t]+", " ");
    }

    private boolean bounded(int from, int to) {
        return atoms.get(to - 1).start - atoms.get(from).start <= MAX_PREVIEW_MS
                && characters[to] - characters[from] <= MAX_BLOCK_CHARS;
    }

    private Block block(int from, int to, boolean fits) {
        long end = atoms.get(to - 1).end;
        if (to < atoms.size()) {
            Atom next = atoms.get(to);
            end =
                    !next.barrierBefore && next.start - end <= MAX_GAP_MS
                            ? next.start
                            : Math.min(end, next.start);
        }
        return new Block(atoms.get(from).start, end, text(from, to), fits);
    }

    private boolean boundary(int at) {
        if (at >= atoms.size()) return true;
        String before = atoms.get(at - 1).text, raw = atoms.get(at).text;
        if (!Character.isWhitespace(before.charAt(before.length() - 1))
                && !Character.isWhitespace(raw.charAt(0))) return false;
        String next = raw.stripLeading();
        if (next.isEmpty()) return false;
        int first = next.codePointAt(0), type = Character.getType(first);
        return ".,!?;:)]}".indexOf(first) < 0
                && type != Character.END_PUNCTUATION
                && type != Character.FINAL_QUOTE_PUNCTUATION;
    }

    List<Block> plan(Fit fit) {
        int size = atoms.size();
        int[] next = new int[size];
        boolean[] supported = new boolean[size];
        long[] shortfall = new long[size + 1];
        double[] cost = new double[size + 1];
        for (int from = size - 1; from >= 0; from--) {
            int limit = from + 1;
            while (limit < size
                    && !atoms.get(limit).barrierBefore
                    && atoms.get(limit).start - atoms.get(limit - 1).end <= MAX_GAP_MS
                    && bounded(from, limit + 1)) limit++;
            int low = from + 1, high = limit, best = from;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (bounded(from, mid) && fit.fits(text(from, mid))) {
                    best = mid;
                    low = mid + 1;
                } else high = mid - 1;
            }
            long bestShort = Long.MAX_VALUE;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int to = from + 1; to <= best; to++) {
                if (!boundary(to)) continue;
                Block candidate = block(from, to, true);
                long duration = candidate.end - candidate.start;
                if (duration <= 0) continue;
                long missing = shortfall(candidate) + shortfall[to];
                int chars = candidate.text.codePointCount(0, candidate.text.length());
                // Relative reading load, not a language-specific characters-per-second limit.
                char last = candidate.text.charAt(candidate.text.length() - 1);
                double value =
                        cost[to]
                                + chars * (double) chars * 1000 / duration
                                + 40
                                + (Character.isLetterOrDigit(last) ? 20 : 0);
                if (missing < bestShort || (missing == bestShort && value < bestCost)) {
                    bestShort = missing;
                    bestCost = value;
                    next[from] = to;
                    supported[from] = true;
                }
            }
            if (next[from] == 0) {
                int to = from + 1;
                while (to < size && !boundary(to)) to++;
                next[from] = to;
                Block nativeBlock = block(from, to, false);
                bestShort = shortfall(nativeBlock) + shortfall[to];
                bestCost = cost[to] + 40;
            }
            shortfall[from] = bestShort;
            cost[from] = bestCost;
        }
        List<Block> out = new ArrayList<>();
        for (int from = 0; from < size; from = next[from]) {
            Block b = block(from, next[from], supported[from]);
            out.add(b.fits && !fit.fits(b.text) ? block(from, next[from], false) : b);
        }
        return List.copyOf(out);
    }

    /** Native timeline template used only while source text or a bounded borrowed gap is active. */
    long templateTime(long time) {
        int low = 0, high = atoms.size() - 1, found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (atoms.get(mid).start <= time) {
                found = mid;
                low = mid + 1;
            } else high = mid - 1;
        }
        if (found < 0) return -1;
        Atom last = atoms.get(found);
        if (time < last.end) return last.start;
        if (found + 1 < atoms.size()) {
            Atom next = atoms.get(found + 1);
            if (!next.barrierBefore && next.start - last.end <= MAX_GAP_MS && time < next.start)
                return last.start;
        }
        return -1;
    }

    private static long shortfall(Block b) {
        return Math.max(0, MIN_DURATION_MS - (b.end - b.start));
    }

    static Block at(List<Block> blocks, long time) {
        int low = 0, high = blocks.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Block b = blocks.get(mid);
            if (time < b.start) high = mid - 1;
            else if (time >= b.end) low = mid + 1;
            else return b;
        }
        return null;
    }

    /**
     * @return The time attribute, or -1 if it is missing or invalid.
     */
    private static long number(Element e, String key) {
        String s = e.getAttribute(key);
        return s.matches("[0-9]{1,10}") ? Long.parseLong(s) : -1;
    }

    private static boolean attributes(Element e, String... allowed) {
        List<String> keys = Arrays.asList(allowed);
        NamedNodeMap a = e.getAttributes();
        for (int i = 0; i < a.getLength(); i++)
            if (!keys.contains(a.item(i).getNodeName())) return false;
        return true;
    }
}
