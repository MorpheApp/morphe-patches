/* Copyright 2026 Morphe. See NOTICE for the GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.CaptioningManager;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Player-owned overlay; two independent paragraphs share only their bottom anchor. */
public final class DualCaptionOverlay extends LinearLayout {
    private static final Map<View, WeakReference<DualCaptionOverlay>> layers = new WeakHashMap<>();
    private final Part primary, secondary;
    private final Map<View, Float> originalAlphas = new WeakHashMap<>();
    private boolean active;
    private int requestedWidth;

    private DualCaptionOverlay(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setClickable(false);
        primary = new Part(context, true);
        secondary = new Part(context, false);
        addView(primary, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(secondary, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private static DualCaptionOverlay get(View view) {
        WeakReference<DualCaptionOverlay> reference = layers.get(view);
        return reference == null ? null : reference.get();
    }

    static void begin(View view) {
        DualCaptionOverlay layer = get(view);
        if (layer == null) return;
        layer.active = false;
        layer.setVisibility(GONE);
        for (Map.Entry<View, Float> entry : layer.originalAlphas.entrySet())
            if (entry.getKey().getAlpha() == 0f) entry.getKey().setAlpha(entry.getValue());
        layer.originalAlphas.clear();
    }

    static void show(View view, String first, String second, TextPaint paint, int width) {
        if (!(view instanceof ViewGroup group)) return;
        DualCaptionOverlay layer = get(view);
        if (layer == null) {
            layer = new DualCaptionOverlay(view.getContext());
            layers.put(view, new WeakReference<>(layer));
            group.getOverlay().add(layer);
        }
        CaptioningManager manager =
                (CaptioningManager) view.getContext().getSystemService(Context.CAPTIONING_SERVICE);
        if (manager == null) {
            begin(view);
            return;
        }
        CaptioningManager.CaptionStyle style = manager.getUserStyle();
        layer.setBackgroundColor(style.hasWindowColor() ? style.windowColor : Color.TRANSPARENT);
        layer.requestedWidth = width;
        layer.primary.configure(first, paint, style);
        layer.secondary.configure(second, paint, style);
        ((LayoutParams) layer.secondary.getLayoutParams()).topMargin =
                Math.round(
                        Math.max(
                                view.getResources().getDisplayMetrics().density * 4,
                                paint.getTextSize() * .28f));
        layer.forceLayout();
        layer.active = true;
        layer.setVisibility(VISIBLE);
    }

    /** Invoked after native measurement; reserve height without changing native child types. */
    public static void afterMeasure(View view) {
        DualCaptionOverlay layer = get(view);
        if (layer == null
                || !layer.active
                || !(view instanceof ViewGroup group)
                || !(view instanceof StableCaptionBlocks.Window window)) return;
        int width = layer.requestedWidth;
        if (width <= 0) {
            begin(view);
            return;
        }
        layer.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        window.patch_setCaptionMeasuredSize(
                Math.max(
                        view.getMeasuredWidth(),
                        width + view.getPaddingLeft() + view.getPaddingRight()),
                Math.max(
                        view.getMeasuredHeight(),
                        layer.getMeasuredHeight()
                                + view.getPaddingTop()
                                + view.getPaddingBottom()));
        place(layer, view, view.getMeasuredWidth(), view.getMeasuredHeight());
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            layer.originalAlphas.put(child, child.getAlpha());
            child.setAlpha(0f);
        }
    }

    public static void afterLayout(View view) {
        DualCaptionOverlay layer = get(view);
        if (layer == null || !layer.active) return;
        place(layer, view, view.getWidth(), view.getHeight());
    }

    private static void place(DualCaptionOverlay layer, View view, int width, int height) {
        int x = (width - layer.getMeasuredWidth()) / 2;
        int y = height - view.getPaddingBottom() - layer.getMeasuredHeight();
        layer.layout(
                x,
                Math.max(0, y),
                x + layer.getMeasuredWidth(),
                Math.max(0, y) + layer.getMeasuredHeight());
    }

    /** Drawing each language independently preserves per-paragraph wrapping and direction. */
    private static final class Part extends View {
        private final TextPaint paint = new TextPaint();
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String text = "";
        private CaptioningManager.CaptionStyle style;
        private StaticLayout layout;
        private final float padding;
        private final boolean bottom;

        Part(Context context, boolean bottom) {
            super(context);
            this.bottom = bottom;
            padding = context.getResources().getDisplayMetrics().density * 2;
        }

        void configure(String text, TextPaint source, CaptioningManager.CaptionStyle style) {
            this.text = text;
            this.style = style;
            paint.set(source);
            paint.setColor(style.foregroundColor);
            background.setColor(style.backgroundColor);
            setContentDescription(text);
            forceLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = Math.max(1, MeasureSpec.getSize(widthSpec) - Math.round(padding * 2));
            layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                            .setIncludePad(true)
                            .build();
            int twoRows =
                    StaticLayout.Builder.obtain("\n", 0, 1, paint, width)
                            .setIncludePad(true)
                            .build()
                            .getHeight();
            setMeasuredDimension(
                    MeasureSpec.getSize(widthSpec),
                    Math.max(layout.getHeight(), twoRows) + Math.round(padding * 2));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (layout == null || text.isEmpty()) return;
            canvas.save();
            canvas.translate(
                    padding, bottom ? getHeight() - padding - layout.getHeight() : padding);
            for (int line = 0; line < layout.getLineCount(); line++)
                canvas.drawRoundRect(
                        layout.getLineLeft(line) - padding,
                        layout.getLineTop(line) - padding,
                        layout.getLineRight(line) + padding,
                        layout.getLineBottom(line) + padding,
                        padding,
                        padding,
                        background);
            int color = paint.getColor();
            paint.clearShadowLayer();
            if (style.edgeType == CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1, paint.getTextSize() / 14));
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(style.edgeColor);
                layout.draw(canvas);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color);
            } else if (style.edgeType != CaptioningManager.CaptionStyle.EDGE_TYPE_NONE) {
                float offset =
                        style.edgeType == CaptioningManager.CaptionStyle.EDGE_TYPE_DEPRESSED
                                ? -padding
                                : padding;
                paint.setShadowLayer(padding, offset, offset, style.edgeColor);
            }
            layout.draw(canvas);
            canvas.restore();
        }
    }
}
