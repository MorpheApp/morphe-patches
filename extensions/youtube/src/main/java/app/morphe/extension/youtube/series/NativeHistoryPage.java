package app.morphe.extension.youtube.series;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Only reads native History video rows and their progress overlays, never menu/recommendation IDs.
 */
final class NativeHistoryPage {
    static final class Row {
        final String id;
        final Integer percent;
        final Long positionMs;

        Row(String id, Integer percent, Long positionMs) {
            this.id = id;
            this.percent = percent;
            this.positionMs = positionMs;
        }

        String signature() {
            return String.valueOf(positionMs) + ":" + percent;
        }
    }

    final Map<String, Row> rows = new LinkedHashMap<>();
    final Map<String, Integer> percentages = new LinkedHashMap<>();
    String continuation = "";

    static List<byte[]> children(byte[] message, int number) {
        List<byte[]> result = new ArrayList<>();
        for (NavigationProto.Field field : NavigationProto.read(message, 4 * 1024 * 1024))
            if (field.number == number && field.wire == 2) result.add(field.value);
        return result;
    }

    static List<byte[]> path(byte[] message, int... path) {
        List<byte[]> nodes = Collections.singletonList(message);
        for (int number : path) {
            List<byte[]> next = new ArrayList<>();
            for (byte[] node : nodes) next.addAll(children(node, number));
            if (next.size() > 1000) throw new IllegalArgumentException("Too many history rows");
            nodes = next;
        }
        return nodes;
    }

    static NativeHistoryPage parse(byte[] bytes) {
        NativeHistoryPage page = new NativeHistoryPage();
        List<byte[]> sections = path(bytes, 9, 58173949, 1, 58174010, 4, 49399797);
        sections.addAll(path(bytes, 10, 49399797));
        if (sections.isEmpty()) throw new IllegalArgumentException("Unsupported history response");
        for (byte[] section : sections) {
            for (byte[] row : path(section, 1, 50195462, 1, 50630979)) {
                String id = NavigationProto.string(row, 1);
                if (!id.matches("[A-Za-z0-9_-]{11}")) continue;
                Integer percent = null;
                for (byte[] overlay : path(row, 32, 110282477)) {
                    for (NavigationProto.Field field : NavigationProto.read(overlay)) {
                        if (field.number == 1 && field.wire == 0 && field.value.length == 1) {
                            int value = field.value[0] & 255;
                            if (value <= 100) percent = value;
                        }
                    }
                }
                Long position = null;
                for (byte[] endpoint : path(row, 8, 48687757)) {
                    if (!id.equals(NavigationProto.string(endpoint, 1))) continue;
                    for (NavigationProto.Field field : NavigationProto.read(endpoint)) {
                        if (field.number != 7 || field.wire != 5 || field.value.length != 4)
                            continue;
                        float seconds =
                                java.nio.ByteBuffer.wrap(field.value)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                        .getFloat();
                        // Match the host converter: truncate seconds before converting to ms.
                        if (Float.isFinite(seconds) && seconds >= 0 && seconds <= 604800)
                            position = (long) seconds * 1000;
                    }
                }
                page.rows.putIfAbsent(id, new Row(id, percent, position));
                if (percent != null) page.percentages.putIfAbsent(id, percent);
            }
            for (byte[] token : path(section, 2, 52047593, 1)) {
                if (token.length > 8192)
                    throw new IllegalArgumentException("History token too large");
                page.continuation = new String(token, StandardCharsets.UTF_8);
            }
        }
        return page;
    }
}
