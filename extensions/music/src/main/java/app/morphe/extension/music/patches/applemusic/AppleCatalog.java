package app.morphe.extension.music.patches.applemusic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to Apple's own catalog API (amp-api.music.apple.com), like the music.apple.com web
 * player does. No third-party proxy. Logic ported from ViviMusic Xten apple-canvas.js (AGPL-3.0).
 */
final class AppleCatalog {
    static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36";
    private static final String AMP = "https://amp-api.music.apple.com";
    private static final String[] BLACKLIST = {"playlist", "set list", "essentials", "dj mix", "mixed", "apple music", "today's hits", "session"};
    private static final String[] EDITION = {"deluxe", "expanded", "remastered", "remix", "version", "edit", "mix", "bonus"};

    private static String token;
    private static long tokenExpiryMs;

    static byte[] get(String url, String token, long from, long to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Origin", "https://music.apple.com");
        c.setRequestProperty("Referer", "https://music.apple.com/");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (to >= 0) c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new java.io.IOException("HTTP " + code + " " + url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String text(String url) throws Exception {
        return new String(get(url, null, 0, -1), "UTF-8");
    }

    /** Scrapes the short-lived JWT the Apple Music web player uses for anonymous catalog reads. */
    static synchronized String token() throws Exception {
        long now = System.currentTimeMillis();
        if (token != null && now < tokenExpiryMs - 60000) return token;
        String html = text("https://music.apple.com/us/browse");
        Matcher sm = Pattern.compile("/assets/index(?:-legacy)?[~-][a-zA-Z0-9_-]+\\.js").matcher(html);
        List<String> scripts = new ArrayList<>();
        while (sm.find()) if (!scripts.contains(sm.group())) scripts.add(sm.group());
        Pattern tp = Pattern.compile("ey[a-zA-Z0-9_-]+\\.ey[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+");
        for (String s : scripts) {
            String js;
            try { js = text("https://music.apple.com" + s); } catch (Exception e) { continue; }
            Matcher tm = tp.matcher(js);
            while (tm.find()) {
                try {
                    String t = tm.group();
                    byte[] p = android.util.Base64.decode(t.split("\\.")[1],
                            android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                    JSONObject o = new JSONObject(new String(p, "UTF-8"));
                    long exp = o.optLong("exp", 0) * 1000;
                    if (o.has("iss") && exp > now) {
                        token = t;
                        tokenExpiryMs = exp;
                        DebugLog.log("Apple token OK (from " + s + ")");
                        return t;
                    }
                } catch (Exception ignored) { }
            }
        }
        DebugLog.log("Token scrape failed; scripts found: " + scripts.size());
        throw new java.io.IOException("No Apple Music token found");
    }

    private static JSONObject amp(String path, String... kv) throws Exception {
        StringBuilder sb = new StringBuilder(AMP).append(path).append('?');
        for (int i = 0; i + 1 < kv.length; i += 2)
            sb.append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8")).append('&');
        try {
            return new JSONObject(new String(get(sb.toString(), token(), 0, -1), "UTF-8"));
        } catch (java.io.IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("HTTP 401")) token = null;
            throw e;
        }
    }

    private static String norm(String s) {
        if (s == null) return "";
        s = Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFKD).replaceAll("[\\u0300-\\u036f]", "");
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static final String DELIM = "(?:\\s*,\\s*|\\s*&\\s*|\\s+\u00d7\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)";

    private static List<String> artists(String s) {
        List<String> l = new ArrayList<>();
        for (String p : (s == null ? "" : s).split("(?i)" + DELIM)) {
            String n = norm(p);
            if (!n.isEmpty()) l.add(n);
        }
        return l;
    }

    private static boolean artistMatches(String req, String got) {
        List<String> r = artists(req), g = artists(got);
        if (r.isEmpty() || g.isEmpty()) return false;
        for (String x : r) if (!g.contains(x)) return false;
        return true;
    }

    private static boolean blacklisted(String n) {
        String l = n == null ? "" : n.toLowerCase();
        for (String w : BLACKLIST) if (l.contains(w)) return true;
        return false;
    }

    /** Square motion artwork first (our overlay is square), then the tall one. */
    private static String motionUrl(JSONObject ev) {
        if (ev == null) return null;
        for (String k : new String[]{"motionDetailSquare", "motionSquareVideo1x1", "motionDetailRaw", "motionDetailTall"}) {
            JSONObject a = ev.optJSONObject(k);
            if (a == null) continue;
            for (String f : new String[]{"video", "videoUrl", "hlsUrl", "url"}) {
                String v = a.optString(f, "");
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    private static String albumMotion(String albumId, String sf) throws Exception {
        if (albumId == null || albumId.startsWith("pl.")) return null;
        JSONObject root = amp("/v1/catalog/" + sf + "/albums/" + albumId, "extend", "editorialVideo");
        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) return null;
        JSONObject at = data.getJSONObject(0).optJSONObject("attributes");
        if (at == null || blacklisted(at.optString("name"))) return null;
        return motionUrl(at.optJSONObject("editorialVideo"));
    }

    /** @return the .m3u8 URL of the track's album motion artwork, or null if there is none. */
    static String findMotion(String song, String artist, String album, String sf) throws Exception {
        if (song == null || artist == null) return null;
        String q = song.toLowerCase().contains(artist.toLowerCase()) ? song : artist + " " + song;
        if (album != null && !album.isEmpty() && !q.toLowerCase().contains(album.toLowerCase())) q += " " + album;
        JSONObject root = amp("/v1/catalog/" + sf + "/search", "term", q, "types", "songs", "limit", "10",
                "extend", "editorialVideo", "include", "albums");
        JSONObject r = root.optJSONObject("results");
        JSONObject songs = r == null ? null : r.optJSONObject("songs");
        JSONArray arr = songs == null ? null : songs.optJSONArray("data");
        DebugLog.log("Search [" + sf + "] q='" + q + "' -> " + (arr == null ? 0 : arr.length()) + " songs");
        if (arr == null) return null;

        String nt = norm(song);
        List<double[]> scored = new ArrayList<>(); // {score, index}
        int rejected = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject at = arr.getJSONObject(i).optJSONObject("attributes");
            if (at == null) continue;
            String name = at.optString("name"), coll = at.optString("albumName", at.optString("collectionName", ""));
            if (blacklisted(name) || blacklisted(coll)) continue;
            if (!artistMatches(artist, at.optString("artistName"))) { rejected++; continue; }
            double score = 10;
            String nn = norm(name);
            if (nn.equals(nt)) score += 15;
            else if (nn.contains(nt) || nt.contains(nn)) score += 7;
            else score -= 10;
            for (String w : EDITION) {
                boolean a = song.toLowerCase().contains(w), b = name.toLowerCase().contains(w);
                if (a && b) score += 5;
                else if (a != b && b) score -= 3;
            }
            if (album != null && !coll.isEmpty()) {
                String na = norm(album), nc = norm(coll);
                if (nc.equals(na)) score += 20;
                else if (nc.contains(na) || na.contains(nc)) score += 10;
            }
            scored.add(new double[]{score, i});
        }
        java.util.Collections.sort(scored, new java.util.Comparator<double[]>() { @Override public int compare(double[] a, double[] b) { return Double.compare(b[0], a[0]); } });
        DebugLog.log("  " + scored.size() + " candidates (" + rejected + " rejected by artist)");
        for (int k = 0; k < Math.min(3, scored.size()); k++) {
            JSONObject c = arr.getJSONObject((int) scored.get(k)[1]).optJSONObject("attributes");
            if (c != null) DebugLog.log("  top" + (k + 1) + " score=" + scored.get(k)[0] + " " + c.optString("artistName") + " - " + c.optString("name") + " [" + c.optString("albumName") + "]");
        }
        for (double[] s : scored) {
            if (s[0] < 12) continue;
            JSONObject item = arr.getJSONObject((int) s[1]);
            JSONObject at = item.getJSONObject("attributes");
            String albumId = null;
            JSONObject rel = item.optJSONObject("relationships");
            JSONObject ra = rel == null ? null : rel.optJSONObject("albums");
            JSONArray rd = ra == null ? null : ra.optJSONArray("data");
            if (rd != null && rd.length() > 0) albumId = rd.getJSONObject(0).optString("id", null);
            if (albumId == null) {
                String u = at.optString("url", "");
                int k = u.indexOf("/album/");
                if (k >= 0) {
                    String part = u.substring(k + 7);
                    int qm = part.indexOf('?');
                    if (qm >= 0) part = part.substring(0, qm);
                    String id = part.substring(part.lastIndexOf('/') + 1);
                    if (id.matches("\\d+")) albumId = id;
                }
            }
            String direct = motionUrl(at.optJSONObject("editorialVideo"));
            if (direct != null) return direct;
            String m = albumMotion(albumId, sf);
            DebugLog.log("  album " + albumId + " motion=" + m);
            if (m != null) return m;
        }
        return null;
    }
}
