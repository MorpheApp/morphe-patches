/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Field of a proto message parsed without a schema.
 * <p>
 * Length delimited fields are parsed as text if the payload is printable UTF-8,
 * otherwise as a message if possible, otherwise kept as bytes.
 * Only text, varints and the bytes of length delimited fields can be modified, and fields can be removed.
 * All other fields are written back unchanged.
 * Fields reference the parsed bytes instead of copying them.
 */
public final class ProtoNode {

    private static final int MAX_DEPTH = 64;

    @Nullable
    private final ProtoNode parent;
    private final int fieldNumber;

    private final byte[] bytes;
    private final int tagStart;
    /**
     * Start of the value, or the start of the payload of length delimited fields.
     */
    private final int valueStart;
    private final int valueEnd;
    private final boolean lengthDelimited;

    @Nullable
    public List<ProtoNode> children;
    @Nullable
    private String text;
    @Nullable
    private Long varint;
    /**
     * Replaced bytes of a length delimited field.
     */
    @Nullable
    private byte[] payload;
    /**
     * If the text of this field or a sub-field is modified.
     */
    private boolean modified;

    private ProtoNode(@Nullable ProtoNode parent, int fieldNumber, byte[] bytes,
                      int tagStart, int valueStart, int valueEnd, boolean lengthDelimited) {
        this.parent = parent;
        this.fieldNumber = fieldNumber;
        this.bytes = bytes;
        this.tagStart = tagStart;
        this.valueStart = valueStart;
        this.valueEnd = valueEnd;
        this.lengthDelimited = lengthDelimited;
    }

    /**
     * @return The fields of the message, or null if the bytes are not a valid proto message.
     */
    @Nullable
    public static List<ProtoNode> parse(byte[] bytes) {
        return parse(bytes, 0, bytes.length, null, 0);
    }

    @Nullable
    private static List<ProtoNode> parse(byte[] bytes, int start, int end, @Nullable ProtoNode parent,
                                         int depth) {
        if (depth > MAX_DEPTH || start >= end || !isMessage(bytes, start, end)) {
            return null;
        }

        List<ProtoNode> nodes = new ArrayList<>();
        CodedInputStream input = CodedInputStream.newInstance(bytes, start, end - start);

        try {
            while (!input.isAtEnd()) {
                final int tagStart = start + input.getTotalBytesRead();
                final int tag = input.readTag();
                final int wireType = WireFormat.getTagWireType(tag);
                final boolean lengthDelimited = wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED;

                int valueStart = start + input.getTotalBytesRead();
                switch (wireType) {
                    case WireFormat.WIRETYPE_VARINT -> input.readRawVarint64();
                    case WireFormat.WIRETYPE_FIXED64 -> input.readRawLittleEndian64();
                    case WireFormat.WIRETYPE_FIXED32 -> input.readRawLittleEndian32();
                    case WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                        final int length = input.readRawVarint32();
                        valueStart = start + input.getTotalBytesRead();
                        input.skipRawBytes(length);
                    }
                    default -> {
                        return null; // Groups are not used.
                    }
                }
                final int valueEnd = start + input.getTotalBytesRead();

                ProtoNode node = new ProtoNode(parent, WireFormat.getTagFieldNumber(tag), bytes,
                        tagStart, valueStart, valueEnd, lengthDelimited);
                if (lengthDelimited) {
                    node.text = decodeText(bytes, valueStart, valueEnd, false);
                    if (node.text == null) {
                        node.children = parse(bytes, valueStart, valueEnd, node, depth + 1);
                    }
                }
                nodes.add(node);
            }
        } catch (IOException ex) {
            return null; // Not a proto message.
        }

