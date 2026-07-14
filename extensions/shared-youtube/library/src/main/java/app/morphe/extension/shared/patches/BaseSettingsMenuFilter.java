/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2029
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Needle parsing, hierarchical discovery capture, and filter mutation helpers.
 * Called from the bytecode-injected filter and from the picker Preference.
 */
@SuppressWarnings("unused")
public abstract class BaseSettingsMenuFilter {

    private static final int MAX_TITLE_LENGTH = 60;

    @Nullable
    private static volatile BaseSettingsMenuFilter INSTANCE;

    /**
     * Buffer for the current helper walk; endCapture() flushes it to the persisted setting
     * so partial walks never corrupt the picker view.
     */
    private static final List<DiscoveredNode> currentWalk = new ArrayList<>();

    /**
     * True between beginCapture and endCapture so setTitle captures during the walk join
     * the buffer, while post-walk assignments (async server titles) append persistently.
     */
    private static volatile boolean insideWalk;

    private final StringSetting entriesSetting;
    private final StringSetting discoveredSetting;

    private volatile String cachedRaw;
    @Nullable private volatile String[] cachedNeedles;

    protected BaseSettingsMenuFilter(StringSetting entriesSetting,
                                     StringSetting discoveredSetting) {
        this.entriesSetting = entriesSetting;
        this.discoveredSetting = discoveredSetting;
        INSTANCE = this;
    }

