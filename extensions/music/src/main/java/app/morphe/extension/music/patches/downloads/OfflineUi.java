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
import android.graphics.Path;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.drawable.shapes.PathShape;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.function.Consumer;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.misc.MediaTime;
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

    /** Covers of rows are decoded at this size, a small sample of the stored cover. */
    private static final int ROW_ARTWORK_DP = 56;
    private static final int ARTWORK_CORNER_DP = 4;

    /** Icon buttons are square, otherwise their round ripple is drawn as a stretched oval. */
    static final int ICON_BUTTON_DP = 48;

    private static final float DISABLED_BUTTON_ALPHA = .35f;

    private static final int SEEK_BAR_HEIGHT_DP = 3;

    /** The bar is drawn thin but stays easy to hit. */
    static final int SEEK_BAR_TOUCH_DP = 18;
    private static final int SEEK_THUMB_DP = 12;

    /** How far a row that is being dragged is lifted off the list. */
    static final int DRAG_ELEVATION_DP = 8;
    private static final int DRAG_ICON_DP = 24;

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

    static void setButtonEnabled(View button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : DISABLED_BUTTON_ALPHA);
    }

    /** Three horizontal bars, since the app has no drag handle icon of a usable size. */
    Drawable dragHandle() {
        Path bars = new Path();
        bars.addRect(3, 6, 21, 8, Path.Direction.CW);
        bars.addRect(3, 11, 21, 13, Path.Direction.CW);
        bars.addRect(3, 16, 21, 18, Path.Direction.CW);

        ShapeDrawable handle = new ShapeDrawable(new PathShape(bars, DRAG_ICON_DP, DRAG_ICON_DP));
        handle.setIntrinsicWidth(dp(DRAG_ICON_DP));
        handle.setIntrinsicHeight(dp(DRAG_ICON_DP));
        handle.getPaint().setColor(foreground());
        return handle;
    }

    /**
     * @param gutterDp Space on both sides, so the bar reads as a control rather than an edge.
     */
    SeekBar seekBar(int gutterDp) {
        SeekBar bar = new SeekBar(context);
        bar.setPadding(dp(gutterDp), 0, dp(gutterDp), 0);
        bar.setProgressDrawable(seekBarDrawable());
        bar.setBackground(null);
        bar.setSplitTrack(false);
        return bar;
    }

    void showSeekThumb(SeekBar bar) {
        ShapeDrawable thumb = new ShapeDrawable(new OvalShape());
        thumb.setIntrinsicWidth(dp(SEEK_THUMB_DP));
        thumb.setIntrinsicHeight(dp(SEEK_THUMB_DP));
        thumb.getPaint().setColor(foreground());
        bar.setThumb(thumb);
        bar.setThumbOffset(dp(SEEK_THUMB_DP) / 2);
    }

    /**
     * The thickness comes from the drawable because {@code setMinHeight} and
     * {@code setMaxHeight} are only available from API 29.
     */
    private Drawable seekBarDrawable() {
        final int foreground = foreground();

        // Dimming the foreground works on any background, unlike lightening a black one.
        Drawable track = roundedBar(Utils.adjustColorBrightness(foreground, 0.3f));
        Drawable played = roundedBar(foreground);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(played, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        // A progress drawable is stretched to the view, and the thickness can only be capped
        // with setMaxHeight from API 29, so the band is inset to the height it should have.
        final int inset = (dp(SEEK_BAR_TOUCH_DP) - dp(SEEK_BAR_HEIGHT_DP)) / 2;
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        return layers;
    }

    private Drawable roundedBar(int color) {
        return CustomDialog.createRoundedBackground(SEEK_BAR_HEIGHT_DP, color);
    }

    /** A cover view showing the placeholder, for {@link OfflineArtworkLoader} to fill. */
    ImageView artworkView() {
        ImageView view = new ImageView(context);
        view.setBackgroundColor(Utils.adjustColorBrightness(foreground(), 0.2f));
        roundCorners(view);
        showArtwork(view, null);
        return view;
    }

    /**
     * Shows a cover, or the placeholder icon when there is none. A view of a list row can switch
     * between the two as it is reused for other tracks, so every property is set each time.
     */
    void showArtwork(ImageView view, @Nullable Bitmap bitmap) {
        if (bitmap != null) {
            view.clearColorFilter();
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setImageBitmap(bitmap);
        } else {
            view.setImageDrawable(
                    ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            view.setColorFilter(foreground());
            // Keeps the size of the icon on a cover of any size.
            view.setScaleType(ImageView.ScaleType.CENTER);
        }
    }

    /** Size the covers of rows are decoded at, which the mini player shares. */
    int rowArtworkPixels() {
        return dp(ROW_ARTWORK_DP);
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

    /** The views that show a track in a row, kept so a reused row can show another track. */
    record TrackInfoViews(ImageView artwork, TextView title, TextView detail) {
    }

    /** Adds the cover, title and details of a track to its row. */
    void addTrackInfo(LinearLayout row, OfflineTrack track) {
        bindTrackInfo(createTrackInfo(row), track);
    }

    /** Adds empty views for the cover, title and details of a track to a row. */
    TrackInfoViews createTrackInfo(LinearLayout row) {
        ImageView artwork = artworkView();
        row.addView(artwork, new LinearLayout.LayoutParams(dp(ROW_ARTWORK_DP), dp(ROW_ARTWORK_DP)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);

        TextView name = text("", 16, foreground());
        name.setSingleLine(true);

        TextView detail = text("", 13, secondary());
        detail.setSingleLine(true);

        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        return new TrackInfoViews(artwork, name, detail);
    }

    /** Shows a track in views made by {@link #createTrackInfo}. The cover is decoded later. */
    void bindTrackInfo(TrackInfoViews views, OfflineTrack track) {
        OfflineArtworkLoader.load(views.artwork(), track.artworkFile(), rowArtworkPixels(), this);
        views.title().setText(track.displayTitle());
        views.detail().setText(str("morphe_music_downloads_track_subtitle",
                track.displayArtist(), formatDuration(track.durationSeconds()),
                formatSize(track.sizeBytes())));
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

    private static String formatDuration(int seconds) {
        // Unknown durations are not shown.
        return seconds <= 0 ? "" : MediaTime.format(seconds * 1000L);
    }

    static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }
}
