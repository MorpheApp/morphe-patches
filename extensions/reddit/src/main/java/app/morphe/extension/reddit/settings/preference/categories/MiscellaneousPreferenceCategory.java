/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.AppIconPatch;
import app.morphe.extension.reddit.patches.DownloadAllFromGalleryPatch;
import app.morphe.extension.reddit.patches.DownloadLocationPatch;
import app.morphe.extension.reddit.patches.EnableDebuggingPatch;
import app.morphe.extension.reddit.patches.OpenLinksDirectlyPatch;
import app.morphe.extension.reddit.patches.OpenLinksExternallyPatch;
import app.morphe.extension.reddit.patches.OpenSystemShareSheetPatch;
import app.morphe.extension.reddit.patches.PrivateBrowsingModePatch;
import app.morphe.extension.reddit.patches.RedgifsAudioPatch;
import app.morphe.extension.reddit.patches.SanitizeSharingLinksPatch;
import app.morphe.extension.reddit.patches.SharePostContentPatch;
import app.morphe.extension.reddit.settings.RedditActivityHook;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;
import app.morphe.extension.reddit.settings.preference.DownloadLocationPreference;
import app.morphe.extension.reddit.settings.preference.FeatureFlagsManagerPreference;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedSettings;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.settings.preference.ExportLogToClipboardPreference;
import app.morphe.extension.shared.settings.preference.ImportExportPreference;
import app.morphe.extension.shared.settings.preference.SortedListPreference;
import app.morphe.extension.shared.settings.preference.URLLinkPreference;
import app.morphe.extension.shared.settings.preference.about.MorpheAboutPreference;

@SuppressWarnings("deprecation")
public class MiscellaneousPreferenceCategory extends ConditionalPreferenceCategory {
    public MiscellaneousPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen, "morphe_miscellaneous_screen",
                "morphe_misc_screen_title", "morphe_miscellaneous_screen_summary");
    }

    @Override
    public boolean getSettingsStatus() {
        return AppIconPatch.isPatchIncluded() ||
                DownloadAllFromGalleryPatch.isPatchIncluded() ||
                DownloadLocationPatch.isPatchIncluded() ||
                EnableDebuggingPatch.isPatchIncluded() ||
                OpenLinksDirectlyPatch.isPatchIncluded() ||
                OpenLinksExternallyPatch.isPatchIncluded() ||
                OpenSystemShareSheetPatch.isPatchIncluded() ||
                PrivateBrowsingModePatch.isPatchIncluded() ||
                RedgifsAudioPatch.isPatchIncluded() ||
                SanitizeSharingLinksPatch.isPatchIncluded() ||
                SharePostContentPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        MorpheAboutPreference.showVancedAsPastContributor(false);
        Preference about = new MorpheAboutPreference(context);
        about.setTitle(str("morphe_about_screen_title"));
        about.setSummary(str("morphe_about_summary"));
        addPreference(about);

        ImportExportPreference importPref = new ImportExportPreference(context);
        importPref.setTitle(str("morphe_pref_import_export_title"));
        importPref.setSummary(str("morphe_pref_import_export_summary"));
        addPreference(importPref);

        addPreference(new SortedListPreference(context, BaseSettings.MORPHE_LANGUAGE));
        addPreference(new CustomDialogListPreference(context, SharedSettings.PATCHES_UPDATE_NOTICE));

        if (AppIconPatch.isPatchIncluded()) {
            addPreference(AppIconPatch.getIconPreference(context));
        }
        if (DownloadAllFromGalleryPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DOWNLOAD_ALL_FROM_GALLERY
            ));
        }
        if (DownloadLocationPatch.isPatchIncluded()) {
            addPreference(new DownloadLocationPreference(context));
        }
        if (OpenLinksDirectlyPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.OPEN_LINKS_DIRECTLY
            ));
        }
        if (OpenLinksExternallyPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.OPEN_LINKS_EXTERNALLY
            ));
        }
        if (OpenSystemShareSheetPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    SharedSettings.OPEN_SYSTEM_SHARE_SHEET
            ));
        }
        if (PrivateBrowsingModePatch.isPatchIncluded()) {
            addPreference(new CustomDialogListPreference(
                    context,
                    Settings.PRIVATE_BROWSING_MODE
            ));
        }
        if (RedgifsAudioPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REDGIFS_AUDIO
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REDGIFS_HD
            ));
        }
        if (SanitizeSharingLinksPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SANITIZE_SHARING_LINKS
            ));
        }
        if (SharePostContentPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SHARE_POST_CONTENT
            ));
        }

        addPreference(new CustomDialogListPreference(context, BaseSettings.DEBUG_LEVEL));
        if (EnableDebuggingPatch.isPatchIncluded()) {
            Preference exportLogs = new ExportLogToClipboardPreference(context);
            exportLogs.setTitle(str("morphe_debug_export_logs_title"));
            exportLogs.setSummary(str("morphe_debug_export_logs_summary"));
            addPreference(exportLogs);

            addPreference(new FeatureFlagsManagerPreference(context));
        }

        // Include original privacy policy link that was changed to open Morphe settings.
        if (RedditActivityHook.isPrivacyPolicyReplaced()) {
            URLLinkPreference preference = new URLLinkPreference(context);
            preference.setTitle(str("reddit_privacy_policy"));
            preference.externalURL = ResourceUtils.getString("privacy_policy_uri");
            addPreference(preference);
        }

        Utils.setPreferenceTitlesToMultiLine(this);
    }
}
