/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.ResourceUtils.getString;

import android.content.Context;
import android.net.Uri;
import android.os.RemoteException;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.text.TextUtils;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.car.app.IOnDoneCallback;
import androidx.car.app.model.IOnCheckedChangeListener;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Toggle;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.morphe.extension.shared.Utils;

/**
 * Shows YouTube Music's car settings in the media browser. The original toggle listeners apply changes.
 */
public final class AndroidAutoSettingsTab {
    public interface StockSettingsSource {
        ListTemplate patch_getSettingsTemplate(Context context, RowTitle rowTitle);
    }

    public interface RowTitle {
        String get(int resourceId);
    }

    public interface StockToggle {
        StockToggleDelegate patch_getDelegate();
    }

    public interface StockToggleDelegate {
        IOnCheckedChangeListener patch_getListener();
    }

    static final String SETTINGS_MEDIA_ID = "morphe:aa:settings";
    private static final String ROW_PREFIX = SETTINGS_MEDIA_ID + ":row:";
    private static final IOnDoneCallback TOGGLE_DONE = new IOnDoneCallback.Default();
    @Nullable
    private static volatile StockSettingsSource settingsSource;
    private static WeakReference<Object> activeConnection = new WeakReference<>(null);
    private static final Map<String, Choice> pendingChoices = new HashMap<>();

    private AndroidAutoSettingsTab() {}

    @MainThread
    public static void setSettingsSource(StockSettingsSource source) {
        settingsSource = source;
        activeConnection.clear();
        pendingChoices.clear();
    }

    static MediaItem settingsTab() {
        Uri icon = Uri.parse("android.resource://" + Utils.getContext().getPackageName()
                + "/drawable/yt_outline_experimental_gear_vd_theme_24");
        MediaDescriptionCompat description = new MediaDescriptionCompat(SETTINGS_MEDIA_ID,
                getString("settings"), null, null, null, icon, null, null);
        return new MediaItem(description, MediaItem.FLAG_BROWSABLE);
    }

    private static Map<String, Setting> loadSettings(StockSettingsSource source, Context context) {
        Map<String, String> titles = new HashMap<>();
        // The title resource name keeps the row's media ID stable when the app language changes.
        ListTemplate template = source.patch_getSettingsTemplate(context, id -> {
            String key = context.getResources().getResourceEntryName(id);
            titles.put(key, context.getString(id));
            return key;
        });
        Map<String, Setting> settings = new LinkedHashMap<>();
        for (Object item : template.getSingleList().getItems()) {
            if (item instanceof Row row) {
                String key = row.getTitle().toString();
                String title = titles.get(key);
                if (title == null) {
                    title = key;
                    key = "title/" + Uri.encode(title);
                }
                settings.put(key, new Setting(key, title, TextUtils.join(" · ", row.getTexts()), row.getToggle()));
            }
        }
        return settings;
    }

    private static List<MediaItem> items(Collection<Setting> settings) {
        List<MediaItem> rows = new ArrayList<>(settings.size());
        for (Setting setting : settings) {
            String subtitle = setting.summary;
            if (setting.toggle != null) {
                String state = stateLabel(setting.toggle.isChecked());
                subtitle = subtitle.isEmpty() ? state : state + " · " + subtitle;
            }
            rows.add(row(setting.rowId(), setting.title, subtitle, setting.toggle != null));
        }
        return rows;
    }

    private record Choice(boolean value, String id) {}

    @MainThread
    static List<MediaItem> open(Context context, String id, Object connection, Runnable reloadSettings)
            throws RemoteException {
        StockSettingsSource source = settingsSource;
        if (source == null) return Collections.emptyList();
        if (activeConnection.get() != connection) {
            activeConnection = new WeakReference<>(connection);
            pendingChoices.clear();
        }
        var settings = loadSettings(source, context).values();
        if (SETTINGS_MEDIA_ID.equals(id)) return items(settings);
        for (Setting setting : settings) {
            Choice choice = pendingChoices.get(setting.key);
            if (id.equals(setting.rowId())) {
                if (setting.toggle == null) return Collections.singletonList(setting.status());
                boolean checked = setting.toggle.isChecked();
                if (choice == null || choice.value == checked) {
                    choice = new Choice(!checked, setting.choicePrefix() + UUID.randomUUID());
                    pendingChoices.put(setting.key, choice);
                }
                return Collections.singletonList(row(choice.id, stateLabel(choice.value), setting.title, true));
            }
            if (!id.startsWith(setting.choicePrefix())) continue;
            // Android Auto can reopen the last choice after the app restarts.
            // Remove the ID before applying the change so reopening it only shows the current value.
            if (choice != null && id.equals(choice.id)) {
                pendingChoices.remove(setting.key);
                if (setting.toggle != null && setting.toggle.isChecked() != choice.value) {
                    ((StockToggle) (Object) setting.toggle).patch_getDelegate().patch_getListener()
                            .onCheckedChange(choice.value, TOGGLE_DONE);
                    AndroidAutoPatch.mainHandler.post(() -> {
                        if (activeConnection.get() == connection) reloadSettings.run();
                    });
                    var refreshed = loadSettings(source, context);
                    setting = refreshed.get(setting.key);
                    if (setting == null) return items(refreshed.values());
                }
            }
            return Collections.singletonList(setting.status());
        }
        // Android Auto can reopen an old ID after YouTube Music renames or removes a setting.
        return items(settings);
    }

    private record Setting(String key, String title, String summary, @Nullable Toggle toggle) {
        String rowId() {
            return ROW_PREFIX + key;
        }

        String choicePrefix() {
            return rowId() + ":choice:";
        }

        MediaItem status() {
            return row(SETTINGS_MEDIA_ID + ":status:" + key, title, toggle == null ? summary : stateLabel(toggle.isChecked()), false);
        }
    }

    private static String stateLabel(boolean checked) {
        return getString(checked ? "morphe_music_android_auto_setting_on" : "morphe_music_android_auto_setting_off");
    }

    private static MediaItem row(String id, String title, @Nullable String subtitle, boolean browsable) {
        var description = new MediaDescriptionCompat(id, title, subtitle, null, null, null, null, null);
        return new MediaItem(description, browsable ? MediaItem.FLAG_BROWSABLE : 0);
    }
}
