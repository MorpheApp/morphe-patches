/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.view.Window;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.patches.VideoEffectsPatch;
import app.morphe.extension.shared.patches.VideoEffectsPatch.CustomEffect;
import app.morphe.extension.shared.patches.VideoEffectsPatch.VideoEffect;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference.ListPreferenceArrayAdapter;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.LoadVideoPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PipDismissHelper;

@SuppressWarnings("unused")
public class VideoEffectFlyoutButton {

    /**
     * Share of the screen height the list may take up, so the video above it stays visible.
     */
    private static final int MAX_LIST_HEIGHT_PERCENT = 40;

    /**
     * Adds a menu item to the player settings flyout menu.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex, boolean isTopFlyout) {
        if (Settings.VIDEO_EFFECT_FLYOUT_BUTTON.get() && isTopFlyout) {
            nextButtonIndex = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    ResourceUtils.getDrawable("morphe_ic_video_effect"),
                    str("morphe_video_effect_title"),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        showDialog(v.getContext());
                    },
                    nextButtonIndex
            );
        }
        return nextButtonIndex;
    }

    /**
     * Shows the effects in a list that stays open, so effects can be tried one after another
     * while the video plays.
     */
    private static void showDialog(Context context) {
        try {
            List<CharSequence> entryList = new ArrayList<>();
            List<CharSequence> entryValueList = new ArrayList<>();
            for (VideoEffect effect : VideoEffect.values()) {
                // Custom effects are listed by their names.
                if (effect == VideoEffect.CUSTOM) continue;
                entryList.add(str("morphe_video_effect_entry_" + effect.name().toLowerCase(Locale.US)));
                entryValueList.add(effect.name());
            }
            for (CustomEffect effect : VideoEffectsPatch.getCustomEffects()) {
                entryList.add(effect.name);
                entryValueList.add(VideoEffectsPatch.CUSTOM_EFFECT_NAME_PREFIX + effect.name);
            }
            CharSequence[] entries = entryList.toArray(new CharSequence[0]);
            CharSequence[] entryValues = entryValueList.toArray(new CharSequence[0]);

            SheetBottomDialog.DraggableLinearLayout mainLayout =
                    SheetBottomDialog.createMainLayout(context, LegacyPlayerControlButton.getDialogBackgroundColor());
            mainLayout.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);
            mainLayout.addView(SheetBottomDialog.createTitle(
                    context, str("morphe_video_effect_title"), ThemeUtils.getAppForegroundColor()));

            ListView listView = new ListView(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                            Dim.pctHeight(MAX_LIST_HEIGHT_PERCENT), MeasureSpec.AT_MOST));
                }
            };
            listView.setDivider(null);
            ListPreferenceArrayAdapter adapter = new ListPreferenceArrayAdapter(
                    context, entries, entryValues, VideoEffectsPatch.getSelectedEffectEntryValue());
            listView.setAdapter(adapter);
            mainLayout.addView(listView);

            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(
                    context, mainLayout, LegacyPlayerControlButton.fadeInDuration);

            listView.setOnItemClickListener((parent, view, position, id) -> {
                try {
                    String value = entryValues[position].toString();
                    VideoEffect effect = VideoEffectsPatch.saveCustomEffectNameOfEntryValue(value);
                    Settings.VIDEO_EFFECT.save(effect);

                    if (effect != VideoEffect.NONE && !VideoEffectsPatch.isCurrentVideoDrawnWithEffects()) {
                        dialog.dismiss();
                        LoadVideoPatch.initializeReloadVideo();
                        return;
                    }

                    adapter.setSelectedValue(value);
                    adapter.notifyDataSetChanged();
                } catch (Exception ex) {
                    Logger.printException(() -> "Video effect selection failure", ex);
                }
            });

            // Keep the video visible behind the list, to see the effects while choosing.
            Window window = dialog.getWindow();
            if (window != null) {
                window.setDimAmount(0f);
            }

            PipDismissHelper.dismissOnPip(dialog);
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showDialog failure", ex);
        }
    }
}
