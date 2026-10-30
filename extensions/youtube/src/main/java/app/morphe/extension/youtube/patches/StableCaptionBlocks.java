/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.patches;

import android.os.Build;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.Nullable;

import org.w3c.dom.Document;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/** Native source timing and phone paint, with complete immutable text between block boundaries. */
@SuppressWarnings("unused")
public final class StableCaptionBlocks {
    private static final ThreadLocal<State> parsing = new ThreadLocal<>();
    private static final Object FIXED_PARAGRAPH = new Object();

    /**
     * Text paint measurement comparison requires Android 10.
     * The setting requires restarting the app.
     */
    private static final boolean ENABLED = isPatchIncluded()
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && Settings.STABLE_CAPTION_BLOCKS.get();

    private StableCaptionBlocks() {}

    private static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    public interface Timeline {
        // Methods are added during patching.
        List<?> patch_getOriginalCaptions(long time);

        @Nullable
        State patch_getBlockState();
    }

    public interface Cue {
        // Methods are added during patching.
        CharSequence patch_getOriginalCaptionVisible();

        Frame patch_getBlockFrame();

        Object patch_copyBlockFrame(Frame frame);
    }

    public interface Window {
        // Methods are added during patching.
        Object patch_getCaptionCue();

        Object patch_getCaptionMeasureRow();

        List<CharSequence> patch_getFullCaptionLines();

        List<CharSequence> patch_getVisibleCaptionLines();
    }

    public interface Row {
        // Methods are added during patching.
        void patch_setCaptionText(CharSequence text);

        StaticLayout patch_getCaptionLayout();
    }

    public static final class Frame {
        final State state;
        final long time, key;

        Frame(State state, long time, long key) {
            this.state = state;
            this.time = time;
            this.key = key;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Frame f && f.state == state && f.key == key;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(state) + Long.hashCode(key);
        }
    }

    public static final class State {
        StableCaptionBlockPolicy policy;
        private final Map<View, Cache> caches = new WeakHashMap<>();
        // Keep inner, outer portrait and outer landscape plans across native view recreation.
        private final List<Cache> ready = new ArrayList<>();
    }

    private static final class Cache {
        final int width;
        final TextPaint paint;
        volatile List<StableCaptionBlockPolicy.Block> blocks;
        volatile boolean cancelled;
        StableCaptionBlockPolicy.Block last;
        CharSequence text;
        List<CharSequence> nativeFull, nativeVisible;

        Cache(int width, TextPaint paint) {
            this.width = width;
            this.paint = new TextPaint();
            this.paint.set(paint);
        }

        boolean matches(StaticLayout probe) {
            return width == probe.getWidth() && paint.equalsForTextMeasurement(probe.getPaint());
        }

        StaticLayout layout(String text) {
            if (cancelled) throw new CancellationException();
            return blockLayout(text, paint, width).setIncludePad(true).build();
        }
    }

    /**
     * Called before a caption track is parsed.
     */
    static void setAutomaticTrack(boolean automatic) {
        if (!ENABLED) return;
        parsing.remove();
        if (automatic) {
            parsing.set(new State());
        }
    }

    static boolean isCapturing() {
        return parsing.get() != null;
    }

    static void capture(Document document) {
        State state = parsing.get();
        if (state != null) state.policy = StableCaptionBlockPolicy.parse(document);
    }

    /**
     * Injection point.
     * Called at the end of the caption timeline constructor.
     */
    @Nullable
    public static State takeState() {
        if (!ENABLED) return null;
        State state = parsing.get();
        parsing.remove();
        return state != null && state.policy != null ? state : null;
    }

