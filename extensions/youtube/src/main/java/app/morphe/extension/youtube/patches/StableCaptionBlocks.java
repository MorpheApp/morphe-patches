/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import android.os.Build;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

import org.w3c.dom.Document;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;

/** Native source timing and phone paint, with complete immutable text between block boundaries. */
@SuppressWarnings("unused")
public final class StableCaptionBlocks {
    private static final ThreadLocal<State> parsing = new ThreadLocal<>();
    private static final Object FIXED_PARAGRAPH = new Object();

    private StableCaptionBlocks() {}

    public interface Timeline {
        List<?> patch_getOriginalCaptions(long time);
    }

    public interface Cue {
        CharSequence patch_getOriginalCaptionVisible();

        Frame patch_getBlockFrame();

        Object patch_copyBlockFrame(Frame frame);
    }

    public interface Window {
        Object patch_getCaptionCue();

        Object patch_getCaptionMeasureRow();

        List<CharSequence> patch_getFullCaptionLines();

        List<CharSequence> patch_getVisibleCaptionLines();
    }

    public interface Row {
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
        // Published only after a nonempty plan is ready.
        private volatile Cache lastReady;
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

    public static void setAutomaticTrack(boolean automatic) {
        parsing.remove();
        if (automatic && Build.VERSION.SDK_INT >= 28 && Settings.STABLE_CAPTION_BLOCKS.get()) {
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

    public static State takeState() {
        State state = parsing.get();
        parsing.remove();
        return state != null && state.policy != null ? state : null;
    }

    /** Native player stages recreate cues, but retain their styled full-text sequence. */
    public static CharSequence carryFrame(CharSequence text, Frame frame) {
        if (text == null || text.length() == 0 || frame == null) return text;
        SpannableStringBuilder copy = new SpannableStringBuilder(text);
        for (Frame previous : copy.getSpans(0, copy.length(), Frame.class))
            copy.removeSpan(previous);
        copy.setSpan(frame, 0, copy.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return copy;
    }

    public static Frame frameOf(CharSequence text) {
        if (!(text instanceof Spanned spanned) || text.length() == 0) return null;
        Frame[] frames = spanned.getSpans(0, text.length(), Frame.class);
        return frames.length == 1 ? frames[0] : null;
    }

    public static List<?> present(Timeline timeline, List<?> original, long time, State state) {
        if (state == null || original == null || original.size() > 1) return original;
        long template = state.policy.templateTime(time);
        if (template < 0) return Collections.emptyList();
        try {
            List<?> source = original;
            if (source.isEmpty()
                    || (source.size() == 1
                            && source.get(0) instanceof Cue c
                            && (c.patch_getOriginalCaptionVisible() == null
                                    || c.patch_getOriginalCaptionVisible().length() == 0))) {
                if (timeline == null) return original;
                source = timeline.patch_getOriginalCaptions(template);
            }
            if (source == null || source.size() != 1 || !(source.get(0) instanceof Cue cue))
                return original;
            Object copy = cue.patch_copyBlockFrame(new Frame(state, time, template));
            return copy == null ? original : Collections.singletonList(copy);
        } catch (RuntimeException | LinkageError ignored) {
            return original;
        }
    }

    /**
     * Player subclasses may recreate cue placement; only marked ASR blocks use default center
     * placement.
     */
    public static List<?> centerBlocks(List<?> original) {
        if (original == null || original.size() != 1 || !(original.get(0) instanceof Cue cue))
            return original;
        Frame frame = cue.patch_getBlockFrame();
        if (frame == null) return original;
        try {
            Object copy = cue.patch_copyBlockFrame(frame);
            return copy == null || copy == cue ? original : Collections.singletonList(copy);
        } catch (RuntimeException | LinkageError ignored) {
            return original;
        }
    }

    /**
     * Called before native window measurement, after its font/typeface/style have been configured.
     */
    public static void prepare(View view, int widthSpec) {
        if (Build.VERSION.SDK_INT < 28) return;
        if (!(view instanceof Window window) || !(window.patch_getCaptionCue() instanceof Cue cue))
            return;
        Frame frame = cue.patch_getBlockFrame();
        if (frame == null) return;
        try {
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
                Cache ready = frame.state.lastReady;
                if (ready != null && ready.matches(probe)) {
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
                                } catch (RuntimeException failure) {
                                    next.blocks = List.of();
                                }
                                if (!next.cancelled) {
                                    if (!next.blocks.isEmpty()) frame.state.lastReady = next;
                                    View target = redraw.get();
                                    if (target != null) target.post(target::requestLayout);
                                }
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
        } catch (RuntimeException | LinkageError ignored) {
            /* Unknown native geometry retains native text. */
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
        if (text instanceof Spanned spans && spans.getSpanStart(FIXED_PARAGRAPH) >= 0) {
            return blockLayout(text, paint, width)
                    .setLineSpacing(spacingAdd, spacingMult)
                    .setIncludePad(includePad)
                    .build();
        }
        return new StaticLayout(text, paint, width, alignment, spacingMult, spacingAdd, includePad);
    }
}
