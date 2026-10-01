/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.utils.ProtoNode;

/**
 * Replaces the auto-translated description of the description panel with the original description.
 * <p>
 * The description element is a list of segments: texts, and attachments such as channel cards
 * that replace a link of the description. Texts are attributed strings, with runs (links, styles,
 * icons) that reference ranges of the text. The original description is plain text, so it's split
 * at the links replaced by attachments, and the runs are moved to the same texts in the original
 * description. Runs whose text is not in the original description are removed.
 */
final class OriginalDescription {

    /**
     * Field of the segments in the message that lists them. The path to that message
     * depends on the app version, so the message is found by the segments it includes.
     */
    private static final int SEGMENT_FIELD = 1;
    private static final int SEGMENT_TEXT_FIELD = 1;
    static final int TEXT_CONTENT_FIELD = 1;
    private static final int RANGE_START_FIELD = 1;
    private static final int RANGE_LENGTH_FIELD = 2;

    private OriginalDescription() {
    }

    /**
     * Ranges of the runs of a text, with the same range.
     */
    private static final class Anchor {
        final int start;
        final int end;
        final List<ProtoNode> runs = new ArrayList<>();
        /**
         * Start of the text in the restored text, or -1 if not found in the original text.
         */
        int restoredStart = -1;

        Anchor(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * @return If the description was replaced.
     */
    static boolean restore(List<ProtoNode> root, String originalDescription) {
        // The segments are listed by the message whose segments include the most text.
        List<ProtoNode> segments = new ArrayList<>();
        findSegments(root, segments, new int[]{0});
        if (segments.isEmpty()) {
            Logger.printDebug(() -> "Description segments not found");
            return false;
        }
        List<String> originalTexts = splitAtAttachments(segments, originalDescription);
        if (originalTexts == null) {
            // The shown description is not a translation of the original description,
            // such as a description localized by the uploader with other attachments.
            // The original description replaces the first text, and the other segments are removed.
            ProtoNode firstText = null;
            for (ProtoNode segment : segments) {
                ProtoNode text = ProtoNode.field(segment.children, SEGMENT_TEXT_FIELD);
                if (firstText == null && text != null && text.children != null) {
                    firstText = text;
                }
            }
            if (firstText == null || !restoreText(firstText.children, originalDescription)) {
                return false;
            }
            for (ProtoNode segment : segments) {
                if (firstText.getParent() != segment) {
                    segment.remove();
                }
            }
            Logger.printDebug(() -> "Restored description that does not match the original attachments");
            return true;
        }

        boolean modified = false;
        int textIndex = 0;
        for (ProtoNode segment : segments) {
            ProtoNode text = ProtoNode.field(segment.children, SEGMENT_TEXT_FIELD);
            if (text == null) {
                continue;
            }
            String originalText = originalTexts.get(textIndex++);
            if (text.children != null) {
                modified |= restoreText(text.children, originalText);
            }
        }

        if (modified) {
            Logger.printDebug(() -> "Restored description");
        }
        return modified;
    }

    /**
     * Finds the segments of the description, the fields of the message whose segments include the most text.
     */
    private static void findSegments(List<ProtoNode> message, List<ProtoNode> segments, int[] segmentsTextLength) {
        List<ProtoNode> candidates = new ArrayList<>();
        int textLength = 0;
        for (ProtoNode field : message) {
            List<ProtoNode> children = field.children;
            if (children == null) {
                continue;
            }
            if (field.getFieldNumber() == SEGMENT_FIELD) {
                candidates.add(field);
                textLength += segmentTextLength(children);
            }
            findSegments(children, segments, segmentsTextLength);
        }
        if (textLength > segmentsTextLength[0]) {
            segments.clear();
            segments.addAll(candidates);
            segmentsTextLength[0] = textLength;
        }
    }

    /**
     * Content with line breaks is not parsed as text, so the content is any field
     * that decodes as text, and not a message of other fields.
     *
     * @return The length of the content of a text segment, or 0 if the segment is not a text.
     */
    private static int segmentTextLength(List<ProtoNode> segment) {
        ProtoNode text = ProtoNode.field(segment, SEGMENT_TEXT_FIELD);
        if (text == null || text.children == null) {
            return 0;
        }
        ProtoNode content = ProtoNode.field(text.children, TEXT_CONTENT_FIELD);
        if (content == null || content.getVarint() != null) {
            return 0;
        }
        String decoded = decodeText(content);
        return decoded == null ? 0 : decoded.length();
    }

    /**
     * @return The field decoded as text, or null if the field is not a text, such as bytes of other data.
     */
    @Nullable
    static String decodeText(ProtoNode field) {
        String decoded = field.decodeUtf8();
        for (int i = 0, length = decoded.length(); i < length; i++) {
            final char c = decoded.charAt(i);
            if (c == '\uFFFD' || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                return null;
            }
        }
        return decoded;
    }

    /**
     * @return The original texts of the text segments, or null if an attachment
     *         is not found in the original description.
     */
    @Nullable
    private static List<String> splitAtAttachments(List<ProtoNode> segments, String description) {
        List<String> texts = new ArrayList<>();
        int textStart = 0;
        boolean previousIsText = false;

        for (ProtoNode segment : segments) {
            List<ProtoNode> segmentFields = segment.children;
            if (ProtoNode.field(segmentFields, SEGMENT_TEXT_FIELD) != null) {
                if (previousIsText) {
                    return null;
                }
                previousIsText = true;
                continue;
            }

            int[] link = findAttachmentLink(segmentFields, description, textStart);
            if (link == null) {
                return null;
            }
            if (previousIsText) {
                // Attachments are shown on their own line.
                int textEnd = link[0];
                if (textEnd > textStart && description.charAt(textEnd - 1) == '\n') {
                    textEnd--;
                }
                texts.add(description.substring(textStart, textEnd));
            }
            textStart = link[1];
            previousIsText = false;
        }

        if (previousIsText) {
            texts.add(description.substring(textStart));
        }
        return texts;
    }

    /**
     * @return The range of the link of the attachment, or null if not found.
     *         The link is the first link that includes a text of the attachment
     *         as a part of the link, such as the handle or the channel id of a channel card.
     */
    @Nullable
    private static int[] findAttachmentLink(List<ProtoNode> attachment, String description, int from) {
        int linkStart = description.length();
        int linkEnd = -1;
        for (ProtoNode node : ProtoNode.textNodes(attachment)) {
            String text = node.getText();
            final int textLength = text.length();
            for (int index = description.indexOf(text, from); index >= 0 && index < linkStart;
                 index = description.indexOf(text, index + 1)) {
                final int end = index + textLength;
                final int start = tokenStart(description, index, from);
                if (isLinkPart(description, start, index, end)) {
                    linkStart = start;
                    linkEnd = tokenEnd(description, end);
                    break;
                }
            }
        }
        return linkEnd < 0 ? null : new int[]{linkStart, linkEnd};
    }

    /**
     * @return If the text is a whole part of a link, such as a path segment or a query value.
     */
    private static boolean isLinkPart(String description, int linkStart, int start, int end) {
        return start > linkStart
                && description.lastIndexOf('/', start - 1) >= linkStart
                && !isNameCharacter(description.codePointBefore(start))
                && (end == description.length() || !isNameCharacter(description.codePointAt(end)));
    }

    private static boolean isNameCharacter(int codePoint) {
        return RestoreOriginalTitlesPatch.isWordCodePoint(codePoint) || codePoint == '_' || codePoint == '-' || codePoint == '.';
    }

    /**
     * Replaces a text that is not part of the description element, such as the channel description.
     * If the text is the content of an attributed string, the runs (links, styles) are moved too.
     */
    static void restore(ProtoNode content, String originalText) {
        ProtoNode parent = content.getParent();
        List<ProtoNode> text = parent == null ? null : parent.children;
        if (text != null && ProtoNode.field(text, TEXT_CONTENT_FIELD) == content) {
            restoreText(text, originalText);
        } else {
            content.setText(originalText);
        }
    }

    /**
     * Replaces the content with the original text, and moves the runs to the same texts.
     *
     * @return If the text was replaced.
     */
    private static boolean restoreText(List<ProtoNode> text, String originalText) {
        ProtoNode content = ProtoNode.field(text, TEXT_CONTENT_FIELD);
        if (content == null) {
            return false;
        }
        String translatedText = content.decodeUtf8();
        if (trim(translatedText).isEmpty() || translatedText.equals(originalText)) {
            return false;
        }

        final int translatedLength = translatedText.length();
        List<Anchor> anchors = findAnchors(text, content, translatedLength);

        // Runs are shown as in the translated text, such as shortened links and social icons.
        StringBuilder restored = new StringBuilder();
        int originalIndex = 0;
        int anchorsEnd = 0;
        for (Anchor anchor : anchors) {
            if (anchor.start < anchorsEnd || anchor.end - anchor.start == translatedLength) {
                continue; // Inside another run, or the entire text.
            }
            String runText = translatedText.substring(anchor.start, anchor.end);
            int[] originalRun = findRunText(anchor.runs, runText, originalText, originalIndex);
            if (originalRun == null) {
                continue;
            }

            restored.append(originalText, originalIndex, originalRun[0]);
            anchor.restoredStart = restored.length();
            restored.append(runText);
            originalIndex = originalRun[1];
            anchorsEnd = anchor.end;
        }
        restored.append(originalText, originalIndex, originalText.length());
        final int restoredLength = restored.length();

        for (Anchor anchor : anchors) {
            for (ProtoNode run : anchor.runs) {
                moveRun(run, anchor, anchors, translatedLength, restoredLength);
            }
        }
        content.setText(restored.toString());
        return true;
    }

    /**
     * @return The runs grouped by range, ordered by start and then by the longest range.
     */
    private static List<Anchor> findAnchors(List<ProtoNode> text, ProtoNode content, int textLength) {
        Map<Long, Anchor> anchors = new LinkedHashMap<>();
        for (ProtoNode run : new ArrayList<>(text)) {
            List<ProtoNode> runFields = run.children;
            if (run == content || runFields == null) {
                continue;
            }
            int[] range = getRange(runFields);
            if (range == null) {
                continue; // Not a run.
            }
            final int start = range[0];
            final int end = range[1];
            if (start < 0 || end < start || end > textLength) {
                run.remove();
                continue;
            }
            anchors.computeIfAbsent(((long) start << 32) | end, key -> new Anchor(start, end))
                    .runs.add(run);
        }

        List<Anchor> sorted = new ArrayList<>(anchors.values());
        sorted.sort((a, b) -> a.start != b.start
                ? Integer.compare(a.start, b.start)
                : Integer.compare(b.end, a.end));
        return sorted;
    }

    /**
     * @return The range of the text of the runs in the original text, or null if not found.
     *         Found with the text, or with the urls of the runs if the text is shortened.
     */
    @Nullable
    private static int[] findRunText(List<ProtoNode> runs, String runText, String originalText, int from) {
        final int originalLength = originalText.length();
        int foundStart = originalLength;
        int foundEnd = -1;

        String trimmedText = trim(runText);
        if (!trimmedText.isEmpty()) {
            int index = originalText.indexOf(trimmedText, from);
            if (index >= 0) {
                foundStart = index;
                foundEnd = index + trimmedText.length();
            }
        }

        for (ProtoNode run : runs) {
            for (String url : findUrls(run.children)) {
                int index = originalText.indexOf(url, from);
                if (index < 0 || index >= foundStart) {
                    continue;
                }
                int end = index + url.length();
                // The trailing slash is not part of the url.
                if (end < originalLength && originalText.charAt(end) == '/') {
                    end++;
                }
                foundStart = tokenStart(originalText, index, from);
                foundEnd = end;
            }
        }

        return foundEnd < 0 ? null : new int[]{foundStart, foundEnd};
    }

    /**
     * @return The urls of the run without the scheme, the 'www.' host prefix and the trailing slash,
     *         as the description can include the url with or without any of them.
     */
    private static List<String> findUrls(List<ProtoNode> run) {
        List<String> urls = new ArrayList<>();
        for (ProtoNode node : ProtoNode.textNodes(run)) {
            Uri uri = Uri.parse(node.getText());
            String host = uri.getHost();
            if (host == null) {
                continue;
            }
            // Redirects include the url as a query parameter.
            String redirectUrl = uri.getQueryParameter("q");
            if (redirectUrl != null) {
                uri = Uri.parse(redirectUrl);
                host = uri.getHost();
                if (host == null) {
                    continue;
                }
            }

            String url = uri.toString();
            url = url.substring(url.indexOf(host));
            if (url.startsWith("www.")) {
                url = url.substring("www.".length());
            }
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            urls.add(url);
        }
        return urls;
    }

    /**
     * Moves the run to the same text in the restored text, or removes it if the text is not found.
     */
    private static void moveRun(ProtoNode run, Anchor anchor, List<Anchor> anchors,
                                int translatedLength, int restoredLength) {
        int start = -1;
        int length = anchor.end - anchor.start;
        for (Anchor other : anchors) {
            if (other.restoredStart >= 0 && anchor.start >= other.start && anchor.end <= other.end) {
                start = other.restoredStart + anchor.start - other.start;
                break;
            }
        }
        if (start < 0 && length == translatedLength) {
            start = 0;
            length = restoredLength;
        }
        List<ProtoNode> runFields = run.children;
        List<ProtoNode> rangeMessage = runFields == null ? null : findRangeMessage(runFields);
        ProtoNode startNode = rangeMessage == null ? null : ProtoNode.field(rangeMessage, RANGE_START_FIELD);
        if (start < 0 || startNode == null) {
            run.remove();
            return;
        }

        ProtoNode lengthNode = ProtoNode.field(rangeMessage, RANGE_LENGTH_FIELD);
        if (!Long.valueOf(start).equals(startNode.getVarint())) {
            startNode.setVarint(start);
        }
        if (lengthNode != null && !Long.valueOf(length).equals(lengthNode.getVarint())) {
            lengthNode.setVarint(length);
        }
    }

    /**
     * @return The range of the run, which is the start and length fields of the run
     *         or of the closest sub message. Null if the run has no range.
     */
    @Nullable
    private static int[] getRange(List<ProtoNode> run) {
        List<ProtoNode> rangeMessage = findRangeMessage(run);
        if (rangeMessage == null) {
            return null;
        }
        ProtoNode startNode = ProtoNode.field(rangeMessage, RANGE_START_FIELD);
        ProtoNode lengthNode = ProtoNode.field(rangeMessage, RANGE_LENGTH_FIELD);
        Long start = startNode == null ? null : startNode.getVarint();
        Long length = lengthNode == null ? Long.valueOf(0) : lengthNode.getVarint();
        if (start == null || length == null || start > Integer.MAX_VALUE || length > Integer.MAX_VALUE) {
            return null;
        }
        return new int[]{start.intValue(), (int) (start + length)};
    }

    @Nullable
    private static List<ProtoNode> findRangeMessage(List<ProtoNode> run) {
        Queue<List<ProtoNode>> messages = new ArrayDeque<>();
        messages.add(run);
        while (!messages.isEmpty()) {
            List<ProtoNode> message = messages.remove();
            ProtoNode start = ProtoNode.field(message, RANGE_START_FIELD);
            if (start != null && start.getVarint() != null) {
                return message;
            }
            for (ProtoNode node : message) {
                List<ProtoNode> children = node.children;
                if (children != null) {
                    messages.add(children);
                }
            }
        }
        return null;
    }

    /**
     * @return The text without leading and trailing invisible characters, such as spaces
     *         and zero width spaces, that are added around links and icons.
     */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isInvisible(text.charAt(start))) {
            start++;
        }
        while (end > start && isInvisible(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isInvisible(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT;
    }

    private static int tokenStart(String text, int index, int from) {
        while (index > from && !Character.isWhitespace(text.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private static int tokenEnd(String text, int index) {
        final int length = text.length();
        while (index < length && !Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }
}
