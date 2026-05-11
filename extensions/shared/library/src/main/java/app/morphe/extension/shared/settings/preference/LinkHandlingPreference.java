/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.net.Uri;
import android.os.Build;
import android.preference.Preference;
import android.preference.PreferenceManager;
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

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                String patchedPackage = Utils.getContext().getPackageName();
                DomainVerificationManager manager =
                        getContext().getSystemService(DomainVerificationManager.class);
                DomainVerificationUserState state =
                        manager.getDomainVerificationUserState(patchedPackage);
                if (state != null && !state.getHostToStateMap().isEmpty()
                        && state.getHostToStateMap().values().stream()
                        .allMatch(s -> s == DomainVerificationUserState.DOMAIN_STATE_SELECTED)) {
                    setEnabled(false);
                    setSummary(str("morphe_link_handling_summary_configured"));
                } else {
                    setSummary(str("morphe_link_handling_summary"));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "LinkHandlingPreference: domain check failure", ex);
            }
        }
    }

    @Override
    protected void onClick() {
        try {
            String key = getKey();
            if (key == null || !key.contains(":")) {
                Logger.printException(() -> "LinkHandlingPreference: malformed key: " + key);
                return;
            }
            String originalPackage = key.substring(key.indexOf(':') + 1);
            String patchedPackage = Utils.getContext().getPackageName();

            Activity activity = (Activity) getContext();
            showStep1Dialog(activity, originalPackage, patchedPackage);
        } catch (Exception ex) {
            Logger.printException(() -> "LinkHandlingPreference onClick failure", ex);
        }
    }

    private static void showStep1Dialog(Activity activity, String originalPackage, String patchedPackage) {
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                activity,
                str("morphe_link_handling_step1_title"),
                str("morphe_link_handling_step1_message"),
                null,
                str("morphe_link_handling_open"),
                () -> {
                    openAppLinkSettings(activity, originalPackage);
                    showStep2Dialog(activity, patchedPackage);
                },
                null,
                null,
                null,
                true
        );

        dialogPair.first.show();
    }

    private static void showStep2Dialog(Activity activity, String patchedPackage) {
        Utils.runOnMainThreadDelayed(() -> {
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    activity,
                    str("morphe_link_handling_step2_title"),
                    str("morphe_link_handling_step2_message"),
                    null,
                    str("morphe_link_handling_open"),
                    () -> openAppLinkSettings(activity, patchedPackage),
                    null,
                    null,
                    null,
                    true
            );

            dialogPair.first.show();
        }, 300);
    }

    private static void openAppLinkSettings(Activity activity, String packageName) {
        try {
            Intent intent = new Intent(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
                    : android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "openAppLinkSettings failure: " + packageName, ex);
        }
    }
}
