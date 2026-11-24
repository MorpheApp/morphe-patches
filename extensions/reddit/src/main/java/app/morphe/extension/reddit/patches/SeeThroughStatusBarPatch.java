/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import kotlin.jvm.functions.Function1;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class SeeThroughStatusBarPatch {

    private static volatile long appBarColor = 0L;
    private static volatile boolean sideLayout;
    private static volatile int cachedStatusBarPx = 0;
    private static volatile WeakReference<Activity> currentActivity = null;
    private static volatile Context windowContext = null;
    private static volatile boolean lifecycleRegistered = false;

    private static final Function1<Object, Object> SCRIM_DRAW =
            new Function1<Object, Object>() {
                @Override
                public Object invoke(Object scope) {
                    try {
                        GlassNavigationBarPatch.drawContent(scope);
                    } catch (Throwable ex) {
                        Logger.printException(() -> "drawContent failure", ex);
                    }
                    try {
                        if (on()) {
                            drawScrim(scope);
                        }
                    } catch (Throwable ex) {
                        Logger.printException(() -> "drawScrim failure", ex);
                    }
                    return null;
                }
            };

    private static final Function1<Object, Object> BODY_DRAW =
            new Function1<Object, Object>() {
                @Override
                public Object invoke(Object scope) {
                    try {
                        drawBody(scope);
                    } catch (Throwable ex) {
                        Logger.printException(() -> "drawBody failure", ex);
                    } finally {
                        try {
                            GlassNavigationBarPatch.drawContent(scope);
                        } catch (Throwable ex) {
                            Logger.printException(() -> "drawContent failure", ex);
                        }
                    }
                    return null;
                }
            };

    private SeeThroughStatusBarPatch() {}

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;
    }

    private static boolean on() {
        return isPatchIncluded() && Settings.SEE_THROUGH_STATUS_BAR.get() && !sideLayout;
    }

    public static void sideLayout(boolean value) {
        try {
            sideLayout = value;
        } catch (Throwable ex) {
            Logger.printException(() -> "sideLayout failure", ex);
        }
    }

    public static long appBarColor(long c) {
        try {
            appBarColor = c;
            return on() ? (c & 0x00FFFFFFFFFFFFFFL) : c;
        } catch (Throwable ex) {
            Logger.printException(() -> "appBarColor failure", ex);
            return c;
        }
    }

    public static Object appBarBody(Object modifier) {
        try {
            return (on() && appBarColor != 0L) ? drawWithContent(modifier, BODY_DRAW) : modifier;
        } catch (Throwable ex) {
            Logger.printException(() -> "appBarBody failure", ex);
            return modifier;
        }
    }

    public static Object feedBoxModifier(Object padded, Object unpadded) {
        try {
            return on() ? drawWithContent(unpadded, SCRIM_DRAW) : padded;
        } catch (Throwable ex) {
            Logger.printException(() -> "feedBoxModifier failure", ex);
            return padded;
        }
    }

    public static float topBarHeight(float dp) {
        try {
            if (!on()) {
                return dp;
            }
            final float density = GlassNavigationBarPatch.getDensity();
            return dp + (density > 0f ? (statusBarPx() / density) : 0f);
        } catch (Throwable ex) {
            Logger.printException(() -> "topBarHeight failure", ex);
            return dp;
        }
    }

    private static void ensureLifecycleCallbacks() {
        if (lifecycleRegistered) {
            return;
        }
        synchronized (SeeThroughStatusBarPatch.class) {
            if (lifecycleRegistered) {
                return;
            }
            try {
                Context context = Utils.getContext();
                Context ctx = context == null ? null : context.getApplicationContext();
                if (ctx instanceof Application) {
                    ((Application) ctx)
                            .registerActivityLifecycleCallbacks(
                                    new Application.ActivityLifecycleCallbacks() {
                                        @Override
                                        public void onActivityCreated(
                                                Activity activity, Bundle savedInstanceState) {
                                            currentActivity = new WeakReference<>(activity);
                                        }

                                        @Override
                                        public void onActivityStarted(Activity activity) {}

                                        @Override
                                        public void onActivityResumed(Activity activity) {
                                            currentActivity = new WeakReference<>(activity);
                                        }

                                        @Override
                                        public void onActivityPaused(Activity activity) {}

                                        @Override
                                        public void onActivityStopped(Activity activity) {}

                                        @Override
                                        public void onActivitySaveInstanceState(
                                                Activity activity, Bundle outState) {}

                                        @Override
                                        public void onActivityDestroyed(Activity activity) {
                                            final WeakReference<Activity> ref = currentActivity;
                                            if (ref != null && ref.get() == activity) {
                                                currentActivity = null;
                                            }
                                        }
                                    });
                    lifecycleRegistered = true;
                }
            } catch (Throwable ex) {
                Logger.printException(() -> "ensureLifecycleCallbacks failure", ex);
            }
        }
    }

    private static int getDecorViewInset() {
        try {
            final WeakReference<Activity> ref = currentActivity;
            final Activity activity = ref != null ? ref.get() : null;
            if (activity != null && activity.getWindow() != null) {
                final View decorView = activity.getWindow().getDecorView();
                if (decorView != null) {
                    final WindowInsets insets = decorView.getRootWindowInsets();
                    if (insets != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            return insets.getInsets(WindowInsets.Type.systemBars()).top;
                        } else {
                            return insets.getSystemWindowInsetTop();
                        }
                    }
                }
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "getDecorViewInset failure", ex);
        }
        return 0;
    }

    private static int getWindowContextInset() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return 0;
        }
        try {
            if (windowContext == null) {
                synchronized (SeeThroughStatusBarPatch.class) {
                    if (windowContext == null) {
                        Context context = Utils.getContext();
                        Context ctx = context == null ? null : context.getApplicationContext();
                        final Context appContext = ctx;
                        if (appContext != null) {
                            final DisplayManager dm = appContext.getSystemService(DisplayManager.class);
                            if (dm != null) {
                                final Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
                                if (display != null) {
                                    windowContext =
                                            appContext
                                                    .createDisplayContext(display)
                                                    .createWindowContext(
                                                            WindowManager.LayoutParams
                                                                    .TYPE_APPLICATION,
                                                            null);
                                }
                            }
                        }
                    }
                }
            }
            if (windowContext != null) {
                final WindowManager wm = windowContext.getSystemService(WindowManager.class);
                if (wm != null) {
                    final WindowInsets insets = wm.getCurrentWindowMetrics().getWindowInsets();
                    if (insets != null) {
                        return insets.getInsets(WindowInsets.Type.systemBars()).top;
                    }
                }
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "getWindowContextInset failure", ex);
        }
        return 0;
    }

    private static int statusBarPx() {
        try {
            ensureLifecycleCallbacks();

            int inset = getDecorViewInset();
            if (inset > 0) {
                cachedStatusBarPx = inset;
                return inset;
            }

            inset = getWindowContextInset();
            if (inset > 0) {
                cachedStatusBarPx = inset;
                return inset;
            }

            if (cachedStatusBarPx > 0) {
                return cachedStatusBarPx;
            }

            final int resourceId =
                    Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                inset = Resources.getSystem().getDimensionPixelSize(resourceId);
                if (inset > 0) {
                    cachedStatusBarPx = inset;
                    return inset;
                }
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "statusBarPx failure", ex);
        }
        return cachedStatusBarPx;
    }

    private static long packFloats(float x, float y) {
        return ((long) Float.floatToRawIntBits(x) << 32)
                | (Float.floatToRawIntBits(y) & 0xFFFFFFFFL);
    }

    private static void drawScrim(Object scope) {
        final long color = appBarColor;
        if (color == 0L) {
            return;
        }
        final int h = statusBarPx();
        if (h <= 0) {
            return;
        }
        final long scopeSize = GlassNavigationBarPatch.drawScopeSize(scope);
        final float width = Float.intBitsToFloat((int) (scopeSize >>> 32));
        if (width <= 0f) {
            return;
        }

        final int rgb = ((int) (color >>> 32)) & 0x00FFFFFF;
        final int n = h;
        int runStart = 0;
        int runAlpha = 0;
        for (int i = 0; i <= n; i++) {
            int alpha = 0;
            if (i < n) {
                final double t = (i + 0.5) / (double) n;
                final double factor = 0.92 * Math.pow(1.0 - t, 1.6);
                alpha = Math.min(255, Math.max(0, (int) Math.round(255.0 * factor)));
            }
            if (alpha != runAlpha) {
                if (runAlpha > 0) {
                    final int bandArgb = (runAlpha << 24) | rgb;
                    final long bandColor = ((long) bandArgb & 0xFFFFFFFFL) << 32;

                    final long topLeft = packFloats(0f, (float) runStart);
                    final long size = packFloats(width, (float) (i - runStart));
                    final long cornerRadius = packFloats(0f, 0f);

                    GlassNavigationBarPatch.drawRoundRect(
                            scope, bandColor, topLeft, size, cornerRadius, null, 1.0f, 0xF0);
                }
                runStart = i;
                runAlpha = alpha;
            }
        }
    }

    private static void drawBody(Object scope) {
        final long color = appBarColor;
        if (color == 0L) {
            return;
        }
        final int h = statusBarPx();
        final long scopeSize = GlassNavigationBarPatch.drawScopeSize(scope);
        final float width = Float.intBitsToFloat((int) (scopeSize >>> 32));
        final float height = Float.intBitsToFloat((int) scopeSize);
        final long topLeft = packFloats(0f, -h);
        final long size = packFloats(width, height + h);
        final long cornerRadius = packFloats(0f, 0f);
        GlassNavigationBarPatch.drawRoundRect(
                scope, color, topLeft, size, cornerRadius, null, 1.0f, 0xF0);
    }

    @SuppressWarnings("rawtypes")
    private static Object drawWithContent(Object modifier, Function1 block) {
        return modifier;
    }
}
