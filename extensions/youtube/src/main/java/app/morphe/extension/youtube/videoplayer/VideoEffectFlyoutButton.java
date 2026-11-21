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

import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference.ListPreferenceArrayAdapter;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.LoadVideoPatch;
import app.morphe.extension.youtube.patches.VideoEffectsPatch;
import app.morphe.extension.youtube.patches.VideoEffectsPatch.VideoEffect;
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
            VideoEffect[] effects = VideoEffect.values();
            CharSequence[] entries = new CharSequence[effects.length];
            CharSequence[] entryValues = new CharSequence[effects.length];
            for (int i = 0; i < effects.length; i++) {
                entries[i] = str("morphe_video_effect_entry_" + effects[i].name().toLowerCase(Locale.US));
                entryValues[i] = effects[i].name();
            }

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
                    context, entries, entryValues, Settings.VIDEO_EFFECT.get().name());
            listView.setAdapter(adapter);
            mainLayout.addView(listView);

            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(
                    context, mainLayout, LegacyPlayerControlButton.fadeInDuration);

            listView.setOnItemClickListener((parent, view, position, id) -> {
                try {
                    VideoEffect effect = effects[position];
                    Settings.VIDEO_EFFECT.save(effect);

                    if (effect != VideoEffect.NONE && !VideoEffectsPatch.isCurrentVideoDrawnWithEffects()) {
                        dialog.dismiss();
                        LoadVideoPatch.initializeReloadVideo();
                        return;
                    }

                    adapter.setSelectedValue(effect.name());
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
