/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5457
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.reddit.deeplink.PostSubmitDeeplinkModule;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import kotlin.jvm.functions.Function0;

/**
 * Adds a Create post button to the top bar of communities while the Create button of the navigation bar is hidden.
 */
@SuppressWarnings("unused")
public final class SubredditCreateButtonPatch {

    /**
     * Added to the subreddit header state class during patching.
     */
    public interface HeaderState {
        @Nullable
        String patch_getDisplayName();
    }

    // Read once so the Compose slot table shape stays fixed until restart.
    private static final boolean ENABLED =
            Settings.HIDE_CREATE_BUTTON.get() && Settings.SUBREDDIT_CREATE_BUTTON.get();

    private static final String SUBREDDIT_PREFIX = "r/";

    private static final int ICON_ADD =
            ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "icon_add");

    // Must use anonymous declaration.
    // Using a lambda creates a different bytecode that throws AbstractMethodError when clicked.
    // noinspection Convert2Lambda
    private static final Function0<Object> NO_OP_CLICK = new Function0<>() {
        @Override
        public Object invoke() {
            return null;
        }
    };

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * Injection point.
     */
    public static int getIcon() {
        return ICON_ADD;
    }

    /**
     * Injection point.
     */
    public static String getLabel() {
        return str("morphe_subreddit_create_button_label");
    }

    /**
     * Injection point.
     */
    public static String getDescription(@Nullable Object state) {
        try {
            String name = getDisplayName(state);
            if (name.isEmpty()) {
                return getLabel();
            }
            return str("morphe_subreddit_create_button_description", name);
        } catch (Exception ex) {
            Logger.printException(() -> "getDescription failure", ex);
            return getLabel();
        }
    }

    /**
     * Injection point.
     */
    public static Function0<Object> getClick(@Nullable Object state) {
        try {
            String name = getDisplayName(state);
            if (name.isEmpty()) {
                return NO_OP_CLICK;
            }
            // Must use anonymous declaration.
            // Using a lambda creates a different bytecode that throws AbstractMethodError when clicked.
            // noinspection Convert2Lambda
            return new Function0<>() {
                @Override
                public Object invoke() {
                    openPostComposer(name);
                    return null;
                }
            };
        } catch (Exception ex) {
            Logger.printException(() -> "getClick failure", ex);
            return NO_OP_CLICK;
        }
    }

    private static void openPostComposer(String name) {
        try {
            Activity activity = Utils.getActivity();
            Bundle extras = new Bundle();
            extras.putString("subreddit_name", name);
            Intent intent = PostSubmitDeeplinkModule.submit(activity, extras);
            // Open the composer in the current activity instead of the deep link activity.
            intent.setComponent(activity.getComponentName());
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "openPostComposer failure", ex);
        }
    }

    private static String getDisplayName(@Nullable Object state) {
        if (!(state instanceof HeaderState headerState)) {
            return "";
        }
        String name = headerState.patch_getDisplayName();
        if (name == null) {
            return "";
        }
        // The display name is prefixed, such as "r/pics".
        return name.startsWith(SUBREDDIT_PREFIX)
                ? name.substring(SUBREDDIT_PREFIX.length())
                : name;
    }
}
