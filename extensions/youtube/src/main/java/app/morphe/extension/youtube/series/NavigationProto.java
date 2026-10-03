package app.morphe.extension.youtube.series;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Small bounded wire editor; preserves unknown renderer fields and uses the host protobuf runtime.
 */
final class NavigationProto {
    static final int ITEM = 117501096, BROWSE = 48687626;

    static final class Field {
        final int number, wire;
        final byte[] value;

        Field(int number, int wire, byte[] value) {
            this.number = number;
            this.wire = wire;
            this.value = value;
        }
    }

    static List<Field> read(byte[] data) {
        return read(data, 1_048_576);
    }

    static List<Field> read(byte[] data, int maxBytes) {
        if (data.length > maxBytes) throw new IllegalArgumentException("Renderer too large");
        List<Field> fields = new ArrayList<>();
        int[] p = {0};
        while (p[0] < data.length) {
            long key = varint(data, p);
            int number = (int) (key >>> 3), wire = (int) (key & 7);
            if (number <= 0 || number > 536870911)
                throw new IllegalArgumentException("Invalid field");
            int start = p[0], end;
            if (wire == 0) {
                varint(data, p);
                end = p[0];
            } else if (wire == 1 || wire == 5) {
                end = p[0] + (wire == 1 ? 8 : 4);
                p[0] = end;
            } else if (wire == 2) {
                long size = varint(data, p);
                if (size < 0 || size > data.length - p[0])
                    throw new IllegalArgumentException("Truncated field");
                start = p[0];
                end = p[0] + (int) size;
                p[0] = end;
            } else throw new IllegalArgumentException("Unsupported wire type");
            if (end > data.length) throw new IllegalArgumentException("Truncated field");
            if (fields.size() >= 4096) throw new IllegalArgumentException("Too many fields");
            fields.add(new Field(number, wire, Arrays.copyOfRange(data, start, end)));
        }
        return fields;
    }

    private static long varint(byte[] data, int[] p) {
        long n = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (p[0] >= data.length) throw new IllegalArgumentException("Truncated varint");
            int b = data[p[0]++] & 255;
            if (shift == 63 && b > 1) throw new IllegalArgumentException("Invalid varint");
            n |= (long) (b & 127) << shift;
            if ((b & 128) == 0) return n;
        }
        throw new IllegalArgumentException("Invalid varint");
    }

    private static void writeVarint(ByteArrayOutputStream out, long n) {
        while ((n & ~127L) != 0) {
            out.write((int) n & 127 | 128);
            n >>>= 7;
        }
        out.write((int) n);
    }

    static byte[] encode(List<Field> fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Field f : fields) {
            writeVarint(out, ((long) f.number << 3) | f.wire);
            if (f.wire == 2) writeVarint(out, f.value.length);
            out.write(f.value, 0, f.value.length);
        }
        return out.toByteArray();
    }

    static byte[] get(byte[] data, int number) {
        for (Field f : read(data)) if (f.number == number && f.wire == 2) return f.value;
        return new byte[0];
    }

    static String string(byte[] data, int number) {
        return new String(get(data, number), StandardCharsets.UTF_8);
    }

    static byte[] field(int number, byte[] value) {
        return encode(Collections.singletonList(new Field(number, 2, value)));
    }

    static byte[] text(int number, String value) {
        return field(number, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] replace(byte[] data, int number, byte[] value) {
        List<Field> fields = read(data);
        fields.removeIf(f -> f.number == number);
        fields.add(new Field(number, 2, value));
        return encode(fields);
    }

    static int icon(byte[] item) {
        byte[] icon = get(item, 5);
        for (Field f : read(icon))
            if (f.number == 1 && f.wire == 0) return (int) varint(f.value, new int[] {0});
        return 0;
    }

    static String kind(byte[] item) {
        String id = string(item, 1).toLowerCase(Locale.ROOT);
        int icon = icon(item);
        if (id.contains("history")) return "history";
        if (icon == 406 || icon == 1154) return "home";
        if (id.contains("subscription") || icon == 408) return "subscriptions";
        if (id.contains("activity") || id.contains("notification") || icon == 355 || icon == 1156)
            return "notifications";
        if (id.contains("library") || id.contains("account") || id.contains("you"))
            return "profile";
        return "other";
    }

    static byte[] history(byte[] home, String title) {
        return destination(home, "FEhistory", title, 2);
    }

    static byte[] destination(byte[] home, String browseId, String title, int icon) {
        byte[] item = replace(home, 1, browseId.getBytes(StandardCharsets.UTF_8));
        item = replace(item, 2, field(BROWSE, text(2, browseId)));
        item = replace(item, 3, field(1, text(1, title)));
        item = replace(item, 4, field(75730170, text(2, title)));
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        writeVarint(value, icon);
        item =
                replace(
                        item,
                        5,
                        encode(Collections.singletonList(new Field(1, 0, value.toByteArray()))));
        // Tracking and target IDs belong to the original Home tab and must not be copied.
        List<Field> fields = read(item);
        fields.removeIf(f -> f.number == 10);
        return field(ITEM, encode(fields));
    }

    private NavigationProto() {}
}
