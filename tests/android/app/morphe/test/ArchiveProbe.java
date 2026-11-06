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
import app.morphe.extension.reddit.patches.archive.ArchiveMixedFixture;
import app.morphe.extension.reddit.patches.archive.RecoveryFixture;
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
    public static boolean mixed;
    public static boolean recovery;
    public static boolean recoveryFailure;
    private static List<?> lastVisible;
    private Activity activity;
    private volatile Activity latestReader;
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
            mixed = args.getString("mode", "hidden").equals("mixed");
            recovery = args.getString("mode", "hidden").startsWith("recovery");
            recoveryFailure = args.getString("mode", "hidden").equals("recovery_failure");
        }
        start();
    }
    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        super.callActivityOnCreate(activity, state);
        if (activity.getClass().getName().endsWith(".ArchiveReaderActivity")) latestReader = activity;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            verifyWrappedModels();
            activity = startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),
                    "app.morphe.test.ArchiveProbe$ProbeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            app.morphe.extension.reddit.settings.Settings.PROFILE_ARCHIVE.save(true);
            if (recovery) RecoveryFixture.prepare();
            if (mixed) {
                ArchiveMixedFixture.verifyProjection();
                ArchiveFixture.slow = true;
            }
            runOnMainSync(() -> { revision = newState(0); lazyState = newLazyState(); mount(activity); running = true; tracker.run(); });
            if (recovery) { runRecovery(result); return; }
            if (mixed) { runMixed(result); return; }
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
            if (recovery && activity != null) try { screenshot("recovery-failure"); } catch (Throwable ignored) {}
            result.putString("stream", android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result);
        } finally { running = false; handler.removeCallbacks(tracker); }
    }
    private Object controller() { return ArchiveFixture.controller(username, tab.equals("VISITOR_COMMENT")); }
    private String screenText() {
        android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        StringBuilder result = new StringBuilder();
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        if (root != null) queue.add(root);
        while (!queue.isEmpty()) {
            android.view.accessibility.AccessibilityNodeInfo node = queue.remove();
            // UiAutomation caches Compose nodes even after a text-only redraw.
            // Refresh each node so assertions describe the current screen.
            if (!node.refresh()) continue;
            if (node.getText() != null) result.append(node.getText()).append('\n');
            for (int i = 0; i < node.getChildCount(); i++) { var child = node.getChild(i); if (child != null) queue.add(child); }
        }
        return result.toString();
    }
    private void runRecovery(Bundle result) throws Exception {
        await(() -> RecoveryFixture.requests > 0, 10000, "Direct native models start ID lookup");
        check(!RecoveryFixture.ready(), "Delayed archive leaves native input usable");
        if (recoveryFailure) {
            RecoveryFixture.fail = true; RecoveryFixture.hold = false;
            await(() -> RecoveryFixture.failuresReceived >= 3, 15000, "Rate-limited lookups finish without replacing native content");
            check(!RecoveryFixture.ready() && !screenText().contains("Undeleted"), "Archive failure has no success tag");
            check(screenText().contains("[deleted]"), "Native profile remains readable after rate limit");
            check(RecoveryFixture.requests == 1, "Shared provider backoff prevents further transport requests");
            result.putString("stream", (assertions + RecoveryFixture.assertions) + " runtime failure assertions passed; native content remains usable after HTTP 429.");
            finish(Activity.RESULT_OK, result); return;
        }
        int[] anchor = position();
        RecoveryFixture.hold = false;
        await(RecoveryFixture::ready, 20000, "Post and nested comment update without tapping or refreshing");
        awaitUi(() -> screenText().contains("Undeleted"), 15000, "Native profile preview publishes recovered text and tag automatically");
        check(screenText().contains("Restored bold text"), "Native profile preview strips Markdown and stays readable");
        check(screenText().split("Undeleted", -1).length == 2, "One native metadata tag");
        check(position()[0] == anchor[0] && Math.abs(position()[1] - anchor[1]) <= 2, "Delayed recovery preserves native scroll anchor");
        RecoveryFixture.verify(); screenshot("recovery");
        runOnMainSync(() -> app.morphe.extension.reddit.settings.Settings.PROFILE_ARCHIVE.save(false));
        await(() -> !RecoveryFixture.ready(), 5000, "Disable automatically invalidates composed recovery");
        RecoveryFixture.verifyDisabled();
        awaitUi(() -> !screenText().contains("Undeleted"), 5000, "Disabled feature removes metadata tag");
        runOnMainSync(() -> app.morphe.extension.reddit.settings.Settings.PROFILE_ARCHIVE.save(true));
        await(RecoveryFixture::ready, 20000, "Re-enable starts new observed lookup");
        runOnMainSync(() -> { RecoveryFixture.shown = false; bump(); }); settle();
        check(!screenText().contains("Restored bold text"), "Departed screen no longer receives native content");
        runOnMainSync(() -> { RecoveryFixture.shown = true; bump(); });
        awaitUi(() -> screenText().contains("Undeleted"), 5000, "Returning screen reuses cached recovery");
        result.putInt("assertions", assertions + RecoveryFixture.assertions);
        result.putString("stream", (assertions + RecoveryFixture.assertions) + " recovery runtime assertions passed; native profile renderer, direct post/comment projections, native Markdown parsing, delayed updates, stable anchor, disable/re-enable and navigation. Signed-in feed/discussion acceptance remains separate.");
        finish(Activity.RESULT_OK, result);
    }
    private void runMixed(Bundle result) throws Exception {
        boolean comments = tab.equals("VISITOR_COMMENT");
        await(() -> ArchiveMixedFixture.liveCount() > 0, 5000, "Live cards render before archive response");
        check(ArchiveMixedFixture.recoveredCount() == 0, "Archive loading leaves native cards usable");
        settle();
        swipe(.82f, .25f, 600); settle();
        int[] before = position();
        String[] beforeKey = {null}; runOnMainSync(() -> beforeKey[0] = ArchiveMixedFixture.firstKey(before[0]));
        ArchiveFixture.slow = false;
        await(() -> controller() != null && ArchiveMixedFixture.recoveredCount() > 0, 20000, "Archive cards merge into native feed");
        settle();
        int[] after = position(); String[] afterKey = {null};
        runOnMainSync(() -> afterKey[0] = ArchiveMixedFixture.firstKey(after[0]));
        check(beforeKey[0].equals(afterKey[0]) && Math.abs(before[1] - after[1]) <= 2, "Late insertion retains native visible key and offset");
        check(ArchiveMixedFixture.liveCount() > 0 && ArchiveMixedFixture.recoveredCount() > 0, "Real native and archive cards share one list");
        runOnMainSync(ArchiveMixedFixture::verifyIdentityAndMapping);
        check(ArchiveMixedFixture.hasNativePlaceholder(), "Deleted placeholder retains its native section and identity");
        check(ArchiveMixedFixture.hasStatus("Archived"), "Archive-only entries retain distinct provenance");
        check(!ArchiveMixedFixture.hasIndicator(), "Ordinary profiles do not show hidden-history notice");
        screenshot("mixed"); checkRows();
        ArchiveFixture.holdPreviews = false;
        for (int page = 2; page <= 3; page++) {
            int nextPage = page;
            runOnMainSync(() -> { ArchiveMixedFixture.page = nextPage; bump(); });
            for (int count = 0; count < 8; count++) { swipe(.85f, .20f, 160); settle(); }
        }
        await(() -> ArchiveFixture.count(controller()) == 73, 20000, "Three mixed pages reach archive-only tail");
        runOnMainSync(ArchiveMixedFixture::verifyIdentityAndMapping);
        check(ArchiveMixedFixture.recoveredCount() > 45, "Archive tail survives native exhaustion");
        int distance = distance(position()); swipe(.25f, .80f, 500); settle();
        check(distance(position()) < distance, "Mixed feed reverses immediately");
        if (!comments) {
            runOnMainSync(() -> { ArchiveMixedFixture.sortChanged("TOP"); bump(); }); settle();
            check(ArchiveMixedFixture.recoveredCount() == 0, "Other sorts keep native cards and suppress archive results");
            runOnMainSync(ArchiveMixedFixture::verifyIdentityAndMapping);
            runOnMainSync(() -> { ArchiveMixedFixture.sortChanged("NEW"); bump(); });
            await(() -> ArchiveMixedFixture.recoveredCount() > 0, 20000, "Switching back to New resumes recovery");
        }
        settle(); // Wait for the sort's new list to complete layout before recording its anchor.
        int[] readerAnchor = position();
        String[] readerKey = {null};
        runOnMainSync(() -> readerKey[0] = ArchiveMixedFixture.firstKey(readerAnchor[0]));
        ActivityMonitor monitor = addMonitor("app.morphe.extension.reddit.patches.archive.ArchiveReaderActivity", null, false);
        runOnMainSync(() -> ArchiveMixedFixture.openReader(activity));
        Activity reader = waitForMonitorWithTimeout(monitor, 10000);
        check(reader != null, "Legacy reader remains callable for compatibility");
        settle();
        android.widget.ScrollView[] readerScroll = {null};
        runOnMainSync(() -> readerScroll[0] = findScroll(reader.getWindow().getDecorView()));
        check(readerScroll[0] != null && hasSelectableText(reader.getWindow().getDecorView()), "Reader contains selectable archived text");
        runOnMainSync(() -> readerScroll[0].scrollTo(0, 100));
        SystemClock.sleep(200);
        int readerOffset = readerScroll[0].getScrollY();
        check(readerOffset > 0, "Long archived text scrolls in reader");
        runOnMainSync(reader::recreate);
        await(() -> latestReader != null && latestReader != reader, 10000, "Reader recreates after configuration change");
        Activity rotated = latestReader;
        check(rotated != reader, "Reader recreates after configuration change"); settle();
        android.widget.ScrollView[] rotatedScroll = {null}; runOnMainSync(() -> rotatedScroll[0] = findScroll(rotated.getWindow().getDecorView()));
        check(Math.abs(rotatedScroll[0].getScrollY() - readerOffset) <= 2, "Reader retains its scroll offset");
        runOnMainSync(rotated::finish); removeMonitor(monitor); settle();
        int[] returnedAnchor = position();
        String[] returnedKey = {null};
        runOnMainSync(() -> returnedKey[0] = ArchiveMixedFixture.firstKey(returnedAnchor[0]));
        check(readerKey[0].equals(returnedKey[0]) && Math.abs(returnedAnchor[1] - readerAnchor[1]) <= 2,
                "Reader Back retains profile key and offset: " + readerKey[0] + "/" + readerAnchor[1]
                        + " -> " + returnedKey[0] + "/" + returnedAnchor[1]);
        Object old = controller();
        runOnMainSync(() -> { ArchiveMixedFixture.refresh(username, comments); bump(); });
        await(() -> controller() != old && ArchiveMixedFixture.recoveredCount() > 0, 20000, "Refresh replaces archive generation");
        runOnMainSync(() -> { username = "error_mixed"; ArchiveMixedFixture.page = 1; ArchiveFixture.fail = true; bump(); });
        await(() -> controller() != null && ArchiveFixture.field(controller(), "error") != null, 20000, "Archive failure delivered");
        check(ArchiveMixedFixture.liveCount() > 0, "Archive failure leaves native cards on screen");
        runOnMainSync(() -> { ArchiveFixture.fail = false; ArchiveFixture.retry(controller()); });
        await(() -> ArchiveMixedFixture.recoveredCount() > 0, 20000, "Mixed-feed Retry recovers");
        runOnMainSync(() -> { ArchiveMixedFixture.empty = true; ArchiveMixedFixture.page = 3; username = "empty_native"; bump(); });
        await(() -> controller() != null && "empty_native".equals(ArchiveFixture.field(controller(), "username"))
                && ArchiveMixedFixture.recoveredCount() > 0, 20000, "Confirmed empty native feed shows archive records");
        check(ArchiveMixedFixture.liveCount() == 0, "Empty native feed uses archive-only rows");
        result.putString("stream", assertions + " mixed native runtime assertions passed for " + tab + "; QSF depth=" + wrapperDepth);
        result.putInt("assertions", assertions); finish(Activity.RESULT_OK, result);
    }
    private static android.widget.ScrollView findScroll(View view) {
        if (view instanceof android.widget.ScrollView scroll) return scroll;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            android.widget.ScrollView found = findScroll(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private static boolean hasSelectableText(View view) {
        if (view instanceof android.widget.TextView text && text.isTextSelectable()) return true;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (hasSelectableText(group.getChildAt(i))) return true;
        return false;
    }
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
    private void awaitUi(Condition condition, int timeout, String message) {
        // Accessibility queries must run off the app's main thread, which services them.
        long end = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < end) {
            if (condition.get()) { assertions++; return; }
            SystemClock.sleep(100);
        }
        throw new AssertionError(message + ": " + screenText());
    }
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
            if (recovery) {
                RecoveryFixture.observe(composer);
                lazyColumn(lazyState, new Builder(RecoveryFixture.sections(), composer), composer); return unit();
            }
            if (mixed) {
                Object feed = ArchiveMixedFixture.decorate(username, tab.equals("VISITOR_COMMENT"), visible, composer);
                lazyColumn(lazyState, new Builder(sections(feed), composer), composer); return unit();
            }
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
