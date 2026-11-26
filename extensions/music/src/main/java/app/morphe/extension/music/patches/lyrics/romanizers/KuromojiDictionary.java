/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Memory-mapped binary IPADIC dictionary compatible with upstream {@code kuromoji@0.1.2}
 * ({@code base.dat}, {@code check.dat}, {@code tid*.dat}, {@code cc.dat}, {@code unk*.dat}).
 *
 * <p>All twelve upstream tables (with {@code tid_map.dat} pre-sorted into flat {@code int32}
 * key/value sections for zero-heap binary search) are packed into a single 4-byte-aligned
 * little-endian file so runtime tokenization runs directly off OS page cache with near-zero
 * Java heap footprint.
 */
public final class KuromojiDictionary {

    static final byte[] MAGIC = {'M', 'K', 'R', 'M', '0', '0', '0', '1'};
    static final int SECTION_COUNT = 13;
    private static final int HEADER_BYTES = 8 + 4 + 4 + SECTION_COUNT * 12;

    static final int SEC_BASE = 0;
    static final int SEC_CHECK = 1;
    static final int SEC_TID = 2;
    static final int SEC_TID_POS = 3;
    static final int SEC_TID_MAP_KEYS = 4;
    static final int SEC_TID_MAP_VALS = 5;
    static final int SEC_CC = 6;
    static final int SEC_UNK = 7;
    static final int SEC_UNK_POS = 8;
    static final int SEC_UNK_MAP = 9;
    static final int SEC_UNK_CHAR = 10;
    static final int SEC_UNK_COMPAT = 11;
    static final int SEC_UNK_INVOKE = 12;

    private static final int ROOT_ID = 0;
    private static final int TERM_CODE = 0;
    private static final int NOT_FOUND = -1;
    private static final int[] EMPTY_INTS = new int[0];

    public record CharacterClass(
            int classId,
            String className,
            boolean isAlwaysInvoke,
            boolean isGrouping,
            int maxLength) {
    }

    public record PrefixMatch(int trieId, int codePointLength) {
    }

    private final int entryCount;
    private final IntBuffer base;
    private final IntBuffer check;
    private final ByteBuffer tid;
    private final ByteBuffer tidPos;
    private final IntBuffer tidMapKeys;
    private final int tidMapKeyCount;
    private final IntBuffer tidMapVals;
    private final ShortBuffer cc;
    private final int ccBackwardDim;
    private final ByteBuffer unk;
    private final ByteBuffer unkPos;
    private final int[][] unkTargetMap;
    private final ByteBuffer unkChar;
    @SuppressWarnings("unused")
    private final IntBuffer unkCompat;
    private final CharacterClass[] invokeClasses;
    private final CharacterClass defaultClass;

    private KuromojiDictionary(
            int entryCount,
            IntBuffer base,
            IntBuffer check,
            ByteBuffer tid,
            ByteBuffer tidPos,
            IntBuffer tidMapKeys,
            IntBuffer tidMapVals,
            ShortBuffer cc,
            ByteBuffer unk,
            ByteBuffer unkPos,
            int[][] unkTargetMap,
            ByteBuffer unkChar,
            IntBuffer unkCompat,
            CharacterClass[] invokeClasses,
            CharacterClass defaultClass) {
        this.entryCount = entryCount;
        this.base = base;
        this.check = check;
        this.tid = tid;
        this.tidPos = tidPos;
        this.tidMapKeys = tidMapKeys;
        this.tidMapKeyCount = tidMapKeys.limit() / 3;
        this.tidMapVals = tidMapVals;
        this.cc = cc;
        this.ccBackwardDim = cc.limit() >= 2 ? (cc.get(1) & 0xFFFF) : 0;
        this.unk = unk;
        this.unkPos = unkPos;
        this.unkTargetMap = unkTargetMap;
        this.unkChar = unkChar;
        this.unkCompat = unkCompat;
        this.invokeClasses = invokeClasses;
        this.defaultClass = defaultClass;
    }

    public int size() {
        return entryCount;
    }

