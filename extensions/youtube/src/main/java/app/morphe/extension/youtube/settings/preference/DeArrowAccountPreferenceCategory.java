/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.text.Html;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ResettableEditTextPreference;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.dearrow.DeArrowAccountRequest;
import app.morphe.extension.youtube.patches.dearrow.DeArrowAccountRequest.AccountInfo;
import app.morphe.extension.youtube.settings.Settings;

/**
 * DeArrow account of the user. The preferences declared by the patch, such as the private user id,
 * are kept, and the username and number of submissions are loaded and added after them.
 * <p>
 * The loaded preferences do not show in search results, since they are not available
 * when the settings first load.
 */
@SuppressWarnings({"unused", "deprecation"})
public class DeArrowAccountPreferenceCategory extends PreferenceCategory {

    private static final String SB_BROWSER_USER_URL = "https://sb.ltn.fi/userid/";

    /**
     * Preferences added after the account loaded, which are removed before loading it again.
     */
    private final List<Preference> accountPreferences = new ArrayList<>();

    public DeArrowAccountPreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public DeArrowAccountPreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DeArrowAccountPreferenceCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToActivity() {
        try {
            super.onAttachedToActivity();

            removeAccountPreferences();
            if (!DeArrowAccountRequest.userHasPrivateUserId()) {
                // The user has never voted or submitted anything, so there is no account yet.
                return;
            }

            Preference loadingPreference = createPreference(getContext());
            loadingPreference.setTitle(str("morphe_dearrow_account_loading"));
            loadingPreference.setSelectable(false);
            addAccountPreference(loadingPreference);

            Utils.runOnBackgroundThread(() -> {
                AccountInfo info = DeArrowAccountRequest.fetchAccountInfo();
                Utils.runOnMainThread(() -> showAccountInfo(loadingPreference, info));
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onAttachedToActivity failure", ex);
        }
    }

    private void showAccountInfo(Preference loadingPreference, @Nullable AccountInfo info) {
        try {
            if (info == null) {
                loadingPreference.setTitle(str("morphe_dearrow_account_connection_failure"));
                return;
            }
            removeAccountPreferences();
            Context context = getContext();

            // There is no reason to set a username before submitting anything.
            if (info.hasUserName() || info.titleSubmissions() > 0 || info.thumbnailSubmissions() > 0) {
                addAccountPreference(createUserNamePreference(context, info.userName()));
            }

            addAccountPreference(createSubmissionsPreference(context, info, info.titleSubmissions(),
                    "morphe_dearrow_account_titles", "titles"));
            addAccountPreference(createSubmissionsPreference(context, info, info.thumbnailSubmissions(),
                    "morphe_dearrow_account_thumbnails", "thumbnails"));
        } catch (Exception ex) {
            Logger.printException(() -> "showAccountInfo failure", ex);
        }
    }

    private static ResettableEditTextPreference createUserNamePreference(Context context, String userName) {
        ResettableEditTextPreference preference = new ResettableEditTextPreference(context);
        preference.setSingleLineTitle(false);
        preference.setTitle(Html.fromHtml(str("morphe_dearrow_account_username", userName),
                Html.FROM_HTML_MODE_COMPACT));
        preference.setSummary(str("morphe_dearrow_account_username_change"));
        preference.setText(userName);
        preference.setOnPreferenceChangeListener((pref, value) -> {
            String newUserName = value.toString();
            Utils.runOnBackgroundThread(() -> {
                String errorMessage = DeArrowAccountRequest.setUsername(newUserName);
                Utils.runOnMainThread(() -> {
                    try {
                        if (errorMessage == null) {
                            preference.setTitle(Html.fromHtml(str("morphe_dearrow_account_username", newUserName),
                                    Html.FROM_HTML_MODE_COMPACT));
                            Utils.showSnackbarLong(str("morphe_dearrow_account_username_changed"));
                        } else {
                            preference.setText(userName);
                            showErrorDialog(context, errorMessage);
                        }
                    } catch (Exception ex) {
                        Logger.printException(() -> "setUsername result failure", ex);
                    }
                });
            });
            return true;
        });
        preference.setEnabled(Settings.SB_PRIVATE_USER_ID.isAvailable());
        return preference;
    }

    /**
     * @param view The tab of the sb.ltn.fi user page that lists the submissions.
     */
    private static Preference createSubmissionsPreference(Context context, AccountInfo info, int count,
                                                          String stringPrefix, String view) {
        Preference preference = createPreference(context);
        String formatted = NumberFormat.getNumberInstance().format(count);
        preference.setTitle(Html.fromHtml(str(stringPrefix, formatted), Html.FROM_HTML_MODE_COMPACT));
        if (count > 0) {
            preference.setSummary(str(stringPrefix + "_summary"));
            preference.setOnPreferenceClickListener(pref -> {
                Utils.openLink(SB_BROWSER_USER_URL + info.publicUserId() + "?view=" + view);
                return true;
            });
        } else {
            preference.setSelectable(false);
        }
        return preference;
    }

    private static Preference createPreference(Context context) {
        Preference preference = new Preference(context);
        preference.setSingleLineTitle(false);
        return preference;
    }

    private static void showErrorDialog(Context context, String message) {
        CustomDialog.create(
                context,
                null,
                message,
                null,
                null,
                () -> {},
                null,
                null,
                null,
                true
        ).first.show();
    }

    private void addAccountPreference(Preference preference) {
        accountPreferences.add(preference);
        addPreference(preference);
    }

    private void removeAccountPreferences() {
        for (Preference preference : accountPreferences) {
            removePreference(preference);
        }
        accountPreferences.clear();
    }
}
