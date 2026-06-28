/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import static app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationPatch.TTS_ENGINE_SYSTEM;

import android.content.Context;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * In-memory LRU cache for synthesized Edge TTS MP3 segments.
 *
 * <p>Edge TTS audio at 24 kHz / 48 kbps uses ~6 KB/s. A full hour of speech is
 * ~20 MB, so keeping a few thousand sentences in memory is safe.
 *
 * <p>Test voice samples are also persisted to disk so they survive app restarts
 * and do not require re-synthesis on subsequent sessions.
 */
final class TtsCache {

    private static final Map<String, byte[]> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(1000));

    private static final String TEST_SAMPLES_DIR = "vot_voice_samples";

    static synchronized boolean notCached(String videoId, int segmentIndex, String voice, String lang, String text) {
        if (TTS_ENGINE_SYSTEM.equals(voice)) return true;
        return !cache.containsKey(key(videoId, segmentIndex, voice, lang, text));
    }

    static synchronized byte[] get(String videoId, int segmentIndex, String voice, String lang, String text) {
        if (TTS_ENGINE_SYSTEM.equals(voice)) return null;
        return cache.get(key(videoId, segmentIndex, voice, lang, text));
    }

    static synchronized void put(String videoId, int segmentIndex, String voice, String lang, String text, byte[] data) {
        if (TTS_ENGINE_SYSTEM.equals(voice)) return;
        cache.put(key(videoId, segmentIndex, voice, lang, text), data);
    }

    static long getDuration(String videoId, int segmentIndex, String voice, String lang, String text) {
        if (TTS_ENGINE_SYSTEM.equals(voice)) return -1;
        byte[] data = cache.get(key(videoId, segmentIndex, voice, lang, text));
        return data != null ? TtsEngine.mp3DurationMs(data.length) : -1;
    }

    static byte[] getTestSampleFromDisk(String voiceId, String lang) {
        try {
            File file = testSampleFile(voiceId, lang);
            if (!file.exists()) return null;
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                new DataInputStream(fis).readFully(data);
                return data;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Disk read failed: " + voiceId);
            return null;
        }
    }

    static void putTestSampleToDisk(String voiceId, String lang, byte[] data) {
        try {
            File file = testSampleFile(voiceId, lang);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Logger.printDebug(() -> "Failed to create cache directory");
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Disk write failed: " + voiceId);
        }
    }

    private static File testSampleFile(String voiceId, String lang) {
        Context context = Utils.getContext();
        return new File(context.getCacheDir(), TEST_SAMPLES_DIR + File.separator + voiceId + '_' + lang);
    }

    private static String key(String videoId, int segmentIndex, String voice, String lang, String text) {
        return videoId + ':' + segmentIndex + ':' + voice + ':' + lang + ':' + text;
    }
}
