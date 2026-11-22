/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5153
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
 * Shows YouTube Music's car settings as an Android Auto browse tab, because Android Auto
 * rejects the stock settings screen of a patched app.
 * <p>
 * A browse folder cannot show a switch. Opening a toggle row shows the new value,
 * and opening that value applies it with the stock toggle listener.
 */
public final class AndroidAutoSettingsTab {
    /** Added during patching to the controller of the stock car settings screen. */
    public interface SettingsTemplateSource {
        /** Builds the stock settings screen, using the given context for resources. */
        ListTemplate patch_getSettingsTemplate(Context context);
    }

    /** Added during patching to {@link Toggle}. */
    public interface StockToggle {
        StockToggleDelegate patch_getDelegate();
    }

    /** Added during patching to the toggle's delegate. */
    public interface StockToggleDelegate {
        IOnCheckedChangeListener patch_getListener();
    }

    static final String SETTINGS_MEDIA_ID = "morphe:aa:settings";
    private static final String SETTINGS_PREFIX = SETTINGS_MEDIA_ID + ":";
    private static final String ROW_PREFIX = SETTINGS_PREFIX + "row:";
    private static final String STATUS_PREFIX = SETTINGS_PREFIX + "status:";
    private static final String TEXT_SEPARATOR = " · ";
    private static final IOnDoneCallback TOGGLE_DONE = new IOnDoneCallback.Default();

    @Nullable
    private static volatile SettingsTemplateSource settingsSource;
    // Main thread only.
    private static WeakReference<Object> activeConnection = new WeakReference<>(null);
    // Main thread only. The value each toggle row offers, by setting title.
    private static final Map<String, Choice> pendingChoices = new HashMap<>();

    private AndroidAutoSettingsTab() {
    }

    /** Injection point. Called when MusicBrowserService starts. */
    @MainThread
    public static void setSettingsSource(SettingsTemplateSource source) {
        settingsSource = source;
        activeConnection.clear();
        pendingChoices.clear();
    }

    static boolean isSettingsMediaId(@Nullable String mediaId) {
        return mediaId != null && (mediaId.equals(SETTINGS_MEDIA_ID) || mediaId.startsWith(SETTINGS_PREFIX));
    }

    static MediaItem settingsTab() {
        MediaDescriptionCompat description = new MediaDescriptionCompat(SETTINGS_MEDIA_ID,
                getString("settings"), null, null, null,
                Uri.parse(AndroidAutoPatch.outlineIconUri("gear")), null, null);
        return new MediaItem(description, MediaItem.FLAG_BROWSABLE);
    }

    /**
     * @param reloadSettings Reloads the settings list on the connection.
     * @return The children of a settings media ID.
     */
    @MainThread
    static List<MediaItem> open(Context context, String mediaId, Object connection, Runnable reloadSettings)
            throws RemoteException {
        SettingsTemplateSource source = settingsSource;
        if (source == null) return Collections.emptyList();
        if (activeConnection.get() != connection) {
            activeConnection = new WeakReference<>(connection);
            pendingChoices.clear();
        }

        Collection<Setting> settings = loadSettings(source, context).values();
        if (!mediaId.equals(SETTINGS_MEDIA_ID)) {
            for (Setting setting : settings) {
                if (mediaId.equals(setting.rowId())) {
                    return showChoice(setting);
                }
                if (mediaId.startsWith(setting.choicePrefix())) {
                    return applyChoice(source, context, setting, mediaId, reloadSettings);
                }
            }
        }
        // Android Auto can reopen an old ID after YouTube Music renames or removes a setting.
        return settingsList(settings);
    }

    private static List<MediaItem> showChoice(Setting setting) {
        Toggle toggle = setting.toggle;
        if (toggle == null) return Collections.singletonList(setting.status());

        boolean checked = toggle.isChecked();
        Choice choice = pendingChoices.get(setting.title);
        // Keep the offered ID while it still changes the setting.
        if (choice == null || choice.checked == checked) {
            choice = new Choice(!checked, setting.choicePrefix() + UUID.randomUUID());
            pendingChoices.put(setting.title, choice);
        }
        return Collections.singletonList(item(choice.mediaId, stateLabel(choice.checked), setting.title, true));
    }

    private static List<MediaItem> applyChoice(SettingsTemplateSource source, Context context, Setting setting,
            String mediaId, Runnable reloadSettings) throws RemoteException {
        Choice choice = pendingChoices.get(setting.title);
        // Android Auto can reopen the last choice after the app restarts, so only the offered ID applies.
        // Remove it first so reopening it only shows the current value.
        if (choice == null || !choice.mediaId.equals(mediaId)) return Collections.singletonList(setting.status());
        pendingChoices.remove(setting.title);

        Toggle toggle = setting.toggle;
        if (toggle == null || toggle.isChecked() == choice.checked) {
            return Collections.singletonList(setting.status());
        }
        // The listener runs right away on the main thread.
        ((StockToggle) (Object) toggle).patch_getDelegate().patch_getListener()
                .onCheckedChange(choice.checked, TOGGLE_DONE);
        // Update the list that Android Auto shows after going back.
        Utils.runOnMainThread(reloadSettings);

        // Show the saved value. YouTube Music can also hide the setting after the change.
        Map<String, Setting> updated = loadSettings(source, context);
        Setting updatedSetting = updated.get(setting.title);
        return updatedSetting == null
                ? settingsList(updated.values())
                : Collections.singletonList(updatedSetting.status());
    }

    /** @return The stock settings rows by title, in display order. */
    private static Map<String, Setting> loadSettings(SettingsTemplateSource source, Context context) {
        Map<String, Setting> settings = new LinkedHashMap<>();
        for (Object item : source.patch_getSettingsTemplate(context).getSingleList().getItems()) {
            if (item instanceof Row row) {
                String title = row.getTitle().toString();
                settings.put(title, new Setting(title, TextUtils.join(TEXT_SEPARATOR, row.getTexts()), row.getToggle()));
            }
        }
        return settings;
    }

    private static List<MediaItem> settingsList(Collection<Setting> settings) {
        List<MediaItem> items = new ArrayList<>(settings.size());
        for (Setting setting : settings) {
            String subtitle = setting.summary;
            if (setting.toggle != null) {
                String state = stateLabel(setting.toggle.isChecked());
                subtitle = subtitle.isEmpty() ? state : state + TEXT_SEPARATOR + subtitle;
            }
            items.add(item(setting.rowId(), setting.title, subtitle, setting.toggle != null));
        }
        return items;
    }

    private record Choice(boolean checked, String mediaId) {
    }

    private record Setting(String title, String summary, @Nullable Toggle toggle) {
        String rowId() {
            return ROW_PREFIX + title;
        }

        String choicePrefix() {
            return rowId() + ":choice:";
        }

        MediaItem status() {
            return item(STATUS_PREFIX + title, title,
                    toggle == null ? summary : stateLabel(toggle.isChecked()), false);
        }
    }

    private static String stateLabel(boolean checked) {
        return getString(checked ? "v7_preference_on" : "v7_preference_off");
    }

    private static MediaItem item(String mediaId, String title, @Nullable String subtitle, boolean browsable) {
        MediaDescriptionCompat description = new MediaDescriptionCompat(
                mediaId, title, subtitle, null, null, null, null, null);
        return new MediaItem(description, browsable ? MediaItem.FLAG_BROWSABLE : 0);
    }
}
