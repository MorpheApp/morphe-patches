/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

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
import java.util.List;

/**
 * Field of a proto message parsed without a schema.
 * <p>
 * Length delimited fields are parsed as text if the payload is printable UTF-8,
 * otherwise as a message if possible, otherwise kept as bytes.
 * Only text and varints can be modified, and fields can be removed.
 * All other fields are written back unchanged.
 * Fields reference the parsed bytes instead of copying them.
 */
final class ProtoNode {

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
    List<ProtoNode> children;
    @Nullable
    String text;
    @Nullable
    private Long varint;
    /**
     * If the text of this field or a sub field is modified.
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
    static List<ProtoNode> parse(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return parse(bytes, 0, bytes.length, null, 0, decoder);
    }

    @Nullable
    private static List<ProtoNode> parse(byte[] bytes, int start, int end, @Nullable ProtoNode parent,
                                         int depth, CharsetDecoder decoder) {
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
                    node.text = decodeText(bytes, valueStart, valueEnd, decoder);
                    if (node.text == null) {
                        node.children = parse(bytes, valueStart, valueEnd, node, depth + 1, decoder);
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
     * @return The text, or null if the bytes are not printable UTF-8 text.
     */
    @Nullable
    private static String decodeText(byte[] bytes, int start, int end, CharsetDecoder decoder) {
        if (start >= end) {
            return null;
        }

        // Fast check for control characters, which messages usually start with.
        for (int i = start; i < end; i++) {
            final byte b = bytes[i];
            if ((b >= 0 && b < 0x20) || b == 0x7F) {
                return null;
            }
        }

        try {
            String text = decoder.decode(ByteBuffer.wrap(bytes, start, end - start)).toString();
            for (int i = 0, length = text.length(); i < length; i++) {
                if (Character.isISOControl(text.charAt(i))) {
                    return null;
                }
            }
            return text;
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    /**
     * @return The payload decoded as UTF-8, including text with line breaks
     *         that is not parsed as text.
     */
    String decodeUtf8() {
        return text != null
                ? text
                : new String(bytes, valueStart, valueEnd - valueStart, StandardCharsets.UTF_8);
    }

    void setText(String text) {
        this.text = text;
        children = null;
        setModified();
    }

    /**
     * @return The value, or null if this is not a varint field.
     */
    @Nullable
    Long getVarint() {
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

    void setVarint(long value) {
        varint = value;
        setModified();
    }

    /**
     * Removes this field from its parent message.
     */
    void remove() {
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
    static ProtoNode field(List<ProtoNode> message, int fieldNumber) {
        for (ProtoNode node : message) {
            if (node.fieldNumber == fieldNumber) {
                return node;
            }
        }
        return null;
    }

    static byte[] write(List<ProtoNode> message) throws IOException {
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
            } else if (children != null) {
                output.writeByteArray(node.fieldNumber, write(children));
            } else if (node.text != null) {
                output.writeString(node.fieldNumber, node.text);
            } else {
                output.writeUInt64(node.fieldNumber, node.varint);
            }
        }
    }

    /**
     * @return All text fields of the message, in order.
     */
    static List<ProtoNode> textNodes(List<ProtoNode> message) {
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
    static List<ProtoNode> findMessages(List<ProtoNode> message, int... pathSuffix) {
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
    boolean pathEndsWith(int... suffix) {
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
