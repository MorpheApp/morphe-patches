/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.view.Window;
import android.widget.ListView;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.VideoEffectsPatch.CustomEffect;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference.ListPreferenceArrayAdapter;
import app.morphe.extension.shared.settings.preference.VideoEffectEditor;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;

/**
 * The effects in a list that stays open, so effects can be tried one after another
 * while the video plays. A long press on an effect opens its code.
 */
public final class VideoEffectDialog {

    /**
     * Share of the screen height the list may take up, so the video above it stays visible.
     */
    private static final int MAX_LIST_HEIGHT_PERCENT = 40;

    private VideoEffectDialog() {
    }

    /**
     * @param backgroundColor   Color of the sheet, or null for the default color.
     * @param onVideoNeedsReload Called with the dialog when an effect is selected for a video that
     *                          started without an effect, which shows effects only after it loads again.
     * @return The dialog, which is not shown yet.
     */
    public static SheetBottomDialog.SlideDialog create(Context context,
                                                       @Nullable @ColorInt Integer backgroundColor,
                                                       int animationDuration,
                                                       Consumer<SheetBottomDialog.SlideDialog> onVideoNeedsReload) {
        SheetBottomDialog.DraggableLinearLayout mainLayout =
                SheetBottomDialog.createMainLayout(context, backgroundColor);
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
        listView.setAdapter(createAdapter(context));
        mainLayout.addView(listView);

        SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(
                context, mainLayout, animationDuration);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            try {
                ListPreferenceArrayAdapter adapter = (ListPreferenceArrayAdapter) parent.getAdapter();
                String value = adapter.getEntryValue(position).toString();
                SharedYouTubeSettings.VIDEO_EFFECT.save(value);
                adapter.setSelectedValue(value);
                adapter.notifyDataSetChanged();

                if (!value.isEmpty() && !VideoEffectsPatch.isCurrentVideoDrawnWithEffects()) {
                    onVideoNeedsReload.accept(dialog);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Video effect selection failure", ex);
            }
        });

        // Long press opens the code of the effect, to change it while the video plays.
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            try {
                ListPreferenceArrayAdapter adapter = (ListPreferenceArrayAdapter) parent.getAdapter();
                CustomEffect effect = VideoEffectsPatch.findCustomEffect(
                        adapter.getEntryValue(position).toString());
                // Off has no code.
                if (effect == null) return false;

                // The name may have changed.
                VideoEffectEditor.show(context, effect, () -> listView.setAdapter(createAdapter(context)));
                return true;
            } catch (Exception ex) {
                Logger.printException(() -> "Video effect long press failure", ex);
                return false;
            }
        });

        // Keep the video visible behind the list, to see the effects while choosing.
        Window window = dialog.getWindow();
        if (window != null) {
            window.setDimAmount(0f);
        }

        return dialog;
    }

    private static ListPreferenceArrayAdapter createAdapter(Context context) {
        return new ListPreferenceArrayAdapter(context,
                VideoEffectsPatch.getEffectEntries(),
                VideoEffectsPatch.getEffectEntryValues(),
                SharedYouTubeSettings.VIDEO_EFFECT.get());
    }
}