    /**
     * Packs the 12 raw decompressed Kuromoji {@code .dat} byte arrays into a single
     * memory-mappable binary file.
     */
    public static int writePackedFile(
            File outFile,
            byte[] baseBytes,
            byte[] checkBytes,
            byte[] tidBytes,
            byte[] tidPosBytes,
            byte[] tidMapBytes,
            byte[] ccBytes,
            byte[] unkBytes,
            byte[] unkPosBytes,
            byte[] unkMapBytes,
            byte[] unkCharBytes,
            byte[] unkCompatBytes,
            byte[] unkInvokeBytes) throws IOException {
        PackedMap sortedTidMap = packTargetMap(tidMapBytes);

        byte[][] sections = new byte[SECTION_COUNT][];
        sections[SEC_BASE] = baseBytes;
        sections[SEC_CHECK] = checkBytes;
        sections[SEC_TID] = tidBytes;
        sections[SEC_TID_POS] = tidPosBytes;
        sections[SEC_TID_MAP_KEYS] = sortedTidMap.keysBytes;
        sections[SEC_TID_MAP_VALS] = sortedTidMap.valsBytes;
        sections[SEC_CC] = ccBytes;
        sections[SEC_UNK] = unkBytes;
        sections[SEC_UNK_POS] = unkPosBytes;
        sections[SEC_UNK_MAP] = unkMapBytes;
        sections[SEC_UNK_CHAR] = unkCharBytes;
        sections[SEC_UNK_COMPAT] = unkCompatBytes;
        sections[SEC_UNK_INVOKE] = unkInvokeBytes;

        int[] lengths = new int[SECTION_COUNT];
        for (int i = 0; i < SECTION_COUNT; i++) {
            lengths[i] = sections[i].length;
        }
        long[] offsets = computeSectionOffsets(lengths);
        byte[] header = buildHeader(sortedTidMap.entryCount, offsets, lengths);

        byte[] pad = new byte[4];
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(header);
            long written = header.length;
            for (int i = 0; i < SECTION_COUNT; i++) {
                if (written != offsets[i]) {
                    throw new IOException("misaligned section " + i);
                }
                fos.write(sections[i]);
                written = writeAlignPad(fos, written + lengths[i], pad);
            }
            fos.flush();
            fos.getFD().sync();
        }
        return sortedTidMap.entryCount;
    }

    /**
     * Streaming variant of {@link #writePackedFile} that reads the 12 decompressed {@code .dat}
     * section files from disk so large tables never sit simultaneously on the Java heap.
     */
    public static int writePackedFromFiles(File outFile, File[] rawFiles) throws IOException {
        if (rawFiles == null || rawFiles.length != 12) {
            throw new IOException("expected 12 Kuromoji section files");
        }
        byte[] tidMapBytes = readAllBytesBounded(rawFiles[4], 32L * 1024L * 1024L);
        PackedMap sortedTidMap = packTargetMap(tidMapBytes);

        // Map 12 input files to 13 packed sections (tid_map splits into sorted keys + vals).
        int[] lengths = new int[SECTION_COUNT];
        File[] fileSources = new File[SECTION_COUNT];
        byte[][] memorySources = new byte[SECTION_COUNT][];

        int[] inputToSection = {
                SEC_BASE, SEC_CHECK, SEC_TID, SEC_TID_POS, -1,
                SEC_CC, SEC_UNK, SEC_UNK_POS, SEC_UNK_MAP, SEC_UNK_CHAR, SEC_UNK_COMPAT, SEC_UNK_INVOKE
        };
        for (int i = 0; i < rawFiles.length; i++) {
            int sec = inputToSection[i];
            if (sec < 0) continue;
            long len = rawFiles[i].length();
            if (len < 0 || len > Integer.MAX_VALUE) {
                throw new IOException("section file too large: " + rawFiles[i]);
            }
            fileSources[sec] = rawFiles[i];
            lengths[sec] = (int) len;
        }
        memorySources[SEC_TID_MAP_KEYS] = sortedTidMap.keysBytes;
        lengths[SEC_TID_MAP_KEYS] = sortedTidMap.keysBytes.length;
        memorySources[SEC_TID_MAP_VALS] = sortedTidMap.valsBytes;
        lengths[SEC_TID_MAP_VALS] = sortedTidMap.valsBytes.length;

        long[] offsets = computeSectionOffsets(lengths);
        byte[] header = buildHeader(sortedTidMap.entryCount, offsets, lengths);

        byte[] copyBuf = new byte[64 * 1024];
        byte[] pad = new byte[4];
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(header);
            long written = header.length;
            for (int i = 0; i < SECTION_COUNT; i++) {
                if (written != offsets[i]) {
                    throw new IOException("misaligned section " + i);
                }
                if (memorySources[i] != null) {
                    fos.write(memorySources[i]);
                    written += lengths[i];
                } else {
                    long copied = 0;
                    try (InputStream in = new FileInputStream(fileSources[i])) {
                        int n;
                        while ((n = in.read(copyBuf)) != -1) {
                            fos.write(copyBuf, 0, n);
                            copied += n;
                        }
                    }
                    if (copied != lengths[i]) {
                        throw new IOException("section length changed during pack: " + i);
                    }
                    written += copied;
                }
                written = writeAlignPad(fos, written, pad);
            }
            fos.flush();
            fos.getFD().sync();
        }
        return sortedTidMap.entryCount;
    }

    private static long[] computeSectionOffsets(int[] lengths) {
        long[] offsets = new long[SECTION_COUNT];
        long cursor = align4(HEADER_BYTES);
        for (int i = 0; i < SECTION_COUNT; i++) {
            offsets[i] = cursor;
            cursor = align4(cursor + lengths[i]);
        }
        return offsets;
    }

    private static byte[] buildHeader(int entryCount, long[] offsets, int[] lengths) {
        ByteBuffer header = ByteBuffer.allocate((int) align4(HEADER_BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        header.put(MAGIC);
        header.putInt(entryCount);
        header.putInt(SECTION_COUNT);
        for (int i = 0; i < SECTION_COUNT; i++) {
            header.putLong(offsets[i]);
            header.putInt(lengths[i]);
        }
        while (header.position() < header.capacity()) {
            header.put((byte) 0);
        }
        return header.array();
    }

    private static long writeAlignPad(FileOutputStream fos, long written, byte[] pad) throws IOException {
        long aligned = align4(written);
        int padLen = (int) (aligned - written);
        if (padLen > 0) {
            fos.write(pad, 0, padLen);
            return aligned;
        }
        return written;
    }

    private static byte[] readAllBytesBounded(File file, long maxBytes) throws IOException {
        long len = file.length();
        if (len < 0 || len > maxBytes) {
            throw new IOException("file size out of bounds: " + file);
        }
        byte[] out = new byte[(int) len];
        try (InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < out.length) {
                int r = in.read(out, offset, out.length - offset);
                if (r < 0) break;
                offset += r;
            }
            if (offset != out.length) {
                throw new IOException("truncated read on " + file);
            }
        }
        return out;
    }

    /** Opens a packed Kuromoji binary file via {@link MappedByteBuffer}. */
    public static KuromojiDictionary open(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel ch = raf.getChannel()) {
            long fileLen = ch.size();
            if (fileLen < HEADER_BYTES || fileLen > Integer.MAX_VALUE) {
                throw new IOException("invalid kuromoji file size: " + fileLen);
            }
            MappedByteBuffer mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, fileLen);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            return fromBuffer(mapped);
        }
    }

    /** Parses a packed Kuromoji binary directly from a {@link ByteBuffer} (also useful in tests). */
    public static KuromojiDictionary fromBuffer(ByteBuffer buffer) throws IOException {
        ByteBuffer root = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (root.remaining() < HEADER_BYTES) {
            throw new IOException("truncated kuromoji header");
        }
        for (byte expected : MAGIC) {
            if (root.get() != expected) {
                throw new IOException("kuromoji magic mismatch");
            }
        }
        int entryCount = root.getInt();
        int sectionCount = root.getInt();
        if (sectionCount != SECTION_COUNT) {
            throw new IOException("unexpected kuromoji section count: " + sectionCount);
        }

        ByteBuffer[] slices = new ByteBuffer[SECTION_COUNT];
        for (int i = 0; i < SECTION_COUNT; i++) {
            long offset = root.getLong();
            int length = root.getInt();
            if (offset < HEADER_BYTES || length < 0 || offset + length > root.capacity()) {
                throw new IOException("invalid kuromoji section bounds at " + i);
            }
            ByteBuffer dup = root.duplicate();
            dup.position((int) offset);
            dup.limit((int) offset + length);
            slices[i] = dup.slice().order(ByteOrder.LITTLE_ENDIAN);
        }

        CharacterClass[] invokeClasses = parseInvokeClasses(slices[SEC_UNK_INVOKE]);
        CharacterClass defaultClass = findDefaultClass(invokeClasses);
        int[][] unkTargetMap = parseUnkTargetMap(slices[SEC_UNK_MAP], invokeClasses.length);

        return new KuromojiDictionary(
                entryCount,
                slices[SEC_BASE].asIntBuffer(),
                slices[SEC_CHECK].asIntBuffer(),
                slices[SEC_TID],
                slices[SEC_TID_POS],
                slices[SEC_TID_MAP_KEYS].asIntBuffer(),
                slices[SEC_TID_MAP_VALS].asIntBuffer(),
                slices[SEC_CC].asShortBuffer(),
                slices[SEC_UNK],
                slices[SEC_UNK_POS],
                unkTargetMap,
                slices[SEC_UNK_CHAR],
                slices[SEC_UNK_COMPAT].asIntBuffer(),
                invokeClasses,
                defaultClass);
    }

    /**
     * Common-prefix search over Unicode code points starting at {@code startCpIdx}.
     * Traverses UTF-8 bytes directly without allocating intermediate substrings.
     */
    public List<PrefixMatch> commonPrefixSearch(int[] codePoints, int startCpIdx) {
        List<PrefixMatch> matches = null;
        int node = ROOT_ID;
        for (int i = startCpIdx; i < codePoints.length; i++) {
            int cp = codePoints[i];
            if (cp < 0x80) {
                node = traverse(node, cp);
                if (node == NOT_FOUND) break;
            } else if (cp < 0x800) {
                node = traverse(node, 0xC0 | (cp >>> 6));
                if (node == NOT_FOUND) break;
                node = traverse(node, 0x80 | (cp & 0x3F));
                if (node == NOT_FOUND) break;
            } else if (cp < 0x10000) {
                node = traverse(node, 0xE0 | (cp >>> 12));
                if (node == NOT_FOUND) break;
                node = traverse(node, 0x80 | ((cp >>> 6) & 0x3F));
                if (node == NOT_FOUND) break;
                node = traverse(node, 0x80 | (cp & 0x3F));
                if (node == NOT_FOUND) break;
            } else {
                node = traverse(node, 0xF0 | (cp >>> 18));
                if (node == NOT_FOUND) break;
                node = traverse(node, 0x80 | ((cp >>> 12) & 0x3F));
                if (node == NOT_FOUND) break;
                node = traverse(node, 0x80 | ((cp >>> 6) & 0x3F));
                if (node == NOT_FOUND) break;
                node = traverse(node, 0x80 | (cp & 0x3F));
                if (node == NOT_FOUND) break;
            }

            int termNode = traverse(node, TERM_CODE);
            if (termNode != NOT_FOUND) {
                int b = base.get(termNode);
                if (b <= 0) {
                    int trieId = -b - 1;
                    if (matches == null) {
                        matches = new ArrayList<>(4);
                    }
                    matches.add(new PrefixMatch(trieId, i - startCpIdx + 1));
                }
            }
        }
        return matches != null ? matches : List.of();
    }

    private int traverse(int parent, int unsignedByte) {
        if (parent < 0 || parent >= base.limit()) {
            return NOT_FOUND;
        }
        int child = base.get(parent) + unsignedByte;
        if (child < 0 || child >= check.limit()) {
            return NOT_FOUND;
        }
        return check.get(child) == parent ? child : NOT_FOUND;
    }

    /** Looks up {@code tokenInfoId} byte offsets in {@code tid.dat} for {@code trieId}. */
    public int[] getTargetIds(int trieId) {
        int low = 0;
        int high = tidMapKeyCount - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midBase = mid * 3;
            int midKey = tidMapKeys.get(midBase);
            if (midKey < trieId) {
                low = mid + 1;
            } else if (midKey > trieId) {
                high = mid - 1;
            } else {
                int start = tidMapKeys.get(midBase + 1);
                int count = tidMapKeys.get(midBase + 2);
                if (count <= 0) return EMPTY_INTS;
                int[] ids = new int[count];
                for (int i = 0; i < count; i++) {
                    ids[i] = tidMapVals.get(start + i);
                }
                return ids;
            }
        }
        return EMPTY_INTS;
    }

    public int[] getUnkTargetIds(int classId) {
        if (classId < 0 || classId >= unkTargetMap.length) {
            return EMPTY_INTS;
        }
        int[] ids = unkTargetMap[classId];
        return ids != null ? ids : EMPTY_INTS;
    }

    public short knownLeftId(int tokenInfoId) {
        return tid.getShort(tokenInfoId);
    }

    public short knownRightId(int tokenInfoId) {
        return tid.getShort(tokenInfoId + 2);
    }

    public short knownWordCost(int tokenInfoId) {
        return tid.getShort(tokenInfoId + 4);
    }

    public int knownPosOffset(int tokenInfoId) {
        return tid.getInt(tokenInfoId + 6);
    }

    public short unkLeftId(int unkId) {
        return unk.getShort(unkId);
    }

    public short unkRightId(int unkId) {
        return unk.getShort(unkId + 2);
    }

    public short unkWordCost(int unkId) {
        return unk.getShort(unkId + 4);
    }

    public int unkPosOffset(int unkId) {
        return unk.getInt(unkId + 6);
    }

    public int connectionCost(int prevRightId, int curLeftId) {
        int index = prevRightId * ccBackwardDim + curLeftId + 2;
        if (index < 2 || index >= cc.limit()) {
            return 0;
        }
        return cc.get(index);
    }

    public CharacterClass lookupCharClass(int codePoint) {
        if (codePoint >= 0 && codePoint < unkChar.limit()) {
            int classId = unkChar.get(codePoint) & 0xFF;
            if (classId >= 0 && classId < invokeClasses.length) {
                return invokeClasses[classId];
            }
        }
        return defaultClass;
    }

    public String readKnownFeatures(int posOffset) {
        return readNullTerminatedUtf8(tidPos, posOffset);
    }

    public String readUnkFeatures(int posOffset) {
        return readNullTerminatedUtf8(unkPos, posOffset);
    }

    private static String readNullTerminatedUtf8(ByteBuffer buf, int offset) {
        if (offset < 0 || offset >= buf.limit()) {
            return "";
        }
        int end = offset;
        int limit = buf.limit();
        while (end < limit && buf.get(end) != 0) {
            end++;
        }
        int len = end - offset;
        if (len <= 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        ByteBuffer dup = buf.duplicate();
        dup.position(offset);
        dup.get(bytes, 0, len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static CharacterClass[] parseInvokeClasses(ByteBuffer buf) {
        List<CharacterClass> list = new ArrayList<>(24);
        int pos = 0;
        int limit = buf.limit();
        while (pos + 1 < limit) {
            if (pos + 6 > limit) break;
            int classId = list.size();
            boolean isAlwaysInvoke = buf.get(pos) == 1;
            boolean isGrouping = buf.get(pos + 1) == 1;
            int maxLength = buf.getInt(pos + 2);
            pos += 6;
            int strStart = pos;
            while (pos < limit && buf.get(pos) != 0) {
                pos++;
            }
            String name = "";
            if (pos > strStart) {
                byte[] bytes = new byte[pos - strStart];
                ByteBuffer dup = buf.duplicate();
                dup.position(strStart);
                dup.get(bytes);
                name = new String(bytes, StandardCharsets.UTF_8);
            }
            if (pos < limit && buf.get(pos) == 0) {
                pos++;
            }
            list.add(new CharacterClass(classId, name, isAlwaysInvoke, isGrouping, maxLength));
        }
        return list.toArray(new CharacterClass[0]);
    }

    private static CharacterClass findDefaultClass(CharacterClass[] classes) {
        for (CharacterClass c : classes) {
            if ("DEFAULT".equals(c.className())) {
                return c;
            }
        }
        return classes.length > 0
                ? classes[0]
                : new CharacterClass(0, "DEFAULT", false, true, 0);
    }

    private static int[][] parseUnkTargetMap(ByteBuffer buf, int classCount) {
        int[][] map = new int[Math.max(classCount, 32)][];
        if (buf.limit() < 4) {
            return map;
        }
        ByteBuffer dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int mapKeysSize = dup.getInt();
        if (mapKeysSize < 0) {
            return map;
        }
        for (int i = 0; i < mapKeysSize && dup.remaining() >= 8; i++) {
            int key = dup.getInt();
            int valSize = dup.getInt();
            if (valSize < 0 || (long) valSize * 4L > dup.remaining()) break;
            int[] vals = new int[valSize];
            for (int j = 0; j < valSize; j++) {
                vals[j] = dup.getInt();
            }
            if (key >= 0) {
                if (key >= map.length) {
                    map = Arrays.copyOf(map, key + 8);
                }
                map[key] = vals;
            }
        }
        return map;
    }

    private record PackedMap(byte[] keysBytes, byte[] valsBytes, int entryCount) {
    }

    static PackedMap packTargetMap(byte[] rawTidMap) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(rawTidMap).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.remaining() < 4) {
            throw new IOException("truncated tid_map.dat");
        }
        int mapKeysSize = bb.getInt();
        if (mapKeysSize < 0 || (long) mapKeysSize * 8L > bb.remaining()) {
            throw new IOException("invalid tid_map key count: " + mapKeysSize);
        }
        long[] keyIndexPairs = new long[mapKeysSize];
        int[][] valLists = new int[mapKeysSize][];
        int totalVals = 0;
        for (int i = 0; i < mapKeysSize; i++) {
            if (bb.remaining() < 8) {
                throw new IOException("truncated tid_map entry at " + i);
            }
            int key = bb.getInt();
            int valSize = bb.getInt();
            if (valSize < 0 || (long) valSize * 4L > bb.remaining()) {
                throw new IOException("invalid tid_map value list at " + i);
            }
            int[] vals = new int[valSize];
            for (int j = 0; j < valSize; j++) {
                vals[j] = bb.getInt();
            }
            keyIndexPairs[i] = (((long) key) << 32) | (i & 0xFFFFFFFFL);
            valLists[i] = vals;
            totalVals += valSize;
        }
        Arrays.sort(keyIndexPairs);

        ByteBuffer keysBuf = ByteBuffer.allocate(mapKeysSize * 12).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer valsBuf = ByteBuffer.allocate(totalVals * 4).order(ByteOrder.LITTLE_ENDIAN);
        int valCursor = 0;
        for (int i = 0; i < mapKeysSize; i++) {
            long pair = keyIndexPairs[i];
            int key = (int) (pair >> 32);
            int origIdx = (int) pair;
            int[] vals = valLists[origIdx];
            keysBuf.putInt(key);
            keysBuf.putInt(valCursor);
            keysBuf.putInt(vals.length);
            for (int v : vals) {
                valsBuf.putInt(v);
            }
            valCursor += vals.length;
        }
        return new PackedMap(keysBuf.array(), valsBuf.array(), totalVals);
    }

    private static long align4(long value) {
        return (value + 3L) & ~3L;
    }

    /**
     * Builds a tiny synthetic {@link KuromojiDictionary} in memory for unit tests without
     * downloading upstream IPADIC files.
     */
    public record TestLexeme(
            String surface,
            short leftId,
            short rightId,
            short cost,
            String pos,
            String posDetail1,
            String basicForm,
            String reading,
            String pronunciation) {
    }

    @Nullable
    static KuromojiDictionary buildSyntheticForTest(List<TestLexeme> lexemes) throws IOException {
        // Build a simple uncompressed byte-trie mapped into Double-Array (base/check).
        final class TrieNode {
            final int[] children = new int[256];
            int terminalTrieId = -1;

            TrieNode() {
                Arrays.fill(children, -1);
            }
        }
        List<TrieNode> nodes = new ArrayList<>();
        nodes.add(new TrieNode());

        List<String> uniqueSurfaces = new ArrayList<>();
        List<List<Integer>> surfaceToTokenInfoOffsets = new ArrayList<>();

        ByteArrayOutputStream tidOut = new ByteArrayOutputStream();
        ByteArrayOutputStream tidPosOut = new ByteArrayOutputStream();

        for (TestLexeme lex : lexemes) {
            int surfIdx = uniqueSurfaces.indexOf(lex.surface());
            if (surfIdx < 0) {
                surfIdx = uniqueSurfaces.size();
                uniqueSurfaces.add(lex.surface());
                surfaceToTokenInfoOffsets.add(new ArrayList<>());

                byte[] utf8 = lex.surface().getBytes(StandardCharsets.UTF_8);
                int cur = 0;
                for (byte b : utf8) {
                    int code = b & 0xFF;
                    int next = nodes.get(cur).children[code];
                    if (next < 0) {
                        next = nodes.size();
                        nodes.add(new TrieNode());
                        nodes.get(cur).children[code] = next;
                    }
                    cur = next;
                }
                nodes.get(cur).terminalTrieId = surfIdx;
            }

            int posOffset = tidPosOut.size();
            String featureLine = lex.surface() + "," + lex.pos() + "," + lex.posDetail1()
                    + ",*,*,*,*," + lex.basicForm() + "," + lex.reading() + "," + lex.pronunciation();
            tidPosOut.write(featureLine.getBytes(StandardCharsets.UTF_8));
            tidPosOut.write(0);

            int tokenInfoOffset = tidOut.size();
            ByteBuffer entry = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
            entry.putShort(lex.leftId());
            entry.putShort(lex.rightId());
            entry.putShort(lex.cost());
            entry.putInt(posOffset);
            tidOut.write(entry.array());
            surfaceToTokenInfoOffsets.get(surfIdx).add(tokenInfoOffset);
        }

        // Convert tree into Double-Array (base/check) using kuromoji's exact convention:
        // traverse(parent, code): child = base[parent] + code, check[child] == parent.
        // Terminal at code == 0: base[termChild] = -trieId - 1, check[termChild] == parent.
        int[] baseArr = new int[4096];
        int[] checkArr = new int[4096];
        boolean[] used = new boolean[4096];
        Arrays.fill(checkArr, -1);
        used[0] = true;
        checkArr[0] = 0;

        int[] nodeSlot = new int[nodes.size()];
        nodeSlot[0] = 0;

        // BFS placement
        int[] queue = new int[nodes.size()];
        int qHead = 0;
        int qTail = 0;
        queue[qTail++] = 0;

        while (qHead < qTail) {
            int treeIdx = queue[qHead++];
            TrieNode tn = nodes.get(treeIdx);
            int daParent = nodeSlot[treeIdx];

            List<Integer> codes = new ArrayList<>();
            if (tn.terminalTrieId >= 0) {
                codes.add(0);
            }
            for (int c = 1; c < 256; c++) {
                if (tn.children[c] >= 0) {
                    codes.add(c);
                }
            }
            if (codes.isEmpty()) continue;

            int bVal = 1;
            searchBase:
            while (true) {
                int maxChild = bVal + codes.get(codes.size() - 1);
                if (maxChild >= used.length) {
                    int newLen = Math.max(used.length * 2, maxChild + 512);
                    baseArr = Arrays.copyOf(baseArr, newLen);
                    int oldLen = checkArr.length;
                    checkArr = Arrays.copyOf(checkArr, newLen);
                    Arrays.fill(checkArr, oldLen, newLen, -1);
                    used = Arrays.copyOf(used, newLen);
                }
                for (int code : codes) {
                    if (used[bVal + code]) {
                        bVal++;
                        continue searchBase;
                    }
                }
                break;
            }

            baseArr[daParent] = bVal;
            for (int code : codes) {
                int childSlot = bVal + code;
                used[childSlot] = true;
                checkArr[childSlot] = daParent;
                if (code == 0) {
                    baseArr[childSlot] = -tn.terminalTrieId - 1;
                } else {
                    int childTreeIdx = tn.children[code];
                    nodeSlot[childTreeIdx] = childSlot;
                    queue[qTail++] = childTreeIdx;
                }
            }
        }

        ByteBuffer baseBuf = ByteBuffer.allocate(baseArr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer checkBuf = ByteBuffer.allocate(checkArr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < baseArr.length; i++) {
            baseBuf.putInt(baseArr[i]);
            checkBuf.putInt(checkArr[i]);
        }

        // tid_map.dat
        ByteArrayOutputStream tidMapOut = new ByteArrayOutputStream();
        ByteBuffer u32 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        u32.putInt(0, uniqueSurfaces.size());
        tidMapOut.write(u32.array());
        for (int i = 0; i < uniqueSurfaces.size(); i++) {
            List<Integer> vals = surfaceToTokenInfoOffsets.get(i);
            u32.putInt(0, i);
            tidMapOut.write(u32.array());
            u32.putInt(0, vals.size());
            tidMapOut.write(u32.array());
            for (int v : vals) {
                u32.putInt(0, v);
                tidMapOut.write(u32.array());
            }
        }

        // cc.dat (4x4 zero connection matrix)
        ByteBuffer ccBuf = ByteBuffer.allocate((2 + 16) * 2).order(ByteOrder.LITTLE_ENDIAN);
        ccBuf.putShort((short) 4);
        ccBuf.putShort((short) 4);

        // Minimal unknown dictionary (0: DEFAULT, 1: HIRAGANA, 2: KATAKANA, 3: KANJI, 4: ALPHA)
        String[] classes = {"DEFAULT", "HIRAGANA", "KATAKANA", "KANJI", "ALPHA"};
        boolean[] grouping = {true, false, true, false, true};
        ByteArrayOutputStream invokeOut = new ByteArrayOutputStream();
        ByteArrayOutputStream unkOut = new ByteArrayOutputStream();
        ByteArrayOutputStream unkPosOut = new ByteArrayOutputStream();
        ByteArrayOutputStream unkMapOut = new ByteArrayOutputStream();
        u32.putInt(0, classes.length);
        unkMapOut.write(u32.array());
        for (int c = 0; c < classes.length; c++) {
            invokeOut.write(0);
            invokeOut.write(grouping[c] ? 1 : 0);
            u32.putInt(0, 0);
            invokeOut.write(u32.array());
            invokeOut.write(classes[c].getBytes(StandardCharsets.UTF_8));
            invokeOut.write(0);

            int unkPosOff = unkPosOut.size();
            String unkLine = classes[c] + ",名詞,一般,*,*,*,*,*";
            unkPosOut.write(unkLine.getBytes(StandardCharsets.UTF_8));
            unkPosOut.write(0);

            int unkOff = unkOut.size();
            ByteBuffer unkEntry = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
            unkEntry.putShort((short) 0);
            unkEntry.putShort((short) 0);
            unkEntry.putShort((short) 8000);
            unkEntry.putInt(unkPosOff);
            unkOut.write(unkEntry.array());

            u32.putInt(0, c);
            unkMapOut.write(u32.array());
            u32.putInt(0, 1);
            unkMapOut.write(u32.array());
            u32.putInt(0, unkOff);
            unkMapOut.write(u32.array());
        }

        byte[] unkChar = new byte[65536];
        for (int cp = 0; cp < 65536; cp++) {
            char ch = (char) cp;
            if (ch >= 'ぁ' && ch <= 'ゖ') unkChar[cp] = 1;
            else if ((ch >= 'ァ' && ch <= 'ヺ') || ch == 'ー') unkChar[cp] = 2;
            else if (ScriptDetector.isHan(ch)) unkChar[cp] = 3;
            else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) unkChar[cp] = 4;
            else unkChar[cp] = 0;
        }
        byte[] unkCompat = new byte[65536 * 4];

        File tmp = File.createTempFile("kuromoji_synth_", ".bin");
        tmp.deleteOnExit();
        writePackedFile(
                tmp,
                baseBuf.array(),
                checkBuf.array(),
                tidOut.toByteArray(),
                tidPosOut.toByteArray(),
                tidMapOut.toByteArray(),
                ccBuf.array(),
                unkOut.toByteArray(),
                unkPosOut.toByteArray(),
                unkMapOut.toByteArray(),
                unkChar,
                unkCompat,
                invokeOut.toByteArray());
        KuromojiDictionary opened = open(tmp);
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return opened;
    }
}