        return nodes;
    }

    /**
     * Most fields that are not text are parsed as a message, and many of them are other data. The parser
     * throws an exception for each of them, and the elements are parsed on the main thread while a list loads,
     * so the fields are checked first by the same rules as the parser, without exceptions.
     *
     * @return If the parser can read the bytes as the fields of a message.
     */
    private static boolean isMessage(byte[] bytes, int start, int end) {
        int position = start;
        while (position < end) {
            final long tag = readVarint(bytes, position, end);
            // Field number 0 is not a valid tag.
            if (tag < 0 || ((int) tag >>> 3) == 0) {
                return false;
            }
            position = (int) (tag >>> 32);
            switch ((int) tag & 0x7) {
                case WireFormat.WIRETYPE_VARINT -> {
                    final long value = readVarint(bytes, position, end);
                    if (value >= 0) {
                        position = (int) (value >>> 32);
                    } else if (end - position >= 10 && (bytes[position + 9] & 1) == 1) {
                        // The parser reads a value of 10 bytes at once when it can, and it then takes
                        // only the lowest bit of the 10th byte, which can also have the continuation bit.
                        position += 10;
                    } else {
                        return false;
                    }
                }
                case WireFormat.WIRETYPE_FIXED64 -> {
                    if (end - position < 8) {
                        return false;
                    }
                    position += 8;
                }
                case WireFormat.WIRETYPE_FIXED32 -> {
                    if (end - position < 4) {
                        return false;
                    }
                    position += 4;
                }
                case WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    final long varint = readVarint(bytes, position, end);
                    if (varint < 0) {
                        return false;
                    }
                    position = (int) (varint >>> 32);
                    final int length = (int) varint;
                    if (length < 0 || length > end - position) {
                        return false;
                    }
                    position += length;
                }
                default -> {
                    return false; // Groups are not used.
                }
            }
        }
        return true;
    }

    /**
     * Reads a varint as the parser does: at most 10 bytes, of which the value keeps the low 32 bits.
     *
     * @return The low 32 bits of the value, and the position after the varint in the high 32 bits,
     *         or -1 if the varint is longer than 10 bytes or does not end before the end.
     */
    private static long readVarint(byte[] bytes, int position, int end) {
        int value = 0;
        for (int i = 0; i < 10 && position < end; i++) {
            final byte b = bytes[position++];
            if (i < 5) {
                value |= (b & 0x7F) << (7 * i);
            }
            if (b >= 0) {
                return ((long) position << 32) | (value & 0xFFFFFFFFL);
            }
        }
        return -1;
    }

    /**
     * The bytes are checked as UTF-8 here, without a charset decoder. Most fields of an element are
     * checked, and the elements are parsed on the main thread while a list loads, so a decoder that
     * throws an exception for every field that is not text makes the list stutter.
     *
     * @param allowLineBreaks If line breaks and tabs are allowed. Fields with line breaks are
     *                        not parsed as text, because the tag of field 1 of a message is a line break.
     * @return The text, or null if the bytes are not printable UTF-8 text.
     */
    @Nullable
    private static String decodeText(byte[] bytes, int start, int end, boolean allowLineBreaks) {
        if (start >= end) {
            return null;
        }

        // Fast check for control characters, which messages usually start with.
        for (int i = start; i < end; i++) {
            final byte b = bytes[i];
            if (((b >= 0 && b < 0x20) || b == 0x7F) && !(allowLineBreaks && isLineBreakOrTab(b))) {
                return null;
            }
        }

        // Well-formed UTF-8, as in the table of the Unicode standard: no overlong forms,
        // no surrogates and nothing above U+10FFFF.
        for (int i = start; i < end; ) {
            final int lead = bytes[i] & 0xFF;
            if (lead < 0x80) {
                i++;
                continue;
            }
            final int trailing;
            int secondMin = 0x80;
            int secondMax = 0xBF;
            if (lead >= 0xC2 && lead <= 0xDF) {
                trailing = 1;
            } else if (lead >= 0xE0 && lead <= 0xEF) {
                trailing = 2;
                if (lead == 0xE0) {
                    secondMin = 0xA0;
                } else if (lead == 0xED) {
                    secondMax = 0x9F;
                }
            } else if (lead >= 0xF0 && lead <= 0xF4) {
                trailing = 3;
                if (lead == 0xF0) {
                    secondMin = 0x90;
                } else if (lead == 0xF4) {
                    secondMax = 0x8F;
                }
            } else {
                return null;
            }
            if (i + trailing >= end) {
                return null;
            }
            final int second = bytes[i + 1] & 0xFF;
            // C2 80 to C2 9F are the control characters U+0080 to U+009F.
            if (second < secondMin || second > secondMax || (lead == 0xC2 && second <= 0x9F)) {
                return null;
            }
            for (int j = i + 2; j <= i + trailing; j++) {
                final int next = bytes[j] & 0xFF;
                if (next < 0x80 || next > 0xBF) {
                    return null;
                }
            }
            i += trailing + 1;
        }

        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }

    private static boolean isLineBreakOrTab(int c) {
        return c == '\n' || c == '\r' || c == '\t';
    }

    public int getFieldNumber() {
        return fieldNumber;
    }

    /**
     * @return If this field is a text.
     */
    public boolean isText() {
        return text != null;
    }

    /**
     * @return The text of a text field, such as the fields returned by {@link #textNodes(List)}.
     * @throws IllegalStateException If this field is not a text.
     */
    public String getText() {
        if (text == null) {
            throw new IllegalStateException("Not a text field: " + fieldNumber);
        }
        return text;
    }

    /**
     * @return The payload decoded as UTF-8, including text with line breaks
     *         that is not parsed as text.
     */
    public String decodeUtf8() {
        return text != null
                ? text
                : new String(bytes, valueStart, valueEnd - valueStart, StandardCharsets.UTF_8);
    }

    /**
     * @return The text of a length delimited field, including text with line breaks that is not
     *         parsed as text, or null if the payload is not printable UTF-8 text, such as other data.
     */
    @Nullable
    public String decodeText() {
        if (text != null) {
            return text;
        }
        if (!lengthDelimited) {
            return null;
        }
        return payload != null
                ? decodeText(payload, 0, payload.length, true)
                : decodeText(bytes, valueStart, valueEnd, true);
    }

    /**
     * @return The text of a field with line breaks, such as a post text, which is not parsed
     *         as text, or null if the field is not a text with line breaks.
     */
    @Nullable
    private String getMultiLineText() {
        if (text != null) {
            return null;
        }
        String decoded = decodeText();
        // A message starts with the tag of its first field, which can be a line break.
        return decoded != null && !isLineBreakOrTab(decoded.charAt(0)) && decoded.indexOf('\n') > 0
                ? decoded
                : null;
    }

    public void setText(String text) {
        this.text = text;
        children = null;
        payload = null;
        setModified();
    }

    /**
     * @return The bytes of a length delimited field, such as a message to parse with a schema.
     */
    public byte[] getPayload() {
        return payload != null
                ? payload
                : Arrays.copyOfRange(bytes, valueStart, valueEnd);
    }

    /**
     * Replaces the bytes of a length delimited field, such as with a modified message.
     */
    public void setPayload(byte[] payload) {
        this.payload = payload;
        text = null;
        children = null;
        setModified();
    }

    /**
     * @return The value, or null if this is not a varint field.
     */
    @Nullable
    public Long getVarint() {
        if (varint == null && !lengthDelimited && valueEnd - valueStart <= 10) {
            try {
                varint = CodedInputStream.newInstance(bytes, valueStart, valueEnd - valueStart)
                        .readRawVarint64();
            } catch (IOException ex) {
                return null;
            }
        }
        return varint;
    }

    public void setVarint(long value) {
        varint = value;
        setModified();
    }

    /**
     * @return The field that contains this field, or null if this is a field of the root message.
     */
    @Nullable
    public ProtoNode getParent() {
        return parent;
    }

    /**
     * Removes this field from its parent message.
     */
    public void remove() {
        List<ProtoNode> siblings = parent == null ? null : parent.children;
        if (siblings != null) {
            siblings.remove(this);
            parent.setModified();
        }
    }

    private void setModified() {
        for (ProtoNode node = this; node != null; node = node.parent) {
            node.modified = true;
        }
    }

    /**
     * @return The first field with the field number, or null if not found.
     */
    @Nullable
    public static ProtoNode field(@Nullable List<ProtoNode> message, int fieldNumber) {
        if (message == null) {
            return null;
        }
        for (ProtoNode node : message) {
            if (node.fieldNumber == fieldNumber) {
                return node;
            }
        }
        return null;
    }

    /**
     * @return The fields of the message at the field number path, or an empty list if there is no such message.
     */
    public static List<ProtoNode> message(List<ProtoNode> message, int... path) {
        for (int fieldNumber : path) {
            ProtoNode field = field(message, fieldNumber);
            if (field == null || field.children == null) {
                return List.of();
            }
            message = field.children;
        }
        return message;
    }

    public static byte[] write(List<ProtoNode> message) throws IOException {
        return encode(output -> write(message, output));
    }

    public interface Writer {
        void write(CodedOutputStream output) throws IOException;
    }

    /**
     * @return The encoded fields, such as fields to add to a message that is kept as bytes.
     */
    public static byte[] encode(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        writer.write(output);
        output.flush();
        return bytes.toByteArray();
    }

    public static byte[] encodeMessage(int fieldNumber, byte[] payload) throws IOException {
        return encode(output -> output.writeByteArray(fieldNumber, payload));
    }

    public static byte[] encodeString(int fieldNumber, String text) throws IOException {
        return encode(output -> output.writeString(fieldNumber, text));
    }

    public static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int position = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, position, part.length);
            position += part.length;
        }
        return result;
    }

    private static void write(List<ProtoNode> message, CodedOutputStream output) throws IOException {
        for (ProtoNode node : message) {
            List<ProtoNode> children = node.children;
            if (!node.modified) {
                output.writeRawBytes(node.bytes, node.tagStart, node.valueEnd - node.tagStart);
            } else if (node.payload != null) {
                output.writeByteArray(node.fieldNumber, node.payload);
            } else if (children != null) {
                output.writeByteArray(node.fieldNumber, write(children));
            } else if (node.text != null) {
                output.writeString(node.fieldNumber, node.text);
            } else if (node.varint != null) {
                output.writeUInt64(node.fieldNumber, node.varint);
            }
        }
    }

    /**
     * @return All texts of the message in order, including the texts with line breaks,
     *         which are not parsed as text fields.
     */
    public static List<String> texts(List<ProtoNode> message) {
        List<String> texts = new ArrayList<>();
        addTexts(message, texts);
        return texts;
    }

    private static void addTexts(List<ProtoNode> message, List<String> texts) {
        for (ProtoNode node : message) {
            if (node.text != null) {
                texts.add(node.text);
                continue;
            }
            if (node.children != null) {
                addTexts(node.children, texts);
            }
            String multiLineText = node.getMultiLineText();
            if (multiLineText != null) {
                texts.add(multiLineText);
            }
        }
    }

    /**
     * @return All text fields of the message, in order.
     */
    public static List<ProtoNode> textNodes(List<ProtoNode> message) {
        List<ProtoNode> textNodes = new ArrayList<>();
        addTextNodes(message, textNodes);
        return textNodes;
    }

    private static void addTextNodes(List<ProtoNode> message, List<ProtoNode> textNodes) {
        for (ProtoNode node : message) {
            List<ProtoNode> children = node.children;
            if (children != null) {
                addTextNodes(children, textNodes);
            } else if (node.text != null) {
                textNodes.add(node);
            }
        }
    }

    /**
     * @return The messages whose field numbers from the root message end with the suffix.
     *         Messages inside matching messages are not included.
     */
    public static List<ProtoNode> findMessages(List<ProtoNode> message, int... pathSuffix) {
        List<ProtoNode> messages = new ArrayList<>();
        addMessages(message, pathSuffix, messages);
        return messages;
    }

    private static void addMessages(List<ProtoNode> message, int[] pathSuffix, List<ProtoNode> messages) {
        for (ProtoNode node : message) {
            List<ProtoNode> children = node.children;
            if (children == null) {
                continue;
            }
            if (node.pathEndsWith(pathSuffix)) {
                messages.add(node);
            } else {
                addMessages(children, pathSuffix, messages);
            }
        }
    }

    /**
     * @return If the field numbers from the root message to this field end with the suffix.
     */
    public boolean pathEndsWith(int... suffix) {
        ProtoNode node = this;
        for (int i = suffix.length - 1; i >= 0; i--) {
            if (node == null || node.fieldNumber != suffix[i]) {
                return false;
            }
            node = node.parent;
        }
        return true;
    }
}
