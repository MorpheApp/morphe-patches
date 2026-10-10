package app.morphe.extension.reddit.patches;

import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class EnableTextSelectionPatch {

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

    private static Method richTextMethod;

    /**
     * Injection point. Called at the start of the Compose rich text renderer.
     *
     * @return If the renderer should be invoked inside a SelectionContainer instead.
     */
    public static boolean shouldWrapRichText() {
        try {
            return Settings.ENABLE_TEXT_SELECTION.get() && IN_SELECTION_CONTAINER.get() == null;
        } catch (Exception ex) {
            Logger.printException(() -> "shouldWrapRichText failure", ex);
            return false;
        }
    }

    /**
     * Injection point. Creates the content of the SelectionContainer, which invokes the original
     * rich text renderer with the original arguments.
     * <p>
     * A proxy is used because the Compose runtime expects the app's own (R8 renamed)
     * function interface, which can only be referenced by name at runtime.
     */
    public static Object createRichTextContent(Class<?> owner, String methodName, Object[] args) {
        try {
            ClassLoader loader = owner.getClassLoader();
            Class<?> function2 = Class.forName("kotlin.jvm.functions.Function2", false, loader);
            return Proxy.newProxyInstance(loader, new Class<?>[]{function2},
                    new RichTextContent(owner, methodName, args));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static final class RichTextContent implements InvocationHandler {
        private final Class<?> owner;
        private final String methodName;
        private final Object[] args;

        RichTextContent(Class<?> owner, String methodName, Object[] args) {
            this.owner = owner;
            this.methodName = methodName;
            this.args = args;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] methodArgs) {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return proxy == methodArgs[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return toString();
                }
            }

            // Function2.invoke(composer, changed)
            if (methodArgs != null && methodArgs.length == 2) {
                invokeRichText(methodArgs[0]);
            }
            return null;
        }

        private void invokeRichText(Object composer) {
            Object[] callArgs = args.clone();

            // The trailing Integer arguments are the Compose $changed/$default flags,
            // the argument before them is the Composer. The flags are passed unchanged
            // because the composable applies its default parameter values depending on them.
            int composerIndex = callArgs.length - 1;
            while (composerIndex >= 0 && callArgs[composerIndex] instanceof Integer) {
                composerIndex--;
            }
            callArgs[composerIndex] = composer;

            IN_SELECTION_CONTAINER.set(Boolean.TRUE);
            try {
                findRichTextMethod(owner, methodName, callArgs).invoke(null, callArgs);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new RuntimeException(cause);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                IN_SELECTION_CONTAINER.remove();
            }
        }
    }

    private static synchronized Method findRichTextMethod(
            Class<?> owner, String name, Object[] args) throws NoSuchMethodException {
        if (richTextMethod != null) return richTextMethod;

        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals(name)
                    || !Modifier.isStatic(method.getModifiers())
                    || types.length != args.length) {
                continue;
            }

            boolean matches = true;
            for (int i = 0; i < types.length && matches; i++) {
                matches = isCompatible(types[i], args[i]);
            }
            if (matches) {
                method.setAccessible(true);
                richTextMethod = method;
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static boolean isCompatible(Class<?> type, Object arg) {
        if (type == boolean.class) return arg instanceof Boolean;
        if (type == int.class) return arg instanceof Integer;
        return arg == null || type.isInstance(arg);
    }

    // endregion

    // region Disable collapsing comments.

    /**
     * Injection point. Called when an expanded comment is about to be collapsed.
     * Collapsing is triggered by the same touch that starts a text selection.
     */
    public static boolean shouldBlockCommentCollapse() {
        return Settings.ENABLE_TEXT_SELECTION.get();
    }

    // endregion

    // region A slight drag must not be treated as a tap that clears the selection.

    private static long tapDetectionStartTime;

    /**
     * Injection point. Called right before the selection container decides if a touch
     * was a tap, which clears the selection, or a drag.
     */
    public static void beginSelectionTapDetection() {
        tapDetectionStartTime = Settings.ENABLE_TEXT_SELECTION.get() ? System.nanoTime() : 0;
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
    private static final Set<Object> SELECTION_MANAGERS_WITH_SELECTION =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Name of the method that clears a selection, set during patching.
     */
    private static String selectionReleaseMethodName;

    /**
     * Injection point. Called when a selection container clears its selection.
     */
    public static void onSelectionRelease(Object selectionManager) {
        SELECTION_MANAGERS_WITH_SELECTION.remove(selectionManager);
    }

    /**
     * Injection point. Called when a selection container changes its selection.
     */
    public static void onSelectionChanged(Object selectionManager, Object selection, String releaseMethodName) {
        selectionReleaseMethodName = releaseMethodName;

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
            if (SELECTION_MANAGERS_WITH_SELECTION.isEmpty() || !Settings.ENABLE_TEXT_SELECTION.get()) {
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
            if (!Settings.ENABLE_TEXT_SELECTION.get()) return;

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

    private static void releaseSelections() throws ReflectiveOperationException {
        if (selectionReleaseMethodName == null) return;

        for (Object manager : new ArrayList<>(SELECTION_MANAGERS_WITH_SELECTION)) {
            Method release = manager.getClass().getDeclaredMethod(selectionReleaseMethodName);
            release.setAccessible(true);
            release.invoke(manager);
        }
    }

    // endregion
}
