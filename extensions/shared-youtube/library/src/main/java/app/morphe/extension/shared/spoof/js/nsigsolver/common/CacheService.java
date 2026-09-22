/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/340
 * https://github.com/MorpheApp/morphe-patches/pull/3120
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.js.nsigsolver.common;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.settings.preference.SharedPrefCategory;

public class CacheService {
    private static final String PREF_NAME = "yt_cache_service";
    private static final String KEY_DELIM = "%KEY%";
    /**
     * Preprocessed player JS keys. SharedPreferences loads the whole file into the Java heap,
     * so historical hashes must not accumulate.
     */
    private static final String PLAYER_KEY_PREFIX = "player:";

    @GuardedBy("itself")
    private static final Map<String, WeakReference<SharedPrefCategory>> prefs = new HashMap<>();

    // Singleton instance
    private static final CacheService INSTANCE = new CacheService();

    private CacheService() {}

    public static CacheService getInstance() {
        return INSTANCE;
    }

    public void clear(String section) throws CacheError {
        SharedPrefCategory pref = getSharedPrefs(getPrefsName(section));
        pref.clear();
    }

    public CachedData get(String section, String key) throws CacheError {
        SharedPrefCategory pref = getSharedPrefs(getPrefsName(section));
        prunePlayerEntries(pref, key.startsWith(PLAYER_KEY_PREFIX) ? key : null);

        String code = pref.getString(getCodeKey(key), "");
        String version = pref.getString(getVersionKey(key), "");
        String variant = pref.getString(getVariantKey(key), "");

        if (!code.isEmpty()) {
            return new CachedData(code, version, variant);
        }
        return null;
    }

    public void save(String section, String key, CachedData content) throws CacheError {
        SharedPrefCategory pref = getSharedPrefs(getPrefsName(section));
        if (key.startsWith(PLAYER_KEY_PREFIX)) {
            prunePlayerEntries(pref, key);
        }

        pref.saveString(getCodeKey(key), content.getCode());
        pref.saveString(getVersionKey(key), content.getVersion());
        pref.saveString(getVariantKey(key), content.getVariant());
    }

    /**
     * Keep at most one preprocessed player JS entry. {@code keepKey} is the current
     * {@code player:<hash>} key, or {@code null} when the current hash is unknown (drop extras).
     */
    private void prunePlayerEntries(SharedPrefCategory pref, @Nullable String keepKey) {
        Map<String, ?> all = pref.preferences.getAll();
        if (all.isEmpty()) {
            return;
        }

        Set<String> playerBases = new HashSet<>();
        for (String existing : all.keySet()) {
            if (!existing.startsWith(PLAYER_KEY_PREFIX)) {
                continue;
            }
            final int delim = existing.indexOf(KEY_DELIM);
            if (delim <= PLAYER_KEY_PREFIX.length()) {
                continue;
            }
            playerBases.add(existing.substring(0, delim));
        }

        if (playerBases.isEmpty()) {
            return;
        }

        if (keepKey == null) {
            if (playerBases.size() == 1) {
                return;
            }
            for (String base : playerBases) {
                removePlayerBase(pref, base);
            }
            return;
        }

        for (String base : playerBases) {
            if (!base.equals(keepKey)) {
                removePlayerBase(pref, base);
            }
        }
    }

    private void removePlayerBase(SharedPrefCategory pref, String base) {
        pref.removeKey(getCodeKey(base));
        pref.removeKey(getVersionKey(base));
        pref.removeKey(getVariantKey(base));
    }

    private SharedPrefCategory getSharedPrefs(String name) {
        synchronized (prefs) {
            prefs.values().removeIf(ref -> ref.get() == null);

            WeakReference<SharedPrefCategory> ref = prefs.get(name);

            if (ref != null) {
                SharedPrefCategory existing = ref.get();
                if (existing != null) {
                    return existing;
                }
            }

            SharedPrefCategory newPrefs = new SharedPrefCategory(name);
            prefs.put(name, new WeakReference<>(newPrefs));
            return newPrefs;
        }
    }

    private String getCodeKey(String key) {
        return key + KEY_DELIM + "code";
    }

    private String getVersionKey(String key) {
        return key + KEY_DELIM + "version";
    }

    private String getVariantKey(String key) {
        return key + KEY_DELIM + "variant";
    }

    private String getPrefsName(String section) throws CacheError {
        if (section.contains("/")) {
            throw new CacheError("Slashes aren't allowed inside the pref name: " + section);
        }
        return PREF_NAME + KEY_DELIM + section;
    }
}
