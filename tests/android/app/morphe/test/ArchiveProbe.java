package app.morphe.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import app.morphe.extension.reddit.patches.archive.ArchiveFixture;
import app.morphe.extension.reddit.patches.archive.ProfileArchivePatch;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

/** Host Compose, keyed native sections and MotionEvents. Never shipped in release. */
public final class ArchiveProbe extends Instrumentation {
    public static String tab = "VISITOR_POST", username = "archive_probe";
    public static String theme = "light";
    public static boolean visible = true;
    public static boolean eligible = true;
    public static Object lazyState, revision;
    public static boolean nativeExclusionsVerified;
    public static int wrapperDepth = 1;
    private static List<?> lastVisible;
    private Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private int assertions;
    private final Runnable tracker = new Runnable() {
        public void run() {
            if (!running) return;
            if (lazyState != null) emitVisible(lazyState);
            handler.postDelayed(this, 80);
        }
    };
    @Override public void onCreate(Bundle args) {
        if (args != null) {
            tab = args.getString("tab", tab); theme = args.getString("theme", theme);
            wrapperDepth = Integer.parseInt(args.getString("wrapperDepth", "1"));
        }
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            verifyWrappedModels();
            activity = startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),
                    "app.morphe.test.ArchiveProbe$ProbeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            app.morphe.extension.reddit.settings.Settings.PROFILE_ARCHIVE.save(true);
            runOnMainSync(() -> { revision = newState(0); lazyState = newLazyState(); mount(activity); running = true; tracker.run(); });
            await(() -> controller() != null && ArchiveFixture.count(controller()) == 25, 20000, "First page composed");
            settle();
            check(nativeExclusionsVerified, "Native owner, ordinary-empty, flair and invalid-name exclusions");
            checkRows();
            screenshot("top");
            int searches = ArchiveFixture.searches;
            SystemClock.sleep(1400);
            check(ArchiveFixture.searches == searches, "Precomposition must not load all pages: " + searches + " -> " + ArchiveFixture.searches);
            int[] start = position(); swipe(.78f, .25f, 650); settle();
            check(distance(position()) > distance(start) + 100, "A real drag moves native feed");
            check(!visibleTag("fixture_header"), "Header scrolls away in the same drag");
            Object owner = controller();
            check((Boolean) ArchiveFixture.field(owner, "remembered"), "Rows leaving viewport keep controller alive");
            checkRows();
            swipe(.80f, .30f, 100); int[] released = position(); SystemClock.sleep(220);
            check(distance(position()) > distance(released), "Fling continues after touch release: " + distance(released) + " -> " + distance(position()));
            settle(); int[] anchor = position(); ArchiveFixture.holdPreviews = false;
            await(() -> !((java.util.Map<?, ?>) ArchiveFixture.field(owner, "details")).isEmpty(), 10000, "Delayed previews delivered");
            SystemClock.sleep(1800);
            check(position()[0] == anchor[0] && Math.abs(position()[1] - anchor[1]) <= 2,
                    "Delayed preview reflow retains first visible item and offset");
            check(ArchiveFixture.previews > 0, "Native visible keys trigger previews");
            for (int swipe = 0; swipe < 38 && ArchiveFixture.count(owner) < 73; swipe++) { swipe(.86f, .18f, 180); settle(); }
            await(() -> ArchiveFixture.count(owner) == 73, 20000, "Three pages load through visible-item events");
            checkRows(); check(!(Boolean) ArchiveFixture.field(owner, "more"), "End state reached");
            int beforeReverse = distance(position()); swipe(.25f, .80f, 500); settle();
            check(distance(position()) < beforeReverse, "Reverse drag moves immediately"); screenshot("scrolled");
            runOnMainSync(() -> { visible = false; bump(); });
            await(() -> !(Boolean) ArchiveFixture.field(owner, "visible"), 5000, "Visibility updates controller");
            int[] saved = position(); runOnMainSync(() -> { visible = true; bump(); }); settle();
            check(position()[0] == saved[0] && Math.abs(position()[1] - saved[1]) <= 2, "Visibility return retains item and offset");
            int beforeSlow = ArchiveFixture.searches;
            runOnMainSync(() -> { ArchiveFixture.slow = true; username = "slow_probe"; bump(); });
            await(() -> controller() != null && (Boolean) ArchiveFixture.field(controller(), "loading"), 5000, "Slow request starts");
            await(() -> ArchiveFixture.searches > beforeSlow, 15000, "Slow transport entered");
            Object old = controller();
            runOnMainSync(() -> { ArchiveFixture.slow = false; username = "empty_probe"; bump(); });
            await(() -> controller() != null && (Boolean) ArchiveFixture.field(controller(), "loaded"), 15000, "Empty profile finishes");
            check(!(Boolean) ArchiveFixture.field(old, "remembered"), "Old profile is forgotten");
            check(ArchiveFixture.count(old) == 0 && ArchiveFixture.count(controller()) == 0, "No stale profile results");
            check(ArchiveFixture.cancellations > 0, "Leaving profile cancels transport");
            runOnMainSync(() -> { ArchiveFixture.fail = true; username = "error_probe"; bump(); });
            await(() -> controller() != null && ArchiveFixture.field(controller(), "error") != null, 15000, "Error state delivered");
            runOnMainSync(() -> { ArchiveFixture.fail = false; ArchiveFixture.retry(controller()); });
            await(() -> ArchiveFixture.count(controller()) == 25, 15000, "Retry recovers first page");
            runOnMainSync(() -> ArchiveFixture.assertAuthor(controller(), "error_probe"));
            Object beforeOrdinary = controller();
            runOnMainSync(() -> { eligible = false; bump(); });
            await(() -> !(Boolean) ArchiveFixture.field(beforeOrdinary, "remembered"), 5000, "Ordinary feed disposes archive controller");
            check(ProfileArchivePatch.isPatchIncluded(), "Release bridge installed");
            result.putString("stream", assertions + " native runtime assertions passed for " + tab + "; QSF wrapper depth=" + wrapperDepth + "; drag/fling, paging, previews, cancellation, empty/error/retry.");
            result.putInt("assertions", assertions); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result);
        } finally { running = false; handler.removeCallbacks(tracker); }
    }
    private Object controller() { return ArchiveFixture.controller(username, tab.equals("VISITOR_COMMENT")); }
    private void verifyWrappedModels() {
        int saved = wrapperDepth;
        wrapperDepth = 0;
        try {
            for (String target : new String[] {"VISITOR_POST", "VISITOR_COMMENT"}) {
                Object section = sections(fixtureFeed(target, username, true, true, false)).get(0);
                Object model = sectionModel(section);
                check(model != null, "Direct hidden-history section recognized: " + target);
                String originalKey = key(section);
                for (int depth = 1; depth <= 2; depth++) {
                    section = wrapSection(section);
                    check(key(section).equals(originalKey), "QSF container delegates native section key");
                    check(sectionModel(section) == model, "QSF-wrapped hidden-history section recognized: " + target + ", depth=" + depth);
                }
            }
            check(sectionModel(null) == null && sectionModel(new Object()) == null, "Unrelated sections remain unrecognized");
        } finally { wrapperDepth = saved; }
    }
    private void bump() { writeState(revision, ((Integer) readState(revision)) + 1); }
    private interface Condition { boolean get(); }
    private void await(Condition condition, int timeout, String message) {
        long end = SystemClock.uptimeMillis() + timeout; boolean[] value = {false};
        while (SystemClock.uptimeMillis() < end) {
            runOnMainSync(() -> value[0] = condition.get());
            if (value[0]) { assertions++; return; } SystemClock.sleep(100);
        } throw new AssertionError(message);
    }
    private void check(boolean value, String message) {
        if (!value) throw new AssertionError(message); assertions++;
        Bundle status = new Bundle(); status.putString("stream", message); sendStatus(0, status);
    }
    private void settle() { SystemClock.sleep(1400); waitForIdleSync(); }
    private int[] position() { int[][] value = {null}; runOnMainSync(() -> value[0] = inspectPosition(lazyState)); return value[0]; }
    private int distance(int[] value) { return value[0] * 10000 + value[1]; }
    private boolean visibleTag(String tag) {
        boolean[] result = {false}; runOnMainSync(() -> {
            View view = activity.getWindow().getDecorView().findViewWithTag(tag);
            result[0] = view != null && view.getGlobalVisibleRect(new android.graphics.Rect());
        }); return result[0];
    }
    private void checkRows() {
        int[] count = {0}; runOnMainSync(() -> count[0] = rows(activity.getWindow().getDecorView()));
        check(count[0] > 0 && count[0] < 20, "Native feed virtualizes rows; attached=" + count[0]);
    }
    private static int rows(View view) {
        int count = view.getClass().getName().endsWith(".ArchiveRowView") ? 1 : 0;
        if (view instanceof android.widget.ListView || view instanceof android.widget.ScrollView) throw new AssertionError("Embedded scrolling view");
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) count += rows(group.getChildAt(i));
        return count;
    }
    private void swipe(float from, float to, int duration) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        runOnMainSync(() -> activity.getWindow().getDecorView().getGlobalVisibleRect(bounds));
        float x = bounds.centerX(); long down = SystemClock.uptimeMillis();
        pointer(down, down, MotionEvent.ACTION_DOWN, x, bounds.top + bounds.height() * from);
        int steps = Math.max(8, duration / 16);
        for (int i = 1; i <= steps; i++) {
            SystemClock.sleep(duration / steps);
            pointer(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, bounds.top + bounds.height() * (from + (to - from) * i / steps));
        }
        pointer(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, bounds.top + bounds.height() * to);
    }
    private void pointer(long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            // sendPointerSync waits for animations after ACTION_UP, which consumes the fling
            // before an assertion can sample it. This platform TestApi disables that wait.
            Object injected = android.app.UiAutomation.class.getMethod("injectInputEvent",
                    android.view.InputEvent.class, boolean.class, boolean.class)
                    .invoke(getUiAutomation(), event, action != MotionEvent.ACTION_MOVE, false);
            if (!Boolean.TRUE.equals(injected)) throw new AssertionError("Touch injection failed");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        finally { event.recycle(); }
    }
    private void screenshot(String name) throws java.io.IOException {
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(activity.getExternalFilesDir(null), "native-" + tab + "-" + name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } bitmap.recycle();
    }
    public static void mount(Activity activity) { throw new UnsupportedOperationException(); }
    public static void mountView(Activity activity, View compose) {
        activity.setContentView(compose);
        compose.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        }); compose.requestApplyInsets();
    }
    public static Object fixtureFeed(String tab, String username, boolean visible, boolean filtered, boolean flair) { throw new UnsupportedOperationException(); }
    public static Object sectionModel(Object section) { throw new UnsupportedOperationException(); }
    public static Object wrapSection(Object section) { throw new UnsupportedOperationException(); }
    public static Object wrapSections(Object section) {
        for (int i = 0; i < wrapperDepth; i++) section = wrapSection(section);
        return section;
    }
    public static final class TrackNothing implements Function1<Object, Unit> {
        public Unit invoke(Object ignored) { return unit(); }
    }
    public static List<?> sections(Object feed) { throw new UnsupportedOperationException(); }
    public static Unit unit() { throw new UnsupportedOperationException(); }
    public static Object newState(Object initial) { throw new UnsupportedOperationException(); }
    public static Object readState(Object state) { throw new UnsupportedOperationException(); }
    public static void writeState(Object state, Object value) { throw new UnsupportedOperationException(); }
    public static Object newLazyState() { throw new UnsupportedOperationException(); }
    public static void lazyColumn(Object state, Function1<Object, Unit> content, Object composer) { throw new UnsupportedOperationException(); }
    public static void addItems(Object scope, List<?> sections, Object composer) { throw new UnsupportedOperationException(); }
    public static String key(Object section) { throw new UnsupportedOperationException(); }
    public static void renderSection(Object section, Object composer) { throw new UnsupportedOperationException(); }
    public static void emitVisible(Object state) { throw new UnsupportedOperationException(); }
    public static boolean acceptVisible(List<?> value) {
        if (value.equals(lastVisible)) return false;
        lastVisible = value; return true;
    }
    public static int[] inspectPosition(Object state) { throw new UnsupportedOperationException(); }
    public static final class ProbeActivity extends Activity {
        @Override protected void attachBaseContext(android.content.Context base) {
            android.content.res.Configuration configuration = new android.content.res.Configuration();
            configuration.uiMode = theme.equals("light") ? android.content.res.Configuration.UI_MODE_NIGHT_NO
                    : android.content.res.Configuration.UI_MODE_NIGHT_YES;
            super.attachBaseContext(base.createConfigurationContext(configuration));
        }
        @Override public void onCreate(Bundle state) {
            setTheme(getResources().getIdentifier(theme.equals("light") ? "RedditTheme.AlienBlue" : "RedditTheme.Night", "style", getPackageName()));
            super.onCreate(state);
        }
    }
    public static final class Content implements Function2<Object, Integer, Unit> {
        public Unit invoke(Object composer, Integer flags) {
            readState(revision);
            Object[] excluded = {
                fixtureFeed("OWNER_POST", username, true, true, false),
                fixtureFeed("OWNER_COMMENT", username, true, true, false),
                fixtureFeed("VISITOR_POST", username, true, false, false),
                fixtureFeed("VISITOR_COMMENT", username, true, true, true),
                fixtureFeed("VISITOR_POST", "invalid?name", true, true, false)
            };
            for (Object original : excluded) if (ProfileArchivePatch.decorate(original, composer) != original)
                throw new AssertionError("Native ineligible feed replaced");
            nativeExclusionsVerified = true;
            Object feed = ProfileArchivePatch.decorate(fixtureFeed(tab, username, visible, eligible, false), composer);
            lazyColumn(lazyState, new Builder(eligible ? sections(feed) : java.util.Collections.emptyList(), composer), composer); return unit();
        }
    }
    public static final class Builder implements Function1<Object, Unit> {
        final List<?> rows; final Object composer;
        Builder(List<?> rows, Object composer) { this.rows = rows; this.composer = composer; }
        public Unit invoke(Object scope) { addItems(scope, rows, composer); return unit(); }
    }
    public static final class Keys implements Function1<Integer, Object> {
        final List<?> rows;
        public Keys(List<?> rows) { this.rows = rows; }
        public Object invoke(Integer index) { return index == 0 ? "probe_header" : key(rows.get(index - 1)); }
    }
    public static final class ContentTypes implements Function1<Integer, Object> {
        public Object invoke(Integer index) { return index == 0 ? "header" : "archive"; }
    }
    /** Validation patch adds the host's renamed Function4 interface. */
    public static final class ItemContent {
        final List<?> rows;
        public ItemContent(List<?> rows) { this.rows = rows; }
        public Object invoke(Object scope, Object index, Object composer, Object flags) {
            int i = ((Number) index).intValue();
            if (i == 0) ArchiveFixture.renderHeader(composer); else renderSection(rows.get(i - 1), composer); return unit();
        }
    }
}
