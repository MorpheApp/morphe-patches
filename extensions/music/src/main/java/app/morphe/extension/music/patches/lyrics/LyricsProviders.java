/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.requests.AMLLProvider;
import app.morphe.extension.music.patches.lyrics.requests.AppleMusicProvider;
import app.morphe.extension.music.patches.lyrics.requests.BinimumProvider;
import app.morphe.extension.music.patches.lyrics.requests.BlyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.CaptionsFetcher;
import app.morphe.extension.music.patches.lyrics.requests.DeezerProvider;
import app.morphe.extension.music.patches.lyrics.requests.GeniusProvider;
import app.morphe.extension.music.patches.lyrics.requests.KuGouProvider;
import app.morphe.extension.music.patches.lyrics.requests.LRCLIBProvider;
import app.morphe.extension.music.patches.lyrics.requests.LrcRedProvider;
import app.morphe.extension.music.patches.lyrics.requests.LunaBeatProvider;
import app.morphe.extension.music.patches.lyrics.requests.LunaProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricifyProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.MusixmatchProvider;
import app.morphe.extension.music.patches.lyrics.requests.NetEaseProvider;
import app.morphe.extension.music.patches.lyrics.requests.PetitLyricsProvider;
import app.morphe.extension.music.patches.lyrics.requests.QQProvider;
import app.morphe.extension.music.patches.lyrics.requests.RMMProvider;
import app.morphe.extension.music.patches.lyrics.requests.SimpMusicProvider;
import app.morphe.extension.music.patches.lyrics.requests.SpotifyProvider;
import app.morphe.extension.music.patches.lyrics.requests.UnisonProvider;
import app.morphe.extension.music.patches.lyrics.requests.YouTubeMusicProvider;
import app.morphe.extension.music.settings.Settings;

public final class LyricsProviders {

    static final List<String> PROVIDER_ORDER = Arrays.asList(
            "Apple", "BiniLyrics", "LrcRed", "bLyrics", "Lyricify", "Unison",
            "PetitLyrics", "AMLL", "QQ", "SimpMusic", "KuGou", "Musixmatch",
            "YTMusic", "LRCLIB", "NetEase", "Luna", "LunaBeat",
            "Captions", "Spotify", "Deezer", "RMM", "Genius");

    private LyricsProviders() {
    }

    public static List<LyricsProvider> providersInOrder(String order) {
        List<LyricsProvider> providers = new ArrayList<>(PROVIDER_ORDER.size());
        for (String id : enabledProviderIds(order)) {
            LyricsProvider provider = providerFor(id);
            if (provider != null) {
                providers.add(provider);
            }
        }
        return providers;
    }

    /**
     * The providers that can answer a custom title and artist: the ones keyed by the playing
     * track's video id only know that track, and a prefetch of another track has none to ask with.
     */
    public static List<LyricsProvider> searchableProviders(List<LyricsProvider> providers) {
        List<LyricsProvider> result = new ArrayList<>(providers.size());
        for (LyricsProvider provider : providers) {
            switch (provider.name()) {
                case "YTMusic", "Captions", "Unison", "SimpMusic" -> { }
                default -> result.add(provider);
            }
        }
        return result;
    }

    public static boolean isProviderEnabled(String id) {
        return enabledProviderIds(Settings.LYRICS_SOURCE.get()).contains(id);
    }

    private static List<String> enabledProviderIds(String order) {
        List<String> result = new ArrayList<>();
        if (order == null || !order.contains(",")) {
            order = Settings.DEFAULT_LYRICS_ORDER;
        }
        for (String raw : order.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            boolean enabled = true;
            if (token.startsWith("-")) {
                enabled = false;
                token = token.substring(1).trim();
            }
            if (!PROVIDER_ORDER.contains(token)) {
                continue;
            }
            boolean seen = false;
            for (String existing : result) {
                if (existing.equals(token)) {
                    seen = true;
                    break;
                }
            }
            if (seen) {
                continue;
            }
            if (enabled) {
                result.add(token);
            }
        }
        return result;
    }

    @Nullable
    private static LyricsProvider providerFor(String id) {
        return switch (id) {
            case "YTMusic" -> new YouTubeMusicProvider();
            case "Captions" -> new CaptionsFetcher.CaptionsProvider();
            case "LRCLIB" -> new LRCLIBProvider();
            case "QQ" -> new QQProvider();
            case "NetEase" -> new NetEaseProvider();
            case "KuGou" -> new KuGouProvider();
            case "Luna" -> new LunaProvider();
            case "PetitLyrics" -> new PetitLyricsProvider();
            case "bLyrics" -> new BlyricsProvider();
            case "BiniLyrics" -> new BinimumProvider();
            case "Unison" -> new UnisonProvider();
            case "SimpMusic" -> new SimpMusicProvider();
            case "AMLL" -> new AMLLProvider();
            case "LunaBeat" -> new LunaBeatProvider();
            case "Apple" -> new AppleMusicProvider();
            case "RMM" -> new RMMProvider();
            case "Spotify" -> new SpotifyProvider();
            case "Lyricify" -> new LyricifyProvider();
            case "Musixmatch" -> new MusixmatchProvider();
            case "Deezer" -> new DeezerProvider();
            case "Genius" -> new GeniusProvider();
            case "LrcRed" -> new LrcRedProvider();
            default -> null;
        };
    }
}
