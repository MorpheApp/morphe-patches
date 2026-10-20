/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import static app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationPatch.TTS_ENGINE_SYSTEM;


import java.util.Collections;
import java.util.Map;

import app.morphe.extension.shared.Utils;

/**
 * In-memory LRU cache for synthesized Edge TTS MP3 segments.
 *
 * <p>Edge TTS audio at 24 kHz / 48 kbps uses ~6 KB/s. A full hour of speech is
 * ~20 MB, so keeping a few thousand sentences in memory is safe.
 *
 * <p>Voice previews are cached in memory on first use.
 */
final class TTSCache {

    private static final Map<String, byte[]> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(1000));

    private static final String PREVIEW_VIDEO_ID = "test";
    private static final int PREVIEW_SEGMENT = -1;

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
        return data != null ? TTSEngine.mp3DurationMs(data.length) : -1;
    }

    static byte[] getPreview(String voice, String lang, String text) {
        return get(PREVIEW_VIDEO_ID, PREVIEW_SEGMENT, voice, lang, text);
    }

    static void putPreview(String voice, String lang, String text, byte[] audio) {
        put(PREVIEW_VIDEO_ID, PREVIEW_SEGMENT, voice, lang, text, audio);
    }

    private static String key(String videoId, int segmentIndex, String voice, String lang, String text) {
        return videoId + ':' + segmentIndex + ':' + voice + ':' + lang + ':' + text;
    }
}
