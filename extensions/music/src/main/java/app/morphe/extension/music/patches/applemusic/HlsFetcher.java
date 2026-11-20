package app.morphe.extension.music.patches.applemusic;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android's MediaPlayer is unreliable with Apple's fMP4 HLS, so the (short, looping) motion
 * artwork is assembled into one local file: init segment + media segments, byte ranges honoured.
 */
final class HlsFetcher {
    private static final long MAX_BYTES = 48L * 1024 * 1024;

    private static String text(String url) throws Exception {
        return new String(AppleCatalog.get(url, null, 0, -1), "UTF-8");
    }

    /** Variant playlist URLs, best first: H.264 before HEVC/others, then largest (<=1080) first. */
    static List<String> variants(String masterUrl) throws Exception {
        String pl = text(masterUrl);
        List<String> out = new ArrayList<>();
        if (!pl.contains("#EXT-X-STREAM-INF")) { out.add(masterUrl); return out; }
        final List<Object[]> rows = new ArrayList<>(); // {url, avc(Boolean), height(Integer)}
        String[] lines = pl.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
            int h = 0;
            Matcher m = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)").matcher(l);
            if (m.find()) h = Integer.parseInt(m.group(2));
            String codecs = attr(l, "CODECS");
            boolean avc = codecs != null && codecs.contains("avc1");
            for (int j = i + 1; j < lines.length; j++) {
                String u = lines[j].trim();
                if (u.isEmpty() || u.startsWith("#")) continue;
                rows.add(new Object[]{resolve(masterUrl, u), avc, h});
                break;
            }
        }
        Collections.sort(rows, new Comparator<Object[]>() {
            @Override public int compare(Object[] a, Object[] b) {
                int sa = ((Boolean) a[1] ? 100000 : 0) + ((Integer) a[2] > 1080 ? 0 : (Integer) a[2]);
                int sb = ((Boolean) b[1] ? 100000 : 0) + ((Integer) b[2] > 1080 ? 0 : (Integer) b[2]);
                return sb - sa;
            }
        });
        for (Object[] r : rows) out.add((String) r[0]);
        int avcN = 0;
        for (Object[] r : rows) if ((Boolean) r[1]) avcN++;
        DebugLog.log("  " + rows.size() + " variants (" + avcN + " H.264)");
        if (out.isEmpty()) throw new IllegalStateException("No variant");
        return out;
    }

    static File fetch(String url, File out) throws Exception {
        String pl = text(url);
        if (!pl.contains("#EXTINF")) {
            try (FileOutputStream f = new FileOutputStream(out)) { f.write(AppleCatalog.get(url, null, 0, -1)); }
            return out;
        }
        DebugLog.log("HLS media playlist: " + (pl.split("#EXTINF").length - 1) + " segments, map=" + pl.contains("#EXT-X-MAP")
                + " byterange=" + pl.contains("BYTERANGE") + " discontinuity=" + pl.contains("DISCONTINUITY"));
        File tmp = new File(out.getPath() + ".part");
        long total = 0;
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            String lastUri = null;
            long lastEnd = 0, pendingLen = -1, pendingOff = -1;
            for (String raw : pl.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXT-X-MAP:")) {
                    String br = attr(line, "BYTERANGE");
                    total += write(fos, resolve(url, attr(line, "URI")), br == null ? null : range(br, 0));
                } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                    long[] r = range(line.substring(17), -1);
                    pendingLen = r[0];
                    pendingOff = r[1];
                } else if (!line.startsWith("#")) {
                    String seg = resolve(url, line);
                    long[] r = null;
                    if (pendingLen >= 0) {
                        long off = pendingOff >= 0 ? pendingOff : (seg.equals(lastUri) ? lastEnd : 0);
                        r = new long[]{pendingLen, off};
                        lastEnd = off + pendingLen;
                    }
                    lastUri = seg;
                    pendingLen = pendingOff = -1;
                    total += write(fos, seg, r);
                }
                if (total > MAX_BYTES) throw new java.io.IOException("Motion artwork too large");
            }
        }
        if (total == 0) throw new java.io.IOException("Empty playlist");
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) throw new java.io.IOException("rename failed");
        describe(out);
        return out;
    }

    /** Logs the top-level MP4 boxes and codec fourccs, to diagnose files the player rejects. */
    private static void describe(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] head = new byte[8192];
            int n = in.read(head);
            StringBuilder sb = new StringBuilder("File boxes:");
            long pos = 0;
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                int count = 0;
                while (pos + 8 <= raf.length() && count++ < 8) {
                    raf.seek(pos);
                    long size = raf.readInt() & 0xFFFFFFFFL;
                    byte[] t = new byte[4];
                    raf.readFully(t);
                    sb.append(' ').append(new String(t, "ISO-8859-1")).append('(').append(size).append(')');
                    if (size < 8) break;
                    pos += size;
                }
            }
            String h = new String(head, 0, Math.max(0, n), "ISO-8859-1");
            sb.append(" codecs:");
            for (String c : new String[]{"avc1", "avc3", "hvc1", "hev1", "av01", "mp4a"}) if (h.contains(c)) sb.append(' ').append(c);
            DebugLog.log(sb.toString());
        } catch (Throwable t) { DebugLog.err("describe", t); }
    }

    private static long write(FileOutputStream fos, String url, long[] r) throws Exception {
        byte[] b = r == null ? AppleCatalog.get(url, null, 0, -1)
                : AppleCatalog.get(url, null, r[1], r[1] + r[0] - 1);
        fos.write(b);
        return b.length;
    }

    /** @return {length, offset} */
    private static long[] range(String s, long defOff) {
        String[] p = s.trim().replace("\"", "").split("@");
        return new long[]{Long.parseLong(p[0]), p.length > 1 ? Long.parseLong(p[1]) : defOff};
    }

    private static String attr(String line, String key) {
        Matcher m = Pattern.compile(key + "=(\"[^\"]*\"|[^,]*)").matcher(line);
        return m.find() ? m.group(1).replace("\"", "") : null;
    }

    private static String resolve(String base, String ref) {
        return URI.create(base).resolve(ref).toString();
    }
}
