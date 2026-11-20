package app.morphe.extension.music.patches.applemusic;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Persistent log (app files dir), shown in the YT Music settings screen. */
final class DebugLog {
    static final String FILE = "apple_artwork.log";
    private static final long MAX_BYTES = 400 * 1024;
    private static final long KEEP_BYTES = 200 * 1024;
    private static final ArrayList<String> EARLY = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static File file;

    static final String CRASH_FILE = "apple_artwork_crash.txt";

    /** Records any uncaught exception (full stack) so it can be shown after the app restarts. */
    static void installCrashHandler(final Context c) {
        try {
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread t, Throwable e) {
                    try {
                        java.io.StringWriter sw = new java.io.StringWriter();
                        e.printStackTrace(new java.io.PrintWriter(sw));
                        try (FileOutputStream o = new FileOutputStream(new File(c.getFilesDir(), CRASH_FILE), false)) {
                            o.write((FMT.format(new Date()) + " thread=" + t.getName() + "\n" + sw).getBytes("UTF-8"));
                        }
                    } catch (Throwable ignored) { }
                    if (prev != null) prev.uncaughtException(t, e);
                }
            });
        } catch (Throwable ignored) { }
    }

    static String takeCrash(Context c) {
        try {
            File f = new File(c.getFilesDir(), CRASH_FILE);
            if (!f.exists()) return null;
            byte[] b = new byte[(int) Math.min(f.length(), 20000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { in.read(b); }
            f.delete();
            return new String(b, "UTF-8");
        } catch (Throwable t) { return null; }
    }

    static synchronized void init(Context c) {
        if (file != null) return;
        try {
            file = new File(c.getFilesDir(), FILE);
            trim(file);
            write("=== session start " + FMT.format(new Date()) + " (1.2.1) ===");
            for (String l : EARLY) write(l);
            EARLY.clear();
        } catch (Throwable t) { Log.w("AppleMusicArtwork", t); }
    }

    static synchronized void log(String s) {
        String line = FMT.format(new Date()).substring(11) + " " + s;
        Log.i("AppleMusicArtwork", s);
        if (file == null) { if (EARLY.size() < 200) EARLY.add(line); return; }
        write(line);
    }

    static void err(String where, Throwable t) {
        StringBuilder sb = new StringBuilder("ERROR in ").append(where).append(": ").append(t);
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(5, st.length); i++) sb.append("\n    at ").append(st[i]);
        Throwable c = t.getCause();
        if (c != null) sb.append("\n  caused by ").append(c);
        log(sb.toString());
    }

    private static void write(String line) {
        try (FileOutputStream o = new FileOutputStream(file, true)) {
            o.write((line + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) { }
    }

    /** Keeps the file bounded: when too big, only the newest part survives. */
    private static void trim(File f) {
        try {
            if (!f.exists() || f.length() <= MAX_BYTES) return;
            byte[] tail = new byte[(int) KEEP_BYTES];
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                r.seek(f.length() - KEEP_BYTES);
                r.readFully(tail);
            }
            int nl = 0;
            while (nl < tail.length && tail[nl] != '\n') nl++;
            try (FileOutputStream o = new FileOutputStream(f, false)) { o.write(tail, nl + 1, tail.length - nl - 1); }
        } catch (Throwable ignored) { }
    }

    static String read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return "(no log yet - play a song with the full player open)";
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length(), from = Math.max(0, len - 150 * 1024);
            byte[] b = new byte[(int) (len - from)];
            r.seek(from);
            r.readFully(b);
            return (from > 0 ? "...(older lines trimmed in view; Copy gives everything shown)\n" : "") + new String(b, "UTF-8");
        } catch (Throwable t) { return "Could not read log: " + t; }
    }

    static synchronized void clear(Context c) {
        try { new FileOutputStream(new File(c.getFilesDir(), FILE), false).close(); } catch (Throwable ignored) { }
    }
}
