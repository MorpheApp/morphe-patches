/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.function.Consumer;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/** Views and formatting shared by the downloads screen and the playlist page. */
final class OfflineUi {

    /** Dimming the foreground gives a readable secondary on any background. */
    private static final float SECONDARY_DIM = 0.72f;

    /** How much the background of the playing row is lifted out of the surrounding one. */
    private static final float PLAYING_ROW_BRIGHTNESS = 1.35f;

    private static final int ROW_CORNER_DP = 8;
    private static final int PILL_CORNER_DP = 20;
    private static final int PILL_GAP_DP = 8;
    private static final int PILL_FILL_ALPHA = 26;
    private static final int PILL_STROKE_ALPHA = 65;
    private static final int RIPPLE_ALPHA = 60;

    /** Rows are 56dp, so a sample of the stored cover is already more than enough. */
    static final int ROW_ARTWORK_PIXELS = 256;

    private static final int ROW_ARTWORK_DP = 56;
    private static final int ARTWORK_CORNER_DP = 4;

    /** Icon buttons are square, otherwise their round ripple is drawn as a stretched oval. */
    static final int ICON_BUTTON_DP = 48;

    private final Context context;

    OfflineUi(Context context) {
        this.context = context;
    }

    int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    TextView text(String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    ImageButton icon(String drawable, String description) {
        ImageButton button = icon(ResourceUtils.getDrawable(drawable), description);
        // Icons of the app carry no color of their own.
        button.setColorFilter(foreground());
        return button;
    }

    /** For drawables that already carry the colors of Morphe. */
    ImageButton icon(Drawable drawable, String description) {
        ImageButton button = new ImageButton(context);
        button.setImageDrawable(drawable);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));

        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        return button;
    }

    ImageView artworkView(@Nullable Bitmap bitmap) {
        ImageView view = new ImageView(context);
        view.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        roundCorners(view);

        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            view.setImageDrawable(
                    ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            view.setColorFilter(foreground());
            view.setPadding(dp(16), dp(16), dp(16), dp(16));
        }
        return view;
    }

    /** Matches the softly rounded covers the app uses on the home feed. */
    private void roundCorners(View view) {
        final float radius = dp(ARTWORK_CORNER_DP);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setRoundRect(0, 0, outlined.getWidth(), outlined.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    /**
     * The ripple is masked to the card, so it does not spill into the neighboring rows.
     *
     * @param highlighted If the card is lifted out of the background.
     */
    Drawable rowBackground(boolean highlighted) {
        Drawable card = highlighted
                ? CustomDialog.createRoundedBackground(ROW_CORNER_DP, Utils.adjustColorBrightness(
                        ThemeUtils.getAppBackgroundColor(), PLAYING_ROW_BRIGHTNESS))
                : null;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    /** An empty row, for the caller to add {@link #addTrackInfo} and any actions to. */
    LinearLayout trackRow() {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        return row;
    }

    /** Adds the cover, title and details of a track to its row. */
    void addTrackInfo(LinearLayout row, OfflineTrack track) {
        row.addView(artworkView(track.artwork(ROW_ARTWORK_PIXELS)),
                new LinearLayout.LayoutParams(dp(ROW_ARTWORK_DP), dp(ROW_ARTWORK_DP)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);

        TextView name = text(track.displayTitle(), 16, foreground());
        name.setSingleLine(true);

        TextView detail = text(str("morphe_music_downloads_track_subtitle",
                track.displayArtist(), formatDuration(track.durationSeconds()),
                formatSize(track.audioFile().length())), 13, secondary());
        detail.setSingleLine(true);

        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    }

    /** A rounded ripple button that adapts to any theme background. */
    TextView pillButton(String label, boolean primary) {
        final int foreground = foreground();
        TextView button = text(label, 13, primary ? background() : foreground);
        button.setPadding(dp(14), dp(6), dp(14), dp(6));

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(PILL_CORNER_DP));
        if (primary) {
            shape.setColor(foreground);
        } else {
            shape.setColor(withAlpha(foreground, PILL_FILL_ALPHA));
            shape.setStroke(dp(1), withAlpha(foreground, PILL_STROKE_ALPHA));
        }
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), shape,
                CustomDialog.createRoundedBackground(PILL_CORNER_DP, Color.WHITE)));
        return button;
    }

    /** A row of pill buttons, which scrolls if longer labels do not fit the screen. */
    View pillBar(View... pills) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (View pill : pills) {
            // A margin instead of a spacer view, so a hidden button leaves no gap behind.
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMarginEnd(dp(PILL_GAP_DP));
            row.addView(pill, params);
        }

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    /**
     * @param onName Called with the entered name, which is trimmed and not empty.
     */
    void showPlaylistNameDialog(String title, String okText, String initialName,
                                Consumer<String> onName) {
        EditText input = new EditText(context);
        input.setHint(str("morphe_music_downloads_playlist_name_hint"));
        input.setHintTextColor(secondary());
        input.setText(initialName);
        input.setSelection(initialName.length());
        input.setSingleLine(true);

        CustomDialog.create(context, title, null, input, okText, () -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Utils.showToastShort(str("morphe_music_downloads_playlist_name_empty"));
                return;
            }
            onName.accept(name);
        }, () -> {
        }, null, null, false).first.show();
    }

    static String trackCount(int count) {
        return count == 1
                ? str("morphe_music_downloads_playlist_track_count_one")
                : str("morphe_music_downloads_playlist_track_count", count);
    }

    static int foreground() {
        return ThemeUtils.getAppForegroundColor();
    }

    static int secondary() {
        return Utils.adjustColorBrightness(foreground(), SECONDARY_DIM);
    }

    static int background() {
        return ThemeUtils.getAppBackgroundColor();
    }

    static int rippleColor() {
        return withAlpha(foreground(), RIPPLE_ALPHA);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static String formatDuration(int seconds) {
        if (seconds <= 0) return "";
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }
}
