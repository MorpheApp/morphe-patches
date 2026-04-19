package app.morphe.extension.music.settings.preference;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.Locale;

import app.morphe.extension.music.patches.CrossfadeManager.FadeCurve;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ui.Dim;

/**
 * Custom preference that renders a live preview of the selected crossfade curve.
 * Polls the Settings values to detect changes and re-renders.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class CrossfadeCurvePreference extends Preference
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private CurveView curveView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FadeCurve lastCurve = null;
    private int lastDurationMs = -1;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (curveView == null || !curveView.isAttachedToWindow()) return;

            FadeCurve currentCurve = Settings.CROSSFADE_CURVE.get();
            final int currentDurationMs = Settings.CROSSFADE_DURATION.get().milliseconds;

            if (currentCurve != lastCurve || currentDurationMs != lastDurationMs) {
                lastCurve = currentCurve;
                lastDurationMs = currentDurationMs;
                curveView.invalidate();
            }

            handler.postDelayed(this, 500);
        }
    };

    public CrossfadeCurvePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public CrossfadeCurvePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public CrossfadeCurvePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CrossfadeCurvePreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(false);
        setPersistent(false);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    protected View onCreateView(ViewGroup parent) {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        final int padH = Dim.dp16;
        final int padV = Dim.dp8;
        layout.setPadding(padH, padV, padH, padV);

        curveView = new CurveView(getContext());
        layout.addView(curveView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp(160)));

        handler.postDelayed(pollRunnable, 500);

        return layout;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        if (curveView != null) {
            curveView.invalidate();
        }
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        try {
            SharedPreferences prefs = preferenceManager.getSharedPreferences();
            if (prefs != null) {
                prefs.registerOnSharedPreferenceChangeListener(this);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key != null && key.startsWith("morphe_music_crossfade")) {
            if (curveView != null) {
                curveView.postInvalidate();
            }
        }
    }

    @SuppressLint("ViewConstructor")
    private static final class CurveView extends View {

        private final Paint outPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outPath = new Path();
        private final Path inPath = new Path();

        CurveView(Context context) {
            super(context);
            outPaint.setStyle(Paint.Style.STROKE);
            outPaint.setStrokeWidth(dp(2.5f));
            outPaint.setColor(0xFFFF6B6B);
            outPaint.setStrokeCap(Paint.Cap.ROUND);

            inPaint.setStyle(Paint.Style.STROKE);
            inPaint.setStrokeWidth(dp(2.5f));
            inPaint.setColor(0xFF4ECDC4);
            inPaint.setStrokeCap(Paint.Cap.ROUND);

            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(dp(0.5f));
            gridPaint.setColor(0x33FFFFFF);

            textPaint.setTextSize(dp(10f));
            textPaint.setColor(0x88FFFFFF);

            setBackgroundColor(0xFF14141E);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();
            float padL = dp(36);
            float padR = dp(12);
            float padT = dp(24);
            float padB = dp(24);
            float gW = w - padL - padR;
            float gH = h - padT - padB;

            for (int i = 0; i <= 4; i++) {
                float y = padT + (gH * i / 4f);
                canvas.drawLine(padL, y, w - padR, y, gridPaint);
            }

            textPaint.setTextAlign(Paint.Align.RIGHT);
            for (int i = 0; i <= 4; i++) {
                float val = 1.0f - i / 4f;
                float y = padT + (gH * i / 4f) + dp(3f);
                canvas.drawText(String.format(Locale.US, "%.0f%%", val * 100), padL - dp(4), y, textPaint);
            }

            FadeCurve curve = Settings.CROSSFADE_CURVE.get();
            final int durationMs = Settings.CROSSFADE_DURATION.get().milliseconds;

            textPaint.setTextAlign(Paint.Align.CENTER);
            int xSteps = 4;
            for (int i = 0; i <= xSteps; i++) {
                float x = padL + (gW * i / (float) xSteps);
                int ms = Math.round(durationMs * i / (float) xSteps);
                String label;
                if (ms >= 1000 && ms % 1000 == 0) {
                    label = (ms / 1000) + "s";
                } else {
                    label = ms + "ms";
                }
                canvas.drawText(label, x, h - dp(4), textPaint);
            }

            int steps = 100;
            outPath.reset();
            inPath.reset();

            for (int i = 0; i <= steps; i++) {
                float t = i / (float) steps;
                float x = padL + t * gW;
                float outVol = curve.out(t);
                float inVol = curve.in(t);
                float outY = padT + (1.0f - outVol) * gH;
                float inY = padT + (1.0f - inVol) * gH;

                if (i == 0) {
                    outPath.moveTo(x, outY);
                    inPath.moveTo(x, inY);
                } else {
                    outPath.lineTo(x, outY);
                    inPath.lineTo(x, inY);
                }
            }

            canvas.drawPath(outPath, outPaint);
            canvas.drawPath(inPath, inPaint);

            float legendY = dp(12);
            float legendX = padL + dp(4);

            outPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(legendX, legendY, dp(4), outPaint);
            outPaint.setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Outgoing", legendX + dp(8), legendY + dp(3.5f), textPaint);

            float inLegendX = legendX + dp(80);
            inPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(inLegendX, legendY, dp(4), inPaint);
            inPaint.setStyle(Paint.Style.STROKE);
            canvas.drawText("Incoming", inLegendX + dp(8), legendY + dp(3.5f), textPaint);

            String curveName = switch (curve) {
                case EASE_OUT_CUBIC -> "Subtle hold";
                case EASE_OUT_QUAD -> "Gentle ease";
                case SMOOTHSTEP -> "Smooth S-curve";
                default -> "Equal power";
            };
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(curveName, w - padR, legendY + dp(3.5f), textPaint);
        }

        private float dp(float dp) {
            return TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, dp,
                    getContext().getResources().getDisplayMetrics());
        }
    }
}
