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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Logger;

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

    /** Decoders are not thread safe, and elements are parsed on many threads. */
    private static final ThreadLocal<CharsetDecoder> UTF_8_DECODER = ThreadLocal.withInitial(() ->
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT));

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
        if (depth > MAX_DEPTH || start >= end) {
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

        try {
            String text = UTF_8_DECODER.get().decode(ByteBuffer.wrap(bytes, start, end - start)).toString();
            for (int i = 0, length = text.length(); i < length; i++) {
                final char c = text.charAt(i);
                if (Character.isISOControl(c) && !(allowLineBreaks && isLineBreakOrTab(c))) {
                    return null;
                }
            }
            return text;
        } catch (CharacterCodingException ex) {
            Logger.printDebug(() -> "Could not decode bytes: " +
                    Arrays.toString(Arrays.copyOfRange(bytes, start, end)), ex);
            return null;
        }
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
    public static ProtoNode field(List<ProtoNode> message, int fieldNumber) {
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
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        write(message, output);
        output.flush();
        return bytes.toByteArray();
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