    /** Native player stages recreate cues, but retain their styled full-text sequence. */
    public static CharSequence carryFrame(@Nullable CharSequence text, @Nullable Frame frame) {
        //noinspection SizeReplaceableByIsEmpty
        if (text == null || text.length() == 0 || frame == null) return text;
        SpannableStringBuilder copy = new SpannableStringBuilder(text);
        for (Frame previous : copy.getSpans(0, copy.length(), Frame.class))
            copy.removeSpan(previous);
        copy.setSpan(frame, 0, copy.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return copy;
    }

    @Nullable
    public static Frame frameOf(@Nullable CharSequence text) {
        //noinspection SizeReplaceableByIsEmpty
        if (!(text instanceof Spanned spanned) || text.length() == 0) return null;
        Frame[] frames = spanned.getSpans(0, text.length(), Frame.class);
        return frames.length == 1 ? frames[0] : null;
    }

    /**
     * Injection point.
     * Called instead of the caption sampler, if the timeline has a block state.
     */
    public static List<?> present(Timeline timeline, long time) {
        List<?> original = timeline.patch_getOriginalCaptions(time);
        try {
            State state = timeline.patch_getBlockState();
            if (state == null || original == null || original.size() > 1) return original;
            long template = state.policy.templateTime(time);
            if (template < 0) return Collections.emptyList();

            List<?> source = original;
            //noinspection SizeReplaceableByIsEmpty
            if (source.isEmpty() || (source.size() == 1 && source.get(0) instanceof Cue c
                            && (c.patch_getOriginalCaptionVisible() == null
                                    || c.patch_getOriginalCaptionVisible().length() == 0))) {
                source = timeline.patch_getOriginalCaptions(template);
            }
            if (source == null || source.size() != 1 || !(source.get(0) instanceof Cue cue))
                return original;
            Object copy = cue.patch_copyBlockFrame(new Frame(state, time, template));
            return copy == null ? original : Collections.singletonList(copy);
        } catch (Exception ex) {
            Logger.printException(() -> "present failure", ex);
            return original;
        }
    }

    /**
     * Player subclasses may recreate cue placement; only marked ASR blocks use default center
     * placement.
     */
    public static List<?> centerBlocks(@Nullable List<?> original) {
        if (!ENABLED || original == null || original.size() != 1
                || !(original.get(0) instanceof Cue cue)) {
            return original;
        }
        try {
            Frame frame = cue.patch_getBlockFrame();
            if (frame == null) return original;
            Object copy = cue.patch_copyBlockFrame(frame);
            return copy == null || copy == cue ? original : Collections.singletonList(copy);
        } catch (Exception ex) {
            Logger.printException(() -> "centerBlocks failure", ex);
            return original;
        }
    }

    /**
     * Called before native window measurement, after its font/typeface/style have been configured.
     */
    public static void prepare(View view, int widthSpec) {
        if (!ENABLED) return;
        try {
            if (!(view instanceof Window window)
                    || !(window.patch_getCaptionCue() instanceof Cue cue)) return;
            Frame frame = cue.patch_getBlockFrame();
            if (frame == null) return;
            Cache cache = frame.state.caches.get(view);
            List<CharSequence> full = window.patch_getFullCaptionLines(),
                    visible = window.patch_getVisibleCaptionLines();
            List<CharSequence> nativeFull, nativeVisible;
            if (marked(full) || marked(visible)) {
                if (cache == null || cache.nativeFull == null) return;
                nativeFull = cache.nativeFull;
                nativeVisible = cache.nativeVisible;
                full.clear();
                full.addAll(nativeFull);
                visible.clear();
                visible.addAll(nativeVisible);
            } else {
                nativeFull = new ArrayList<>(full);
                nativeVisible = new ArrayList<>(visible);
            }
            Object nativeRow = window.patch_getCaptionMeasureRow();
            if (!(nativeRow instanceof Row row) || !(nativeRow instanceof View measure)) return;
            int width =
                    View.MeasureSpec.getSize(widthSpec)
                            - view.getPaddingLeft()
                            - view.getPaddingRight();
            if (width <= 0) return;
            // EXACTLY discovers native legacy/new padding semantics without guessing row fields.
            row.patch_setCaptionText("M");
            measure.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            StaticLayout probe = row.patch_getCaptionLayout();
            if (probe == null || probe.getWidth() <= 0) return;
            if (cache == null || !cache.matches(probe)) {
                if (cache != null) cache.cancelled = true;
                cache = new Cache(probe.getWidth(), probe.getPaint());
                frame.state.caches.put(view, cache);
                Cache ready = null;
                for (Cache prepared : frame.state.ready) {
                    if (prepared.matches(probe)) {
                        ready = prepared;
                        break;
                    }
                }
                if (ready != null) {
                    frame.state.ready.remove(ready);
                    frame.state.ready.add(0, ready);
                    cache.blocks = ready.blocks;
                } else {
                    Cache next = cache;
                    WeakReference<View> redraw = new WeakReference<>(view);
                    Utils.runOnBackgroundThread(
                            () -> {
                                try {
                                    next.blocks =
                                            frame.state.policy.plan(
                                                    text -> next.layout(text).getLineCount() <= 2);
                                } catch (CancellationException ex) {
                                    // A newer text layout replaced this cache.
                                    next.blocks = List.of();
                                } catch (Exception ex) {
                                    Logger.printException(() -> "plan failure", ex);
                                    next.blocks = List.of();
                                }
                                Utils.runOnMainThread(
                                        () -> {
                                            if (next.cancelled) return;
                                            if (!next.blocks.isEmpty()) {
                                                frame.state.ready.removeIf(
                                                        prepared ->
                                                                prepared.width == next.width
                                                                        && prepared.paint
                                                                                .equalsForTextMeasurement(
                                                                                        next.paint));
                                                frame.state.ready.add(0, next);
                                                if (frame.state.ready.size() > 3)
                                                    frame.state.ready.remove(3);
                                            }
                                            View target = redraw.get();
                                            if (target != null) target.requestLayout();
                                        });
                            });
                }
            }
            cache.nativeFull = nativeFull;
            cache.nativeVisible = nativeVisible;
            if (cache.blocks == null) return;
            StableCaptionBlockPolicy.Block block =
                    StableCaptionBlockPolicy.at(cache.blocks, frame.time);
            if (block == null || !block.fits) return;
            if (cache.last != block) {
                SpannableStringBuilder text = new SpannableStringBuilder(block.text);
                text.setSpan(FIXED_PARAGRAPH, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                cache.text = text;
                cache.last = block;
            }
            full.clear();
            full.add(cache.text);
            visible.clear();
            visible.add(cache.text);
        } catch (Exception ex) {
            Logger.printException(() -> "prepare failure", ex);
        }
    }

    private static StaticLayout.Builder blockLayout(CharSequence text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    }

    private static boolean marked(List<CharSequence> lines) {
        for (CharSequence text : lines)
            if (text instanceof Spanned spans && spans.getSpanStart(FIXED_PARAGRAPH) >= 0)
                return true;
        return false;
    }

    /** Preserve the native constructor for every unmarked caption, including prepared tracks. */
    @SuppressWarnings("deprecation")
    public static StaticLayout createLayout(
            CharSequence text,
            TextPaint paint,
            int width,
            Layout.Alignment alignment,
            float spacingMult,
            float spacingAdd,
            boolean includePad) {
        if (ENABLED && text instanceof Spanned spans && spans.getSpanStart(FIXED_PARAGRAPH) >= 0) {
            return blockLayout(text, paint, width)
                    .setLineSpacing(spacingAdd, spacingMult)
                    .setIncludePad(includePad)
                    .build();
        }
        return new StaticLayout(text, paint, width, alignment, spacingMult, spacingAdd, includePad);
    }
}
