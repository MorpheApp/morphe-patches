package app.morphe.extension.shared.spoof.js.nsigsolver.common;

import app.morphe.extension.shared.settings.preference.SharedPrefCategory;
import java.lang.ref.WeakReference;

import java.util.HashMap;
import java.util.Map;

public class CacheService {
    private static final String PREF_NAME = "yt_cache_service";
    private static final String KEY_DELIM = "%KEY%";
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

        String code = pref.getString(getCodeKey(key), "");
        String version = pref.getString(getVersionKey(key), "");
        String variant = pref.getString(getVariantKey(key), "");

        if (!code.isEmpty()) {
            return new CachedData(code, version, variant);
        } else {
            return null;
        }
    }

    public void save(String section, String key, CachedData content) throws CacheError {
        SharedPrefCategory pref = getSharedPrefs(getPrefsName(section));

        pref.saveString(getCodeKey(key), content.getCode());
        pref.saveString(getVersionKey(key), content.getVersion());
        pref.saveString(getVariantKey(key), content.getVariant());
    }

    private SharedPrefCategory getSharedPrefs(String name) {
        WeakReference<SharedPrefCategory> ref = prefs.get(name);
        SharedPrefCategory existing = (ref != null) ? ref.get() : null;

        if (existing != null) {
            return existing;
        }

        SharedPrefCategory newPrefs = new SharedPrefCategory(name);
        prefs.put(name, new WeakReference<>(newPrefs));
        return newPrefs;
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
