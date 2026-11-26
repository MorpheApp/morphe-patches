/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4148
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Runs commands with the command router of an activity, the same as the commands of server responses.
 */
@SuppressWarnings("unused")
public final class CommandRouterPatch {

    /**
     * Interface to use the obfuscated command router of an activity.
     */
    public interface CommandRouter {
        // Method is added during patching.
        void patch_executeCommand(byte[] command, Map<String, Object> context);
    }

    /**
     * Each activity has its own router, which can only run commands in that activity.
     * The routers hold their activity, so they are not kept strongly by the key.
     */
    private static final Map<Activity, WeakReference<CommandRouter>> routers = new WeakHashMap<>();

    /**
     * Runs a command in the activity that is shown, the same as a command of a server response.
     * Must be called from the main thread.
     *
     * @param context Values the command is run with, such as the interaction logger.
     *                The app can add values to it.
     * @return If an activity is shown, and the command was run.
     */
    public static boolean executeCommand(byte[] command, Map<String, Object> context) {
        return executeCommand(command, context, false);
    }

    /**
     * Runs a command in the activity that is shown, or with {@code runHidden}
     * in an activity that is not shown if no activity is shown.
     * Must be called from the main thread.
     *
     * @param runHidden If the command can wait in an activity that is not shown, such as in the background.
     *                  The app runs it once the activity is shown again.
     * @return If the command was run, or waits to be run.
     */
    public static boolean executeCommand(byte[] command, Map<String, Object> context, boolean runHidden) {
        CommandRouter hiddenRouter = null;
        for (Map.Entry<Activity, WeakReference<CommandRouter>> entry : routers.entrySet()) {
            Activity activity = entry.getKey();
            CommandRouter router = entry.getValue().get();
            if (router == null || activity == null || activity.isFinishing()) {
                continue;
            }

            // Stopped behind another screen, such as the Morphe settings, or in the background,
            // where nobody would see the result of the command.
            if (!activity.getWindow().getDecorView().isShown()) {
                if (runHidden && hiddenRouter == null) {
                    hiddenRouter = router;
                }
                continue;
            }

            router.patch_executeCommand(command, context);
            return true;
        }

        if (hiddenRouter == null) {
            return false;
        }

        hiddenRouter.patch_executeCommand(command, context);
        return true;
    }

    /**
     * Injection point.
     * <p>
     * Called when an activity creates its command router.
     */
    public static void setCommandRouter(CommandRouter router, Activity activity) {
        try {
            Utils.runOnMainThreadNowOrLater(() -> routers.put(activity, new WeakReference<>(router)));
        } catch (Exception ex) {
            Logger.printException(() -> "setCommandRouter failure", ex);
        }
    }
}
