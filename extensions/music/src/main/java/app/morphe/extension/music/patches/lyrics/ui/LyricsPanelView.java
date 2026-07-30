/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;

/**
 * Third party lyrics, drawn over the content of the lyrics engagement panel.
 *
 * <p>Hides itself when there are no lyrics to show, which leaves the built-in
 * lyrics visible underneath.
 */
public final class LyricsPanelView extends FrameLayout implements LyricsManager.Listener {

    /** How often the highlighted line is re-evaluated while playing. */
    private static final long TICK_INTERVAL_MILLISECONDS = 120;

    private static final float INACTIVE_LINE_ALPHA = 0.45f;

    /** Applied on top of the footer style, which alone is brighter than the app draws it. */
    private static final float FOOTER_ALPHA = 0.6f;

    /** Fade length when the highlight moves from one line to the next. */
    private static final long HIGHLIGHT_FADE_DURATION_MILLISECONDS = 200;

    /** How long auto scrolling stays off after the user touches the panel. */
    private static final long MANUAL_SCROLL_PAUSE_MILLISECONDS = 5000;

    /** Ticks between two overlay state checks, roughly one second. */
    private static final int SYNC_TICK_COUNT = 8;

    /** Own string, because the app string {@code lyrics_source} exists in English only. */
    private static final String LYRICS_SOURCE_KEY = "morphe_music_lyrics_source_label";

    /** App style for the source line, 14sp, as drawn by the timed lyrics panel. */
    private static final String APP_FOOTER_STYLE = "TextAppearance.YouTubeMusic.Body3.Translucent";

    /** Color the app uses for primary text. */
    private static final String APP_PRIMARY_TEXT_COLOR = "ytm_text_color_primary";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ScrollView scrollView;
    private final LinearLayout linesContainer;
    private final TextView footerView;
    private final ProgressBar progressBar;

    private final List<TextView> lineViews = new ArrayList<>();

    @Nullable
    private Lyrics lyrics;

    private int highlightedIndex = -1;

    /** Whether this panel should currently cover the built-in content. */
    private boolean overlayVisible;

    private int tickCount;

    /** Suppresses auto scrolling for a while after the user scrolls manually. */
    private long userScrollUntilUptimeMs;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                updateHighlight();

