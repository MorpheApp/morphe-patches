/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3665
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class CopyTextPatch {

    /**
     * Interface to use obfuscated methods of the selection container's SelectionManager.
     */
    public interface SelectionManagerInterface {
        // Method is added during patching.
        void patch_release();
    }

    /**
     * Fraction of the touch slop a touch may move and still count as a tap.
     * Smaller than Android's slop so that a slight drag keeps the selection.
     */
    private static final float TAP_SLOP_FACTOR = 0.3f;

    /**
     * Max time between the two tap detection injection points for them to belong to the same touch.
     */
    private static final long TAP_DETECTION_WINDOW_NANOS = 100_000_000L;

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    // region Wrap the rich text renderer in a SelectionContainer.

    /**
     * Set while the original rich text renderer is invoked from inside the SelectionContainer.
     */
    private static final ThreadLocal<Boolean> IN_SELECTION_CONTAINER = new ThreadLocal<>();

    /**
     * Injection point. Called at the start of the Compose rich text renderer.
     *
     * @return If the renderer should be invoked inside a SelectionContainer instead.
     */
    public static boolean shouldWrapRichText() {
        try {
            return Settings.COPY_TEXT.get() && IN_SELECTION_CONTAINER.get() == null;
        } catch (Exception ex) {
            Logger.printException(() -> "shouldWrapRichText failure", ex);
            return false;
        }
    }

    /**
     * Injection point. Creates the content of the SelectionContainer, which invokes the original
     * rich text renderer with the original arguments.
     */
    public static Object createRichTextContent(Object[] args) {
        return new RichTextContent(args);
    }

    /**
     * Content lambda of the SelectionContainer. Implements the Compose Function2 interface,
     * the interface and its invoke(composer, changed) bridge method are added during patching.
     */
    public static final class RichTextContent {
        private final Object[] args;

        RichTextContent(Object[] args) {
            this.args = args;
        }

        /**
         * Called by the invoke(composer, changed) bridge method.
         */
        public void render(Object composer) {
            IN_SELECTION_CONTAINER.set(Boolean.TRUE);
            try {
                invokeRichText(args, composer);
            } finally {
                IN_SELECTION_CONTAINER.remove();
            }
        }
    }

    /**
     * Invokes the original rich text renderer with the given arguments, but with the composer
     * of the SelectionContainer content.
     */
    private static void invokeRichText(Object[] args, Object composer) {
        // Modified during patching.
    }

    // endregion

    // region Disable collapsing comments.

    /**
     * Injection point. Called when a comment is clicked, before it is collapsed or expanded.
     * Collapsing is triggered by the same touch that starts a text selection.
     *
     * @return If the click should be ignored.
     */
    public static boolean shouldBlockCommentCollapse(boolean collapsed) {
        return Settings.COPY_TEXT.get() && !collapsed;
    }

    // endregion

    // region A slight drag must not be treated as a tap that clears the selection.

    private static long tapDetectionStartTime;

    /**
     * Injection point. Called right before the selection container decides if a touch
     * was a tap, which clears the selection, or a drag.
     */
    public static void beginSelectionTapDetection() {
        tapDetectionStartTime = Settings.COPY_TEXT.get() ? System.nanoTime() : 0;
    }

    /**
     * Injection point. Called with the touch slop the tap detection above uses.
     * A smaller slop makes a slight drag count as a drag.
     */
    public static float adjustSelectionTapSlop(float slop) {
        long startTime = tapDetectionStartTime;
        if (startTime == 0) return slop;

        tapDetectionStartTime = 0;
        if (System.nanoTime() - startTime > TAP_DETECTION_WINDOW_NANOS) return slop;
        return slop * TAP_SLOP_FACTOR;
    }

    // endregion

    // region Keep the selection while touching and scrolling, a tap clears it.

    /**
     * Selection containers that currently have a selection.
     */
    private static final Set<SelectionManagerInterface> SELECTION_MANAGERS_WITH_SELECTION =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Injection point. Called when a selection container clears its selection.
     */
    public static void onSelectionRelease(SelectionManagerInterface selectionManager) {
        if (!Settings.COPY_TEXT.get()) return;

        SELECTION_MANAGERS_WITH_SELECTION.remove(selectionManager);
    }

    /**
     * Injection point. Called when a selection container changes its selection.
     */
    public static void onSelectionChanged(SelectionManagerInterface selectionManager, @Nullable Object selection) {
        if (!Settings.COPY_TEXT.get()) return;

        if (selection == null) {
            SELECTION_MANAGERS_WITH_SELECTION.remove(selectionManager);
        } else {
            SELECTION_MANAGERS_WITH_SELECTION.add(selectionManager);
        }
    }

    /**
     * Injection point. Called at the start of FocusManager.clearFocus().
     * <p>
     * Reddit's post detail screen clears focus on every touch down, to dismiss the keyboard.
     * The selection container then loses focus and drops the selection before a scroll can
     * even start. While there is a selection, focus clears caused by touch input are ignored.
     *
     * @return If clearing the focus should be skipped.
     */
    public static boolean shouldBlockFocusClear() {
        try {
            if (SELECTION_MANAGERS_WITH_SELECTION.isEmpty() || !Settings.COPY_TEXT.get()) {
                return false;
            }

            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                if ("dispatchTouchEvent".equals(frame.getMethodName())) return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "shouldBlockFocusClear failure", ex);
        }
        return false;
    }

    private static float touchDownX;
    private static float touchDownY;
    private static long touchDownTime;
    private static boolean tapCandidate;
    private static float tapSlop;

    /**
     * Injection point. Called with every touch event that enters Compose.
     * Since touch driven focus clears are ignored while there is a selection, a tap clears
     * the selection here instead. Dragging (scrolling, moving a selection handle) and long
     * presses keep the selection.
     */
    public static void onComposeTouchEvent(MotionEvent event) {
        try {
            if (!Settings.COPY_TEXT.get()) return;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tapCandidate = !SELECTION_MANAGERS_WITH_SELECTION.isEmpty();
                    touchDownX = event.getRawX();
                    touchDownY = event.getRawY();
                    touchDownTime = event.getEventTime();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (tapCandidate) {
                        if (tapSlop == 0) {
                            tapSlop = ViewConfiguration.get(Utils.getContext()).getScaledTouchSlop()
                                    * TAP_SLOP_FACTOR;
                        }
                        if (Math.abs(event.getRawX() - touchDownX) > tapSlop
                                || Math.abs(event.getRawY() - touchDownY) > tapSlop) {
                            tapCandidate = false;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (tapCandidate
                            && event.getEventTime() - touchDownTime < ViewConfiguration.getLongPressTimeout()) {
                        releaseSelections();
                    }
                    tapCandidate = false;
                    break;
                default: // Cancelled or another finger touched the screen.
                    tapCandidate = false;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onComposeTouchEvent failure", ex);
        }
    }

    private static void releaseSelections() {
        // Releasing removes the manager from the set.
        for (SelectionManagerInterface manager : new ArrayList<>(SELECTION_MANAGERS_WITH_SELECTION)) {
            manager.patch_release();
        }
    }

    // endregion
}
