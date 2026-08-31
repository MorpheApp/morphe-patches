/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.music.patches.lyrics.LyricsPanelInstaller;
import app.morphe.extension.music.patches.lyrics.LyricsTranslator;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.ViewAnimations;

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

    /** Applied on top of the secondary color, which alone is brighter than the app draws it. */
    private static final float FOOTER_ALPHA = 0.6f;

    /** Fade length when the highlight moves from one line to the next. */
    private static final long HIGHLIGHT_FADE_DURATION_MILLISECONDS = 200;

    /** Fade length when the panel appears over the built-in content. */
    private static final long OVERLAY_FADE_DURATION_MILLISECONDS = 150;

    /** How long auto scrolling stays off after the user touches the panel. */
    private static final long MANUAL_SCROLL_PAUSE_MILLISECONDS = 5000;

    /** Own string, because the app string {@code lyrics_source} exists in English only. */
    private static final String LYRICS_SOURCE_KEY = "morphe_music_lyrics_source_label";

    /** Size of the source line under the lyrics. */
    private static final float FOOTER_TEXT_SIZE_SP = 16;

    private static final float BUTTON_TEXT_SIZE_SP = 14;

    /** Color the app uses for primary text. */
    private static final String APP_PRIMARY_TEXT_COLOR = "ytm_text_color_primary";

    /** Color the app uses for secondary text, applied to the translation. */
    private static final String APP_SECONDARY_TEXT_COLOR = "ytm_text_color_secondary";

    /** Background the app uses for the pill buttons under its own lyrics. */
    private static final String APP_BUTTON_BACKGROUND_COLOR = "ytm_color_white_at_10pct";

    /** Icons of the buttons the app draws under its own lyrics. */
    private static final String APP_TRANSLATE_ICON = "yt_outline_experimental_translate_vd_theme_24";

    /** Own icon, because the app ships no copy icon of its own. */
    private static final String COPY_ICON = "morphe_yt_copy_bold";

    /** Translation size relative to the lyrics line it belongs to. */
    private static final float TRANSLATION_RELATIVE_SIZE = 0.7f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ScrollView scrollView;
    private final LinearLayout linesContainer;
    private final TextView footerView;
    @Nullable
    private final TextView translateView;
    private final LinearLayout footerContainer;
    private final LinearLayout buttonRow;
    private final ProgressBar progressBar;

    /** One translated line per lyrics line, or {@code null} when showing the original only. */
    @Nullable
    private List<String> translatedLines;

    private final List<TextView> lineViews = new ArrayList<>();

    private final List<List<WordTiming>> lineWordSpans = new ArrayList<>();

    private int lastWordLineIndex = -1;

    private static final class WordTiming {
        final int start;
        final int end;
        final long startMs;
        final long endMs;

        WordTiming(int start, int end, long startMs, long endMs) {
            this.start = start;
            this.end = end;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    @Nullable
    private Lyrics lyrics;

    private int highlightedIndex = -1;

    private boolean wordSyncWasEnabled = true;

    /** Whether this panel should currently cover the built-in content. */
    private boolean overlayVisible;

    /** Built-in views hidden by this panel, so that only what was hidden is shown again. */
    private final List<View> hiddenSiblings = new ArrayList<>();

    /** Suppresses auto scrolling for a while after the user scrolls manually. */
    private long userScrollUntilUptimeMs;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                updateHighlight();
                updateWordSync(LyricsManager.getInstance().getPositionMs());

                // The app restores its own panel content asynchronously, and switching
                // to another engagement panel gives no lyrics state change to react to,
                // so the wanted state is reapplied on every tick rather than on changes.
                syncOverlay();
            } catch (Exception ex) {
                Logger.printException(() -> "Lyrics tick failure", ex);
            }
            handler.postDelayed(this, TICK_INTERVAL_MILLISECONDS);
        }
    };

    public LyricsPanelView(Context context) {
        super(context);

        final int horizontalPadding = Dim.dp32;
        final int verticalPadding = Dim.dp16;

        linesContainer = new LinearLayout(context);
        linesContainer.setOrientation(LinearLayout.VERTICAL);
        linesContainer.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        footerView = new TextView(context);
        applyFooterStyle(footerView);
        footerView.setVisibility(GONE);

        // Same order as the buttons the app draws under its own lyrics.
        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setVisibility(GONE);

        if (Settings.LYRICS_SHOW_COPY_BUTTON.get()) {
            TextView copyView = new TextView(context);
            applyButtonStyle(copyView, COPY_ICON);
            copyView.setText(str("morphe_music_lyrics_copy"));
            copyView.setOnClickListener(view -> onCopyClicked());
            buttonRow.addView(copyView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        if (Settings.LYRICS_SHOW_TRANSLATE_BUTTON.get()) {
            translateView = new TextView(context);
            applyButtonStyle(translateView, APP_TRANSLATE_ICON);
            translateView.setOnClickListener(view -> onTranslateClicked());
            LinearLayout.LayoutParams translateParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            translateParams.setMarginStart(Dim.dp12);
            buttonRow.addView(translateView, translateParams);
        } else {
            translateView = null;
        }

        // The source line lives in a container of its own, so that lyrics lines can be
        // inserted before it without depending on how many views it holds.
        footerContainer = new LinearLayout(context);
        footerContainer.setOrientation(LinearLayout.VERTICAL);
        // The bottom padding keeps the last lines clear of the pinned buttons.
        footerContainer.setPadding(0, Dim.dp24, 0, Dim.dp(200));
        footerContainer.addView(footerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        linesContainer.addView(footerContainer, new LinearLayout.LayoutParams(
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

        // Added last, and outside the scroll view, so the buttons stay pinned at the
        // bottom while the lyrics scroll behind them, the way the app does it.
        LayoutParams buttonRowParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        buttonRowParams.bottomMargin = Dim.dp40;
        addView(buttonRow, buttonRowParams);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
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
        // Only show the built-in lyrics again when the lyrics panel is actually gone,
        // not when another engagement panel (e.g. Related) has taken the container over.
        if (!LyricsPanelInstaller.isOtherPanelForeground()) {
            restoreHiddenSiblings();
        }
    }

    @Override
    public void onLyricsChanged(LyricsManager.State state, @Nullable Lyrics newLyrics) {
        try {
            lyrics = newLyrics;
            highlightedIndex = -1;
            userScrollUntilUptimeMs = 0;
            // The previous translation belongs to the previous track.
            translatedLines = null;

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
                        if (Settings.LYRICS_TRANSLATE.get()) {
                            onTranslateClicked();
                        }
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
     * its own content, and opening another engagement panel makes it take the same
     * container over, neither of which is a lyrics state change to react to.
     */
    public void syncOverlay() {
        applyOverlayVisibility();
    }

    private void applyOverlayVisibility() {
        // All engagement panels are built into the same container, and this view stays
        // in it when another one takes over, so covering the content is only correct
        // while the panel on screen is still the lyrics panel.
        final boolean lyricsPanelOpen = LyricsPanelInstaller.isLyricsPanelOpen();
        final boolean otherPanelOpen = LyricsPanelInstaller.isOtherPanelForeground();

        if (otherPanelOpen) {
            if (getParent() instanceof ViewGroup parent) {
                parent.removeView(this);
            }
            setVisibility(GONE);
            return;
        }

        final boolean visible = overlayVisible && lyricsPanelOpen;
        final boolean wasVisible = getVisibility() == VISIBLE;
        setVisibility(visible ? VISIBLE : GONE);

        // Appearing is faded in, so that covering the built-in lyrics reads as a
        // transition rather than as the panel being swapped out under the user.
        if (visible && !wasVisible) {
            animate().cancel();
            setAlpha(0f);
            animate().alpha(1f).setDuration(OVERLAY_FADE_DURATION_MILLISECONDS).start();
        }

        if (!(getParent() instanceof ViewGroup parent)) {
            return;
        }

        if (!visible) {
            restoreHiddenSiblings();
            return;
        }

        for (int i = 0; i < parent.getChildCount(); i++) {
            View sibling = parent.getChildAt(i);
            if (sibling == this
                    || sibling.getVisibility() != VISIBLE
                    || hiddenSiblings.contains(sibling)) {
                continue;
            }
            sibling.setVisibility(GONE);
            hiddenSiblings.add(sibling);
        }
    }

    /**
     * Shows the built-in views this panel hid, and only those, so that views the app
     * hides on its own and the content of a panel that took the container over are
     * left the way the app left them.
     */
    private void restoreHiddenSiblings() {
        for (View sibling : hiddenSiblings) {
            sibling.setVisibility(VISIBLE);
        }
        hiddenSiblings.clear();
    }

    private void showLoading() {
        clearLines();
        footerContainer.setVisibility(GONE);
        buttonRow.setVisibility(GONE);
        scrollView.setVisibility(GONE);
        progressBar.setVisibility(VISIBLE);
    }

    private void showLyrics(Lyrics newLyrics) {
        clearLines();
        progressBar.setVisibility(GONE);
        scrollView.setVisibility(VISIBLE);

        final Context context = getContext();
        final int textSize = Settings.LYRICS_TEXT_SIZE.get();
        final int foregroundColor = lineTextColor();
        final boolean tapToSeek = newLyrics.synced() && Settings.LYRICS_TAP_TO_SEEK.get();

        for (int i = 0; i < newLyrics.lines().size(); i++) {
            LyricsLine line = newLyrics.lines().get(i);
            List<WordTiming> timings = computeWordTimings(line);
            lineWordSpans.add(timings);

            TextView lineView = new TextView(context);
            // An empty line is an instrumental break, which a note shows better than a gap.
            lineView.setText(line.text().isEmpty() ? new SpannableString("♪")
                    : buildLineText(line, timings, i, Long.MIN_VALUE, false));
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
            lineView.setTextColor(foregroundColor);
            lineView.setAlpha(newLyrics.synced() ? INACTIVE_LINE_ALPHA : 1f);
            lineView.setPadding(0, Dim.dp8, 0, Dim.dp8);
            lineView.setTypeface(null, Typeface.BOLD);

            if (tapToSeek) {
                final long seekTime = line.startTimeMs();
                lineView.setOnClickListener(view -> {
                    final long videoSeekTime = LyricsManager.getInstance().toVideoTime(seekTime);
                    if (!VideoInformation.seekTo(videoSeekTime)) {
                        Logger.printDebug(() -> "Seek to lyrics line failed: " + videoSeekTime);
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

        footerView.setText(sourceText(newLyrics.providerName(), translatedLines != null));
        footerContainer.setVisibility(VISIBLE);
        footerView.setVisibility(VISIBLE);
        buttonRow.setVisibility(VISIBLE);
        updateTranslateLabel();

        scrollView.scrollTo(0, 0);
    }

    private static List<WordTiming> computeWordTimings(LyricsLine line) {
        if (!line.hasWords()) {
            return Collections.emptyList();
        }
        List<WordTiming> timings = new ArrayList<>(line.words().size());
        String text = line.text();
        int textLength = text.length();
        int offset = 0;
        for (Word word : line.words()) {
            String wordText = word.text();
            int wordLength = wordText.length();
            if (wordLength == 0) {
                continue;
            }
            int start = text.indexOf(wordText, offset);
            int len = wordLength;
            if (start < 0) {
                String trimmed = wordText.trim();
                if (!trimmed.isEmpty()) {
                    start = text.indexOf(trimmed, offset);
                    len = trimmed.length();
                }
            }
            if (start < 0) {
                // Unmatched word: advance past it so following words stay aligned,
                // rather than emitting a span that falls outside the line text.
                offset = Math.min(offset + len, textLength);
                continue;
            }
            int end = Math.min(start + len, textLength);
            if (start >= end) {
                continue;
            }
            timings.add(new WordTiming(start, end, word.startMs(), word.endMs()));
            offset = end;
        }
        return timings;
    }

    /**
     * Builds the displayed text for a line, appending the translation (when shown) in a
     * smaller, dimmer style and colouring each word sung or unsung for the karaoke
     * highlight.
     *
     * <p>A fresh {@link SpannableString} is returned on every call so that
     * {@link android.widget.TextView#setText(CharSequence)} performs a full re-layout
     * and repaint. Mutating an existing Spannable in place was not reliably redrawn by
     * this TextView, which left the highlight invisible.
     *
     * @param positionMs Current playback position, used to decide which words are sung.
     * @param allSung   When true every word is treated as sung, used to reset a line.
     */
    private Spannable buildLineText(LyricsLine line, List<WordTiming> timings, int index,
            long positionMs, boolean allSung) {
        String original = line.text();

        SpannableString text;
        List<String> translated = translatedLines;
        if (translated != null && index < translated.size()) {
            String translation = translated.get(index).trim();
            if (!translation.isEmpty() && !translation.equals(original)) {
                text = new SpannableString(original + "\n" + translation);
                final int start = original.length() + 1;
                text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), start, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new ForegroundColorSpan(secondaryTextColor()), start, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                text = new SpannableString(original);
            }
        } else {
            text = new SpannableString(original);
        }

        if (!timings.isEmpty() && Settings.LYRICS_WORD_SYNC.get()) {
            int sung = lineTextColor();
            int unsung = unsungWordColor();
            if (!allSung) {
                text.setSpan(new ForegroundColorSpan(unsung), 0, original.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            for (WordTiming timing : timings) {
                boolean isSung = allSung || positionMs >= timing.startMs;
                if (isSung) {
                    text.setSpan(new ForegroundColorSpan(sung),
                            timing.start, timing.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        return text;
    }

    private void onTranslateClicked() {
        try {
            // The saved translation state outlives the button, so a track change can
            // auto translate when there is no button to drive the translation from.
            if (translateView == null) {
                return;
            }

            Lyrics current = lyrics;
            TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
            if (current == null || track == null) {
                return;
            }

            if (translatedLines != null) {
                Settings.LYRICS_TRANSLATE.save(false);
                translatedLines = null;
                showLyrics(current);
                return;
            }

            Settings.LYRICS_TRANSLATE.save(true);
            translateView.setEnabled(false);
            translateView.setText(str("morphe_music_lyrics_translating"));

            LyricsTranslator.translate(track, current, Settings.LYRICS_SOURCE.get(), lines -> {
                translateView.setEnabled(true);

                // The track may have changed while the translation was in flight.
                if (lyrics != current) {
                    return;
                }

                translatedLines = lines;
                if (lines == null) {
                    Utils.showToastShort(str("morphe_music_lyrics_translate_failed"));
                }
                showLyrics(current);
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onTranslateClicked failure", ex);
        }
    }

    /**
     * Copies the lyrics to the clipboard, with the translation under each line when
     * it is shown, so what is copied matches what is on screen.
     */
    private void onCopyClicked() {
        try {
            Lyrics current = lyrics;
            if (current == null) {
                return;
            }

            //noinspection ExtractMethodRecommender
            List<String> translated = translatedLines;
            List<LyricsLine> lines = current.lines();
            StringBuilder text = new StringBuilder();
            for (int i = 0, linesSize = lines.size(); i < linesSize; i++) {
                if (i != 0) {
                    text.append('\n');
                }
                text.append(lines.get(i).text());

                if (translated != null && i < translated.size() && !translated.get(i).isEmpty()) {
                    text.append('\n').append(translated.get(i));
                }
            }

            ClipboardManager clipboard = (ClipboardManager) getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("lyrics", text.toString()));
            Utils.showToastShort(str("morphe_music_lyrics_copied"));
        } catch (Exception ex) {
            Logger.printException(() -> "onCopyClicked failure", ex);
        }
    }

    private void updateTranslateLabel() {
        if (translateView != null) {
            translateView.setText(str(translatedLines == null
                    ? "morphe_music_lyrics_translate_show"
                    : "morphe_music_lyrics_translate_hide"));
        }
    }

    private void clearLines() {
        for (TextView lineView : lineViews) {
            // A running fade would otherwise keep a reference to a removed view.
            lineView.animate().cancel();
            linesContainer.removeView(lineView);
        }
        lineViews.clear();
        lineWordSpans.clear();
        highlightedIndex = -1;
        lastWordLineIndex = -1;
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

    private void updateWordSync(long positionMs) {
        boolean enabled = Settings.LYRICS_WORD_SYNC.get();
        if (enabled != wordSyncWasEnabled) {
            if (!enabled) {
                int count = Math.min(lineWordSpans.size(), lineViews.size());
                for (int i = 0; i < count; i++) {
                    applyWordColors(i, Long.MIN_VALUE, true);
                }
            }
            wordSyncWasEnabled = enabled;
        }
        int active = highlightedIndex;

        // The per-line lists are rebuilt together, but guard against any transient
        // mismatch so a single malformed line cannot kill the ticker.
        int count = Math.min(lineWordSpans.size(), lineViews.size());
        if (active < 0 || active >= count) {
            if (lastWordLineIndex >= 0) {
                applyWordColors(lastWordLineIndex, Long.MIN_VALUE, false);
            }
            lastWordLineIndex = -1;
            return;
        }

        if (!enabled) {
            if (lastWordLineIndex >= 0 && lastWordLineIndex != active) {
                applyWordColors(lastWordLineIndex, Long.MIN_VALUE, false);
            }
            applyWordColors(active, 0, true);
            lastWordLineIndex = -1;
            return;
        }

        if (lineWordSpans.get(active).isEmpty()) {
            if (lastWordLineIndex >= 0) {
                applyWordColors(lastWordLineIndex, Long.MIN_VALUE, false);
            }
            lastWordLineIndex = -1;
            return;
        }

        if (active != lastWordLineIndex) {
            if (lastWordLineIndex >= 0) {
                applyWordColors(lastWordLineIndex, Long.MIN_VALUE, false);
            }
            lastWordLineIndex = active;
        }

        applyWordColors(active, positionMs, false);
    }

    private void applyWordColors(int index, long positionMs, boolean allSung) {
        if (index < 0 || index >= lineWordSpans.size() || index >= lineViews.size()) {
            return;
        }

        List<WordTiming> timings = lineWordSpans.get(index);
        if (timings.isEmpty()) {
            return;
        }

        // Rebuild the line text into a fresh SpannableString so that setText performs a
        // full re-layout and repaint; mutating an existing Spannable in place is not
        // reliably redrawn by this TextView, which left the highlight invisible.
        lineViews.get(index).setText(
                buildLineText(lyrics.lines().get(index), timings, index, positionMs, allSung));
    }

    /** Eases the highlight between lines the way the built-in panel does. */
    private static void fadeTo(TextView lineView, float alpha) {
        lineView.animate().cancel();
        lineView.animate()
                .alpha(alpha)
                .setDuration(HIGHLIGHT_FADE_DURATION_MILLISECONDS)
                .start();
    }

    private static void applyFooterStyle(TextView footer) {
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, FOOTER_TEXT_SIZE_SP);
        footer.setTextColor(secondaryTextColor());
        // The secondary color alone is brighter than the app draws this line, which
        // sits dimmer than even the inactive lyrics above it.
        footer.setAlpha(FOOTER_ALPHA);
    }

    /**
     * Styles the button as a pill, the shape the app uses for the buttons under its
     * own lyrics, with the background taken from the app palette so it follows the theme.
     *
     * @param iconName Drawable name for the button icon, or {@code null} for a text only button.
     */
    private void applyButtonStyle(TextView button, @Nullable String iconName) {
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SIZE_SP);
        button.setTextColor(lineTextColor());
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Dim.dp16, Dim.dp6, Dim.dp16, Dim.dp6);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(Dim.dp20);
        background.setColor(ResourceUtils.getColor(APP_BUTTON_BACKGROUND_COLOR, 0x1AFFFFFF));
        button.setBackground(background);

        ViewAnimations.applyPressEffect(button);

        if (iconName == null || iconName.isEmpty()) {
            return;
        }

        // The drawable is themed with an attribute the panel context does not carry,
        // so it is tinted explicitly to match the button label.
        Drawable icon = ResourceUtils.getDrawable(iconName);
        if (icon == null) {
            Logger.printDebug(() -> "Missing icon: " + iconName);
            return;
        }
        icon = icon.mutate();
        icon.setTint(lineTextColor());
        final int iconSize = Dim.dp24;
        icon.setBounds(0, 0, iconSize, iconSize);
        button.setCompoundDrawablesRelative(icon, null, null, null);
        button.setCompoundDrawablePadding(Dim.dp8);
    }

    private static int secondaryTextColor() {
        // The karaoke highlight needs a colour that visibly differs from the sung
        // (primary) colour. Prefer the app's secondary text colour, but if that
        // resource is unavailable fall back to a dimmed primary so the effect is
        // always visible instead of collapsing to the sung colour.
        int secondary = ResourceUtils.getColor(APP_SECONDARY_TEXT_COLOR, 0);
        if (secondary != 0) {
            return secondary;
        }
        int base = lineTextColor();
        return Color.argb(0x66, Color.red(base), Color.green(base), Color.blue(base));
    }

    private static int unsungWordColor() {
        int base = lineTextColor();
        return Color.argb(0x66, Color.red(base), Color.green(base), Color.blue(base));
    }

    /**
     * Color the app uses for lyrics text, falling back to the generic foreground color.
     */
    private static int lineTextColor() {
        final int colorId = ResourceUtils.getIdentifier(ResourceType.COLOR, APP_PRIMARY_TEXT_COLOR);
        if (colorId == 0) {
            return ThemeUtils.getAppForegroundColor();
        }
        return ResourceUtils.getColor(APP_PRIMARY_TEXT_COLOR, ThemeUtils.getAppForegroundColor());
    }

    private static String sourceText(String providerName, boolean translated) {
        String text = String.format(str(LYRICS_SOURCE_KEY), providerName);
        if (translated) {
            text += "\n" + str("morphe_music_lyrics_translated_by_google");
        }
        return text;
    }
}