    /**
     * Class.forName the known app-specific singletons so INSTANCE is populated even when
     * the picker Preference opens before any standard-settings navigation has fired.
     */
    @Nullable
    private static BaseSettingsMenuFilter instance() {
        if (INSTANCE == null) {
            String[] candidates = {
                    "app.morphe.extension.youtube.patches.SettingsMenuFilterPatch",
                    "app.morphe.extension.music.patches.SettingsMenuFilterPatch"
            };
            for (String fqn : candidates) {
                try {
                    Class.forName(fqn);
                } catch (ClassNotFoundException ignored) {
                    // Only one candidate lives in any given APK.
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Empty array means no hides; picker capture still happens so users can pick something later.
     */
    protected final String[] needles() {
        String raw = entriesSetting.get();
        if (raw.equals(cachedRaw)) return cachedNeedles;

        List<String> result = new ArrayList<>();
        if (!raw.isBlank()) {
            Set<String> reserved = reservedNeedles();
            for (String line : raw.split("\n")) {
                String trimmed = line.trim().toLowerCase();
                if (trimmed.isEmpty()) continue;
                if (reserved.contains(trimmed)) {
                    Logger.printDebug(() -> "SettingsMenuFilter ignoring reserved needle: " + trimmed);
                    continue;
                }
                result.add(trimmed);
            }
        }
        String[] parsed = result.toArray(new String[0]);
        Logger.printDebug(() -> "SettingsMenuFilter active with needles=" + result);
        cachedNeedles = parsed;
        cachedRaw = raw;
        return parsed;
    }

    /**
     * Resolved per call so the host app locale wins over the Morphe language override.
     */
    private static Set<String> reservedNeedles() {
        Set<String> result = new HashSet<>();
        addLoweredIfPresent(result, ResourceUtils.getString("morphe_settings_title"));
        addLoweredIfPresent(result, ResourceUtils.getString("morphe_settings_submenu_title"));
        return result;
    }

    private static void addLoweredIfPresent(Set<String> target, @Nullable String value) {
        if (value != null) target.add(value.toLowerCase());
    }

    /**
     * Exact match keeps a needle from over-hiding unrelated titles that share substrings with it.
     */
    public static boolean equalsAny(@Nullable CharSequence text, String[] needles) {
        if (text == null) return false;
        String haystack = text.toString().toLowerCase();
        for (String needle : needles) {
            if (haystack.equals(needle)) return true;
        }
        return false;
    }

    public static void logHidden(CharSequence title) {
        Logger.printDebug(() -> "SettingsMenuFilter hidden: " + title);
    }

    /**
     * Resets the walk buffer before a fresh onCreatePreferences pass so the persisted tree
     * always reflects the most recent settings screen instead of accumulating stale nodes.
     */
    public static void beginCapture() {
        if (instance() == null) return;
        synchronized (currentWalk) {
            currentWalk.clear();
        }
        insideWalk = true;
    }

    /**
     * Adds a discovered node with its parent title so the picker can render nested rows.
     */
    public static void capture(@Nullable CharSequence parent, @Nullable CharSequence title) {
        BaseSettingsMenuFilter self = instance();
        if (self == null) return;

        String cleanTitle = clean(title);
        if (cleanTitle == null) return;
        if (reservedNeedles().contains(cleanTitle.toLowerCase())) return;

        String cleanParent = clean(parent);
        DiscoveredNode node = new DiscoveredNode(cleanParent, cleanTitle);

        if (insideWalk) {
            synchronized (currentWalk) {
                for (DiscoveredNode n : currentWalk) {
                    if (n.title().equalsIgnoreCase(cleanTitle)) return;
                }
                currentWalk.add(node);
            }
        } else {
            appendOrphan(self, node);
        }
    }

    /**
     * Post-walk setTitle assignments go straight to the persisted set
     * so the picker keeps them across settings-screen re-opens.
     */
    private static synchronized void appendOrphan(BaseSettingsMenuFilter self, DiscoveredNode node) {
        List<DiscoveredNode> existing = discoveredTree();
        for (DiscoveredNode n : existing) {
            if (n.title().equalsIgnoreCase(node.title())) return;
        }
        List<DiscoveredNode> updated = new ArrayList<>(existing);
        updated.add(node);
        self.discoveredSetting.save(serialize(updated));
    }

    /**
     * Flushes the walk buffer to the persisted setting as JSON so the picker survives process death.
     */
    public static void endCapture() {
        BaseSettingsMenuFilter self = instance();
        if (self == null) return;

        List<DiscoveredNode> snapshot;
        synchronized (currentWalk) {
            snapshot = new ArrayList<>(currentWalk);
        }
        insideWalk = false;
        self.discoveredSetting.save(serialize(snapshot));
    }

    private static String serialize(List<DiscoveredNode> nodes) {
        JSONArray array = new JSONArray();
        for (DiscoveredNode node : nodes) {
            try {
                JSONObject entry = new JSONObject();
                if (node.parent() != null) entry.put("parent", node.parent());
                entry.put("title", node.title());
                array.put(entry);
            } catch (JSONException ignored) {
                // put(String, String) only throws for Double NaN/Infinity, never for our strings.
            }
        }
        return array.toString();
    }

    @Nullable
    private static String clean(@Nullable CharSequence text) {
        if (text == null) return null;
        String value = text.toString().trim();
        if (value.isEmpty() || value.length() > MAX_TITLE_LENGTH) return null;
        return value;
    }

    /**
     * Parsed hierarchical tree in traversal order for the picker to render.
     */
    public static List<DiscoveredNode> discoveredTree() {
        BaseSettingsMenuFilter self = instance();
        if (self == null) return Collections.emptyList();
        String json = self.discoveredSetting.get();
        if (json.isBlank()) return Collections.emptyList();
        try {
            JSONArray array = new JSONArray(json);
            List<DiscoveredNode> result = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                String parent = entry.has("parent") ? entry.getString("parent") : null;
                String title = entry.getString("title");
                result.add(new DiscoveredNode(parent, title));
            }
            return result;
        } catch (Exception ex) {
            Logger.printException(() -> "SettingsMenuFilter discoveredTree parse", ex);
            return Collections.emptyList();
        }
    }

    /**
     * Lowercased snapshot of the current filter list for checkmark rendering in the picker.
     */
    public static Set<String> activeFilterEntries() {
        BaseSettingsMenuFilter self = instance();
        if (self == null) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (String line : self.entriesSetting.get().split("\n")) {
            String trimmed = line.trim().toLowerCase();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /**
     * Toggle picker tap: adds the title when absent, removes matching lines when present.
     * Returns true if the title is in the filter after the operation.
     */
    public static boolean toggleInFilter(String title) {
        BaseSettingsMenuFilter self = instance();
        if (self == null) return false;

        String needle = title.trim().toLowerCase();
        String raw = self.entriesSetting.get();
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String line : raw.split("\n")) {
            if (line.trim().toLowerCase().equals(needle)) {
                removed = true;
                continue;
            }
            kept.add(line);
        }

        String next;
        boolean nowSelected;
        if (removed) {
            next = String.join("\n", kept);
            nowSelected = false;
        } else {
            next = raw.isEmpty() ? title : (raw.endsWith("\n") ? raw + title : raw + "\n" + title);
            nowSelected = true;
        }
        self.entriesSetting.save(next);
        Logger.printDebug(() -> "SettingsMenuFilter toggle '" + title + "' -> " + (nowSelected ? "added" : "removed"));
        return nowSelected;
    }

    /**
     * One Preference in the settings tree: null parent = top-level, matching another
     * node's title = nested child.
     */
    public record DiscoveredNode(@Nullable String parent, String title) {}
}