                // The app restores its own panel content asynchronously, so the
                // wanted state is reapplied regularly rather than only on changes.
                if (++tickCount % SYNC_TICK_COUNT == 0) {
                    syncOverlay();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Lyrics tick failure", ex);
            }
            handler.postDelayed(this, TICK_INTERVAL_MILLISECONDS);
        }
    };

    public LyricsPanelView(@NonNull Context context) {
        super(context);

        final int horizontalPadding = dp(32);
        final int verticalPadding = dp(16);

        linesContainer = new LinearLayout(context);
        linesContainer.setOrientation(LinearLayout.VERTICAL);
        linesContainer.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        footerView = new TextView(context);
        applyFooterStyle(footerView);
        footerView.setPadding(0, dp(24), 0, dp(48));
        footerView.setVisibility(GONE);
        linesContainer.addView(footerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.addView(linesContainer, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        addView(scrollView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(context);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(GONE);
        addView(progressBar, new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
    }

    @Override
    public boolean onInterceptTouchEvent(@NonNull MotionEvent event) {
        // Any touch counts as manual interaction, so auto scrolling backs off
        // instead of fighting the user. The event itself is left untouched.
        userScrollUntilUptimeMs = SystemClock.uptimeMillis() + MANUAL_SCROLL_PAUSE_MILLISECONDS;
        return super.onInterceptTouchEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LyricsManager.getInstance().addListener(this);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        LyricsManager.getInstance().removeListener(this);
        handler.removeCallbacks(ticker);
    }

    @Override
    public void onLyricsChanged(@NonNull LyricsManager.State state, @Nullable Lyrics newLyrics) {
        try {
            lyrics = newLyrics;
            highlightedIndex = -1;
            userScrollUntilUptimeMs = 0;

            switch (state) {
                case LOADING:
                    showLoading();
                    setOverlayVisible(true);
                    break;
                case LOADED:
                    if (newLyrics == null || newLyrics.isEmpty()) {
                        setOverlayVisible(false);
                    } else {
                        showLyrics(newLyrics);
                        setOverlayVisible(true);
                    }
                    break;
                case NOT_FOUND:
                case ERROR:
                case IDLE:
                default:
                    // Nothing to show, so the built-in lyrics are left to take over.
                    clearLines();
                    setOverlayVisible(false);
                    break;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLyricsChanged failure", ex);
        }
    }

    /** Hides the built-in content along with showing this panel, so the two texts never overlap. */
    private void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
        applyOverlayVisibility();
    }

    /**
     * Reapplies the wanted state, because reopening the panel makes the app restore
     * its own content without any lyrics state change to react to.
     */
    public void syncOverlay() {
        applyOverlayVisibility();
    }

    private void applyOverlayVisibility() {
        setVisibility(overlayVisible ? VISIBLE : GONE);

        if (!(getParent() instanceof ViewGroup parent)) {
            return;
        }

        for (int i = 0; i < parent.getChildCount(); i++) {
            View sibling = parent.getChildAt(i);
            if (sibling == this) {
                continue;
            }
            sibling.setVisibility(overlayVisible ? GONE : VISIBLE);
        }
    }

    private void showLoading() {
        clearLines();
        footerView.setVisibility(GONE);
        scrollView.setVisibility(GONE);
        progressBar.setVisibility(VISIBLE);
    }

    private void showLyrics(@NonNull Lyrics newLyrics) {
        clearLines();
        progressBar.setVisibility(GONE);
        scrollView.setVisibility(VISIBLE);

        final Context context = getContext();
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int foregroundColor = lineTextColor();
        final boolean tapToSeek = newLyrics.synced() && Settings.LYRICS_TAP_TO_SEEK.get();

        for (int i = 0; i < newLyrics.lines().size(); i++) {
            LyricsLine line = newLyrics.lines().get(i);

            TextView lineView = new TextView(context);
            // An empty line is an instrumental break, which a note shows better than a gap.
            lineView.setText(line.text().isEmpty() ? "♪" : line.text());
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
            lineView.setTextColor(foregroundColor);
            lineView.setAlpha(newLyrics.synced() ? INACTIVE_LINE_ALPHA : 1f);
            lineView.setPadding(0, dp(8), 0, dp(8));
            lineView.setTypeface(null, Typeface.BOLD);

            if (tapToSeek) {
                final long seekTime = line.startTimeMs();
                lineView.setOnClickListener(view -> {
                    if (!VideoInformation.seekTo(seekTime)) {
                        Logger.printDebug(() -> "Seek to lyrics line failed: " + seekTime);
                    }
                    userScrollUntilUptimeMs = 0;
                });
            }

            // Inserted before the last child, because the footer was added first
            // and has to stay below the lyrics.
            linesContainer.addView(lineView, linesContainer.getChildCount() - 1,
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
            lineViews.add(lineView);
        }

        footerView.setText(sourceText(newLyrics.providerName()));
        footerView.setVisibility(VISIBLE);

        scrollView.scrollTo(0, 0);
    }

    private void clearLines() {
        for (TextView lineView : lineViews) {
            // A running fade would otherwise keep a reference to a removed view.
            lineView.animate().cancel();
            linesContainer.removeView(lineView);
        }
        lineViews.clear();
        highlightedIndex = -1;
    }

    private void updateHighlight() {
        Lyrics current = lyrics;
        if (current == null || !current.synced() || lineViews.isEmpty()) {
            return;
        }

        LyricsManager manager = LyricsManager.getInstance();
        final int index = current.indexForPosition(manager.getPositionMs(), highlightedIndex);
        if (index == highlightedIndex) {
            return;
        }

        if (highlightedIndex >= 0 && highlightedIndex < lineViews.size()) {
            fadeTo(lineViews.get(highlightedIndex), INACTIVE_LINE_ALPHA);
        }
        highlightedIndex = index;

        if (index < 0 || index >= lineViews.size()) {
            return;
        }

        TextView activeView = lineViews.get(index);
        fadeTo(activeView, 1f);

        if (SystemClock.uptimeMillis() < userScrollUntilUptimeMs) {
            return;
        }

        // Keep the active line in the upper third, which is where the eye expects it.
        final int target = activeView.getTop() + linesContainer.getTop()
                - scrollView.getHeight() / 3;
        scrollView.smoothScrollTo(0, Math.max(0, target));
    }

    /** Eases the highlight between lines the way the built-in panel does. */
    private static void fadeTo(@NonNull TextView lineView, float alpha) {
        lineView.animate().cancel();
        lineView.animate()
                .alpha(alpha)
                .setDuration(HIGHLIGHT_FADE_DURATION_MILLISECONDS)
                .start();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    /** Takes size and color from the app style instead of approximating them. */
    private static void applyFooterStyle(@NonNull TextView footer) {
        // The style resolves to 67% white, but the built-in panel draws this line
        // dimmer than even its inactive lyrics, which this brings it down to.
        footer.setAlpha(FOOTER_ALPHA);

        final int styleId = ResourceUtils.getIdentifier(ResourceType.STYLE, APP_FOOTER_STYLE);
        if (styleId != 0) {
            footer.setTextAppearance(styleId);
            return;
        }

        Logger.printDebug(() -> "App is missing " + APP_FOOTER_STYLE);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        footer.setTextColor(Utils.getAppForegroundColor());
    }

    /**
     * Color the app uses for lyrics text, falling back to the generic foreground color.
     */
    private static int lineTextColor() {
        final int colorId = ResourceUtils.getIdentifier(ResourceType.COLOR, APP_PRIMARY_TEXT_COLOR);
        if (colorId == 0) {
            return Utils.getAppForegroundColor();
        }
        return ResourceUtils.getColor(APP_PRIMARY_TEXT_COLOR, Utils.getAppForegroundColor());
    }

    @NonNull
    private static String sourceText(@NonNull String providerName) {
        return String.format(StringRef.str(LYRICS_SOURCE_KEY), providerName);
    }
}
