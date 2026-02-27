package app.morphe.extension.music.utils;

import android.app.Activity;
import android.content.Intent;

import app.morphe.extension.shared.Utils;

public class ExtendedUtils {

    private static final String SETTINGS_CLASS_DESCRIPTOR = "com.google.android.apps.youtube.music.settings.SettingsCompatActivity";
    private static final String SETTINGS_ATTRIBUTION_FRAGMENT_KEY = ":android:show_fragment";
    private static final String SETTINGS_ATTRIBUTION_FRAGMENT_VALUE = "com.google.android.apps.youtube.music.settings.fragment.SettingsHeadersFragment";
    private static final String SETTINGS_ATTRIBUTION_HEADER_KEY = ":android:no_headers";
    private static final int SETTINGS_ATTRIBUTION_HEADER_VALUE = 1;

    private static final String MUSIC_ACTIVITY_CLASS = "com.google.android.apps.youtube.music.activities.MusicActivity";

    public static void openSearch() {
        Activity mActivity = Utils.getActivity();
        if (mActivity == null) {
            return;
        }
        Intent intent = new Intent();
        setSearchIntent(mActivity, intent);
        mActivity.startActivity(intent);
    }

    public static void openSetting() {
        Activity mActivity = Utils.getActivity();
        if (mActivity == null) {
            return;
        }
        Intent intent = new Intent();
        intent.setPackage(mActivity.getPackageName());
        intent.setClassName(mActivity, SETTINGS_CLASS_DESCRIPTOR);
        intent.putExtra(SETTINGS_ATTRIBUTION_FRAGMENT_KEY, SETTINGS_ATTRIBUTION_FRAGMENT_VALUE);
        intent.putExtra(SETTINGS_ATTRIBUTION_HEADER_KEY, SETTINGS_ATTRIBUTION_HEADER_VALUE);
        mActivity.startActivity(intent);
    }

    public static void setSearchIntent(Activity mActivity, Intent intent) {
        intent.setAction(Intent.ACTION_SEARCH);
        intent.setClassName(mActivity, MUSIC_ACTIVITY_CLASS);
        intent.setPackage(mActivity.getPackageName());

        intent.putExtra("query", "");

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }
}