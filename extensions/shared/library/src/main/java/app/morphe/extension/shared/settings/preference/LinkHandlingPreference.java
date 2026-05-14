/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * A preference that guides the user through re-assigning app links
 * from the original package to the patched package.
 * <p>
 * Tapping this preference shows a two-step dialog:
 *   1. Opens the App Info screen of the original package so the user
 *      can clear its link-handling associations.
 *   2. After confirmation opens the App Info screen of the patched
 *      package so the user can enable link-handling there.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LinkHandlingPreference extends Preference {

    public LinkHandlingPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public LinkHandlingPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LinkHandlingPreference(Context context) {
        super(context);
    }

    private Application getApplication() {
        return ((Activity) getContext()).getApplication();
    }

    @Override
    protected void onClick() {
        try {
            String key = getKey();
            if (key == null || !key.contains(":")) {
                Logger.printException(() -> "LinkHandlingPreference malformed key: " + getKey());
                return;
            }

            Context context = getContext();
            String originalPackage = key.substring(key.indexOf(':') + 1);
            String patchedPackage = context.getPackageName();

            showStep1Dialog(context, originalPackage, patchedPackage);
        } catch (Exception ex) {
            Logger.printException(() -> "onClick failure", ex);
        }
    }

    private static void showStep1Dialog(Context context, String originalPackage, String patchedPackage) {
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_link_handling_step1_title"),
                str("morphe_link_handling_step1_message"),
                null,
                str("morphe_link_handling_open"),
                () -> {
                    openAppLinkSettings(context, originalPackage);
                    showStep2Dialog(context, patchedPackage);
                },
                null,
                null,
                null,
                true
        );

        dialogPair.first.show();
    }

    private static void showStep2Dialog(Context context, String patchedPackage) {
        Utils.runOnMainThreadDelayed(() -> {
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_link_handling_step2_title"),
                    str("morphe_link_handling_step2_message"),
                    null,
                    str("morphe_link_handling_open"),
                    () -> openAppLinkSettings(context, patchedPackage),
                    null,
                    null,
                    null,
                    true
            );

            dialogPair.first.show();
        }, 300);
    }

    private static void openAppLinkSettings(Context context, String packageName) {
        try {
            Intent intent = new Intent(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
                    : android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "openAppLinkSettings failure: " + packageName, ex);
        }
    }
}
