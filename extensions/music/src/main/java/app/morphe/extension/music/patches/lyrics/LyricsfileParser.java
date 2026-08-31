/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LyricsfileParser {

    private LyricsfileParser() {
    }

    @Nullable
    public static Lyrics parse(@Nullable String yaml, String providerName) {
        if (yaml == null || yaml.isEmpty()) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        for (String raw : yaml.split("\\r?\\n")) {
            if (!raw.trim().isEmpty()) {
                lines.add(raw.replace("\t", "    "));
            }
        }
        if (lines.isEmpty()) {
            return null;
        }

        Cursor cursor = new Cursor();
        Object root = readValue(lines, cursor, 0);
        if (!(root instanceof Map)) {
            return null;
        }
        Map<?, ?> top = (Map<?, ?>) root;

        boolean instrumental = false;
        Object metaObject = top.get("metadata");
        if (metaObject instanceof Map) {
            Object instrumentalValue = ((Map<?, ?>) metaObject).get("instrumental");
            if (instrumentalValue instanceof Boolean) {
                instrumental = (Boolean) instrumentalValue;
            } else if (instrumentalValue instanceof String) {
                instrumental = Boolean.parseBoolean((String) instrumentalValue);
            }
        }

        List<LyricsLine> parsedLines = new ArrayList<>();
        Object linesObject = top.get("lines");
        if (linesObject instanceof List) {
            for (Object item : (List<?>) linesObject) {
                LyricsLine line = toLine(item);
                if (line != null) {
                    parsedLines.add(line);
                }
            }
        }

        if (instrumental) {
            return parsedLines.isEmpty() ? Lyrics.NOT_FOUND : new Lyrics(parsedLines, providerName, true);
        }

        boolean synced = parsedLines.stream()
                .anyMatch(line -> line.startTimeMs() != LyricsLine.NO_TIME);
        if (synced) {
            return new Lyrics(parsedLines, providerName, true);
        }

        Object plainObject = top.get("plain");
        if (plainObject instanceof String) {
            List<LyricsLine> plain = LrcParser.parsePlain((String) plainObject);
            if (!plain.isEmpty()) {
                return new Lyrics(plain, providerName, false);
            }
        }
        return Lyrics.NOT_FOUND;
    }

    @Nullable
    private static LyricsLine toLine(Object item) {
        if (!(item instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) item;

        Object textObject = map.get("text");
        String text = textObject instanceof String ? (String) textObject : "";

        List<Word> words = new ArrayList<>();
        Object wordsObject = map.get("words");
        if (wordsObject instanceof List) {
            for (Object wordObject : (List<?>) wordsObject) {
                if (!(wordObject instanceof Map)) {
                    continue;
                }
                Map<?, ?> wordMap = (Map<?, ?>) wordObject;
                Object wordText = wordMap.get("text");
                String wordString = wordText instanceof String ? (String) wordText : "";
                long start = asLong(wordMap.get("start_ms"));
                long end = asLong(wordMap.get("end_ms"));
                words.add(new Word(start, end, wordString));
            }
        }

        if (text.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (Word word : words) {
                String wordText = word.text();
                if (wordText.isEmpty()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(wordText);
            }
            text = builder.toString();
        }

        if (words.size() > 1) {
            for (int i = 0; i < words.size() - 1; i++) {
                Word word = words.get(i);
                if (word.endMs() == LyricsLine.NO_TIME) {
                    words.set(i, new Word(word.startMs(), words.get(i + 1).startMs(), word.text()));
                }
            }
        }

        return new LyricsLine(asLong(map.get("start_ms")), text, words);
    }

    private static long asLong(Object value) {
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return LyricsLine.NO_TIME;
            }
        }
        return LyricsLine.NO_TIME;
    }

    private static final class Cursor {
        int index;
    }

    private static Object readValue(List<String> lines, Cursor cursor, int indent) {
        if (isSequenceItem(lines.get(cursor.index), indent)) {
            return readSequence(lines, cursor, indent);
        }
        return readMapping(lines, cursor, indent);
    }

    private static boolean isSequenceItem(String line, int indent) {
        return indentOf(line) == indent && line.trim().startsWith("- ");
    }

    private static Object readSequence(List<String> lines, Cursor cursor, int indent) {
        List<Object> sequence = new ArrayList<>();
        while (cursor.index < lines.size()) {
            String line = lines.get(cursor.index);
            if (indentOf(line) != indent || !line.trim().startsWith("- ")) {
                break;
            }
            String rest = line.trim().substring(2).strip();
            if (rest.isEmpty()) {
                cursor.index++;
                sequence.add(readValue(lines, cursor, indent + 2));
            } else {
                // The first mapping key sits inline after "- "; rewrite it as a
                // regular mapping line so the shared reader can consume it.
                lines.set(cursor.index, " ".repeat(indent + 2) + rest);
                sequence.add(readMapping(lines, cursor, indent + 2));
            }
        }
        return sequence;
    }

    private static Object readMapping(List<String> lines, Cursor cursor, int indent) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (cursor.index < lines.size()) {
            String line = lines.get(cursor.index);
            if (indentOf(line) != indent) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                cursor.index++;
                continue;
            }
            String key = line.substring(indent, colon).trim();
            String value = line.substring(colon + 1).strip();
            cursor.index++;

            if (value.isEmpty()) {
                if (cursor.index < lines.size() && indentOf(lines.get(cursor.index)) > indent) {
                    map.put(key, readValue(lines, cursor, indentOf(lines.get(cursor.index))));
                } else {
                    map.put(key, "");
                }
            } else if (isBlockScalar(value)) {
                map.put(key, readBlockScalar(lines, cursor, indent));
            } else {
                map.put(key, unquote(value));
            }
        }
        return map;
    }

    private static boolean isBlockScalar(String value) {
        return value.equals("|") || value.equals(">") || value.startsWith("|-")
                || value.startsWith(">-") || value.startsWith("|+") || value.startsWith(">+");
    }

    private static String readBlockScalar(List<String> lines, Cursor cursor, int indent) {
        int base = -1;
        for (int i = cursor.index; i < lines.size(); i++) {
            if (indentOf(lines.get(i)) <= indent) {
                break;
            }
            base = indentOf(lines.get(i));
            break;
        }
        if (base < 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        while (cursor.index < lines.size()) {
            String line = lines.get(cursor.index);
            if (indentOf(line) <= indent) {
                break;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            int strip = Math.min(base, line.length());
            builder.append(line.substring(strip));
            cursor.index++;
        }
        return builder.toString();
    }

    private static String unquote(String value) {
        String string = value.strip();
        if (string.length() >= 2 && string.startsWith("'") && string.endsWith("'")) {
            return string.substring(1, string.length() - 1).replace("''", "'");
        }
        if (string.length() >= 2 && string.startsWith("\"") && string.endsWith("\"")) {
            return string.substring(1, string.length() - 1)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return string;
    }

    private static int indentOf(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }
}
