package app.morphe.extension.youtube.series;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.provider.Settings;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One moving selection surface, with native text, focus, and touch semantics. */
final class HistorySwitcher extends LinearLayout {
    private final TextView watch, series;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final ArgbEvaluator colors = new ArgbEvaluator();
    private ValueAnimator animator;
    private float position;
    private Shader selectionShader;
    private boolean shaderDark;
    private boolean selected;

    HistorySwitcher(Context context, TextView watch, TextView series) {
        super(context);
        this.watch = watch;
        this.series = series;
        setOrientation(HORIZONTAL);
        setPadding(dp(4), 0, dp(4), 0);
        addView(watch, new LayoutParams(0, -1, 1.3f));
        addView(series, new LayoutParams(0, -1, 1));
        setMinimumHeight(dp(48));
        updateColors();
    }

    private int dp(float value) {
        return HistoryUi.dp(getContext(), value);
    }

    void select(boolean value) {
        selected = value;
        float target = value ? 1 : 0;
        if (animator != null) animator.cancel();
        boolean motion =
                Settings.Global.getFloat(
                                getContext().getContentResolver(),
                                Settings.Global.ANIMATOR_DURATION_SCALE,
                                1f)
                        != 0;
        if (!isLaidOut() || !isShown() || !motion) {
            position = target;
            updateColors();
            invalidate();
            return;
        }
        if (position == target) return;
        animator = ValueAnimator.ofFloat(position, target);
        animator.setDuration(380);
        animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        animator.addUpdateListener(
                animation -> {
                    position = (float) animation.getAnimatedValue();
                    updateColors();
                    invalidate();
                });
        animator.start();
    }

    private void updateColors() {
        boolean dark = HistoryUi.dark(getContext());
        int ink = dark ? 0xff0f0f0f : 0xfff1f1f1;
        int muted = HistoryUi.secondary(getContext());
        watch.setTextColor((int) colors.evaluate(position, ink, muted));
        series.setTextColor((int) colors.evaluate(position, muted, ink));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        boolean dark = HistoryUi.dark(getContext());
        float radius = dp(22);
        bounds.set(dp(0.5f), dp(0.5f), getWidth() - dp(0.5f), getHeight() - dp(0.5f));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(dark ? 0xff191919 : 0xffeeeeee);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(dark ? 0xff303030 : 0xffdddddd);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        // Actual child bounds keep the motion correct in RTL and after font/layout changes.
        float left = watch.getLeft() + (series.getLeft() - watch.getLeft()) * position;
        float right = watch.getRight() + (series.getRight() - watch.getRight()) * position;
        // The leading edge pulls the surface open; both ends settle flush at rest.
        float stretch = (float) Math.sin(Math.PI * position) * dp(12);
        bounds.set(
                Math.max(dp(4), left - stretch),
                dp(4),
                Math.min(getWidth() - dp(4), right + stretch),
                getHeight() - dp(4));
        if (selectionShader == null || shaderDark != dark) {
            shaderDark = dark;
            selectionShader =
                    new LinearGradient(
                            0,
                            dp(4),
                            0,
                            getHeight() - dp(4),
                            dark ? 0xfffdfdfd : 0xff353535,
                            dark ? 0xffe3e3e3 : 0xff171717,
                            Shader.TileMode.CLAMP);
        }
        paint.setShader(selectionShader);
        canvas.drawRoundRect(bounds, dp(18), dp(18), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(dark ? 0xffffffff : 0xff4a4a4a);
        bounds.inset(dp(0.5f), dp(0.5f));
        canvas.drawRoundRect(bounds, dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.FILL);

        super.dispatchDraw(canvas);

        // Keyboard/D-pad focus remains visible even when a ripple is not running.
        View focused = watch.hasFocus() ? watch : series.hasFocus() ? series : null;
        if (focused != null) {
            bounds.set(
                    focused.getLeft() + dp(1),
                    dp(1),
                    focused.getRight() - dp(1),
                    getHeight() - dp(1));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(HistoryUi.foreground(getContext()));
            canvas.drawRoundRect(bounds, dp(22), dp(22), paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        selectionShader = null;
    }

    /** Icons are drawn alongside the text block, so short labels remain optically centered. */
    static final class Label extends TextView {
        private final boolean collection;
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Path path = new Path();

        Label(Context context, boolean collection) {
            super(context);
            this.collection = collection;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            android.text.Layout text = getLayout();
            if (text == null) return;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float edge = rtl ? 0 : getWidth();
            for (int line = 0; line < text.getLineCount(); line++) {
                edge =
                        rtl
                                ? Math.max(edge, text.getLineRight(line))
                                : Math.min(edge, text.getLineLeft(line));
            }
            float unit = getResources().getDisplayMetrics().density;
            float x = getTotalPaddingLeft() + edge + (rtl ? 8 : -28) * unit;
            int saved = canvas.save();
            canvas.translate(x, (getHeight() - 20 * unit) / 2f);
            canvas.scale(unit, unit);
            glyph.setColor(getCurrentTextColor());
            glyph.setStrokeWidth(1.7f);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            glyph.setStyle(Paint.Style.STROKE);
            if (collection) {
                // Offset episode sheets make this a collection, not another navigation play icon.
                path.reset();
                path.moveTo(5, 2);
                path.lineTo(16, 2);
                path.quadTo(19, 2, 19, 5);
                path.lineTo(19, 13);
                canvas.drawPath(path, glyph);
                box.set(1, 6, 16, 19);
                glyph.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(box, 3, 3, glyph);
                glyph.setColor(
                        isSelected()
                                ? 0xffff0033
                                : HistoryUi.dark(getContext()) ? 0xff191919 : 0xffeeeeee);
                path.reset();
                path.moveTo(7, 9.5f);
                path.lineTo(11.5f, 12.5f);
                path.lineTo(7, 15.5f);
                path.close();
                canvas.drawPath(path, glyph);
            } else {
                box.set(3, 2, 19, 18);
                canvas.drawArc(box, -135, 310, false, glyph);
                path.reset();
                path.moveTo(2, 2);
                path.lineTo(2, 7);
                path.lineTo(7, 7);
                canvas.drawPath(path, glyph);
                path.reset();
                path.moveTo(11, 6);
                path.lineTo(11, 10);
                path.lineTo(14, 12);
                canvas.drawPath(path, glyph);
            }
            canvas.restoreToCount(saved);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        position = selected ? 1 : 0;
        updateColors();
        super.onDetachedFromWindow();
    }
}
