/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Utils;

/**
 * A minimal fake Android {@link Context} for lyrics unit tests that need
 * {@link Utils#getContext()} to be set (settings loading, {@code LyricsDirectories},
 * {@code TrackOffsetStore}, {@code LyricsCache}).
 *
 * <p>Bootstrap is idempotent: {@link #bootstrap()} installs an in-memory {@link SharedPreferences}
 * backed fake context and a temp {@code filesDir} exactly once per JVM. Tests that touch
 * {@code Settings} must call {@link #bootstrap()} from a {@code @BeforeClass} (or {@code @Before})
 * before the first {@code Settings.*} access, so the {@code Setting.preferences} static initializer
 * finds a live context.
 */
@SuppressWarnings({"deprecation", "unchecked"})
public final class LyricsTestEnv {

    private static boolean bootstrapped;

    private LyricsTestEnv() {
    }

    /**
     * Installs the fake context into {@link Utils} if not already done. Safe to call repeatedly and
     * from any thread. Once {@code Setting} is loaded it stays loaded, so this must run before the
     * first {@code Settings.*} touch in the whole test JVM.
     */
    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        File filesDir;
        try {
            filesDir = java.nio.file.Files.createTempDirectory("morphe-lyrics-test").toFile();
        } catch (Exception ex) {
            throw new RuntimeException("Could not create the lyrics test files dir", ex);
        }
        Utils.setContext(new FakeContext(filesDir));
        // The mockable android.jar stubs Resources.getSystem() to null, which makes
        // Utils.isDarkModeEnabled() NPE. SharedYouTubeSettings reads it during its static init,
        // which Settings pulls in. Pin the value so no static initializer hits the system resources.
        Utils.setIsDarkModeEnabled(false);
        bootstrapped = true;
    }

    static final class FakeContext extends ContextWrapper {
        private final File filesDir;
        private final Map<String, FakeSharedPreferences> prefs = new HashMap<>();

        FakeContext(File filesDir) {
            // The mockable android jar does not use the base, and every method we need is overridden.
            super(null);
            this.filesDir = filesDir;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return prefs.computeIfAbsent(name, key -> new FakeSharedPreferences());
        }

        @Override
        public File getFilesDir() {
            return filesDir;
        }

        @Override
        public File getCacheDir() {
            return filesDir;
        }

        @Override
        public File getExternalFilesDir(String type) {
            return filesDir;
        }

        @Override
        public String getPackageName() {
            return "morphe.music.test";
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Context createConfigurationContext(Configuration overrideConfig) {
            return this;
        }

        @Override
        public Context createPackageContext(String packageName, int flags) {
            return this;
        }

        /**
         * Returns a bare {@link Resources} so calls like
         * {@code ResourceUtils.getIdentifier(ResourceType.ID, ...)} in
         * {@code LyricsPanelInstaller.<clinit>} do not NPE. The mockable jar stubs
         * {@code Resources.getIdentifier()} to return {@code 0}, which is the
         * expected "not found" value.
         */
        @Override
        public Resources getResources() {
            return new Resources(null, null, null);
        }
    }

    /** In-memory {@link SharedPreferences} with a working {@link FakeEditor}. */
    static final class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> map = new HashMap<>();
        private final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();

        @Override
        public String getString(String key, String defValue) {
            Object value = map.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = map.get(key);
            return value instanceof Set ? (Set<String>) value : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = map.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = map.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = map.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = map.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(map);
        }

        @Override
        public boolean contains(String key) {
            return map.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor(this);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            listeners.remove(listener);
        }

        void applyChanges(Map<String, Object> changes, Set<String> removals) {
            map.putAll(changes);
            removals.forEach(map::remove);
            for (OnSharedPreferenceChangeListener listener : new ArrayList<>(listeners)) {
                for (String key : changes.keySet()) {
                    notify(listener, key);
                }
                for (String key : removals) {
                    notify(listener, key);
                }
            }
        }

        private void notify(OnSharedPreferenceChangeListener listener, String key) {
            try {
                listener.onSharedPreferenceChanged(this, key);
            } catch (Exception ignored) {
                // UI listeners such as LyricsPanelInstaller call Utils.runOnMainThreadNowOrLater,
                // which NPEs in the mockable jar because Looper.getMainLooper() returns null.
                // The setting value is already stored, so the exception is safe to swallow.
            }
        }
    }

    /** {@link SharedPreferences.Editor} that stages changes until {@code apply}/{@code commit}. */
    static final class FakeEditor implements SharedPreferences.Editor {
        private final FakeSharedPreferences owner;
        private final Map<String, Object> changes = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean cleared;

        FakeEditor(FakeSharedPreferences owner) {
            this.owner = owner;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            changes.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            changes.put(key, values);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            changes.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            changes.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            changes.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            changes.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            removals.add(key);
            changes.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            cleared = true;
            return this;
        }

        @Override
        public boolean commit() {
            if (cleared) {
                owner.map.clear();
            } else {
                owner.applyChanges(changes, removals);
            }
            changes.clear();
            removals.clear();
            cleared = false;
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
