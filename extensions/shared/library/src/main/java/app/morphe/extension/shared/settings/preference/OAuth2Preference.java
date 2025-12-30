package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.oauth2.requests.OAuth2Requester.isActivationCodeDataAvailable;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.oauth2.object.AccessTokenData;
import app.morphe.extension.shared.oauth2.object.ActivationCodeData;
import app.morphe.extension.shared.oauth2.requests.OAuth2Requester;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings("deprecation")
public abstract class OAuth2Preference extends Preference implements Preference.OnPreferenceClickListener {

    private final SharedPreferences.OnSharedPreferenceChangeListener listener
            = (sharedPreferences, str) -> Utils.runOnMainThread(this::updateUI);

    private void addChangeListener() {
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    private void removeChangeListener() {
        Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    private final Application.ActivityLifecycleCallbacks ACTIVITY_LIFECYCLE_CALLBACKS
            = new Application.ActivityLifecycleCallbacks() {

        public void onActivityResumed(@NonNull Activity activity) {
            Logger.printDebug(() -> "onActivityResumed");
            if (isActivationCodeDataAvailable()) {
                unregisterApplicationOnResumeCallback();
                getRefreshToken();
            }
        }

        public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
        public void onActivityStarted(@NonNull Activity a) {}
        public void onActivityPaused(@NonNull Activity a) {}
        public void onActivityStopped(@NonNull Activity a) {}
        public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
        public void onActivityDestroyed(@NonNull Activity a) {}
    };

    private void registerApplicationOnResumeCallback() {
        SpoofVideoStreamsPatch.getMainActivity().getApplication().registerActivityLifecycleCallbacks(
                ACTIVITY_LIFECYCLE_CALLBACKS
        );
    }

    private void unregisterApplicationOnResumeCallback() {
        SpoofVideoStreamsPatch.getMainActivity().getApplication().unregisterActivityLifecycleCallbacks(
                ACTIVITY_LIFECYCLE_CALLBACKS
        );
    }

    private void init() {
        setOnPreferenceClickListener(this);
    }

    public OAuth2Preference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public OAuth2Preference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public OAuth2Preference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public OAuth2Preference(Context context) {
        super(context);
        init();
    }

    protected void updateUI(boolean currentlySignedIn) {
        String summaryKey = currentlySignedIn
                ? "morphe_spoof_video_streams_sign_in_android_vr_about_summary_signed_in"
                : "morphe_spoof_video_streams_sign_in_android_vr_about_summary";
        setSummary(str(summaryKey));

        setEnabled(isSettingEnabled());
    }

    protected void updateUI() {
        updateUI(!BaseSettings.OAUTH2_REFRESH_TOKEN.get().isEmpty());
    }

    protected abstract boolean isSettingEnabled();

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        updateUI();
        addChangeListener();
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        removeChangeListener();
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String okButtonStringKey;
        Runnable okButtonRunnable;
        if (isActivationCodeDataAvailable()) {
            okButtonStringKey = "morphe_spoof_video_streams_sign_in_android_vr_dialog_get_authorization_token_text";
            okButtonRunnable = this::getRefreshToken;
        } else {
            okButtonStringKey = "morphe_spoof_video_streams_sign_in_android_vr_dialog_get_activation_code_text";
            okButtonRunnable = this::showActivationCodeDialog;
            registerApplicationOnResumeCallback();
        }

        CustomDialog.create(
                getContext(),
                // Title.
                str("morphe_spoof_video_streams_sign_in_android_vr_dialog_title"),
                // Message.
                str("morphe_spoof_video_streams_sign_in_android_vr_dialog_message"),
                // No EditText.
                null,
                // OK button text.
                str(okButtonStringKey),
                // OK button action.
                okButtonRunnable,
                // Cancel button action.
                null,
                // Neutral button text.
                str("morphe_spoof_video_streams_sign_in_android_vr_dialog_reset_text"),
                // Neutral button action.
                () -> {
                    OAuth2Requester.revokeToken();
                    updateUI(false);
                },
                // Dismiss dialog when onNeutralClick.
                true
        ).first.show();

        return true;
    }

    private void showActivationCodeDialog() {
        Utils.runOnBackgroundThread(() -> {
            ActivationCodeData activationCodeData = OAuth2Requester.getActivationCodeData();
            if (activationCodeData == null) {
                Utils.showToastLong(str("morphe_spoof_video_streams_sign_in_android_vr_toast_get_activation_code_failed"));
                return;
            }
            Utils.runOnMainThread(() -> {
                String userCode = activationCodeData.userCode;
                String verificationUrl = activationCodeData.verificationUrl;

                CustomDialog.create(
                        getContext(),
                        // Title.
                        str("morphe_spoof_video_streams_sign_in_android_vr_activation_code_dialog_title"),
                        // Message.
                        str("morphe_spoof_video_streams_sign_in_android_vr_activation_code_dialog_message", userCode),
                        // No EditText.
                        null,
                        // OK button text.
                        str("morphe_spoof_video_streams_sign_in_android_vr_activation_code_dialog_open_website_text"),
                        // OK button action.
                        () -> {
                            Utils.setClipboard(userCode);
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setData(Uri.parse(verificationUrl));
                            getContext().startActivity(i);
                        },
                        // Cancel button action (dismiss only).
                        null,
                        // Neutral button text.
                        null,
                        // Neutral button action.
                        null,
                        // Dismiss dialog when onNeutralClick.
                        false
                ).first.show();
            });
        });
    }

    private void getRefreshToken() {
        Utils.runOnBackgroundThread(() -> {
            AccessTokenData accessTokenData = OAuth2Requester.getRefreshTokenData();
            if (accessTokenData == null) {
                Utils.showToastLong(str("morphe_spoof_video_streams_sign_in_android_vr_toast_get_authorization_code_failed"));
                return;
            }
            String refreshToken = accessTokenData.refreshToken;
            if (refreshToken == null || refreshToken.isEmpty()) {
                return;
            }
            BaseSettings.OAUTH2_REFRESH_TOKEN.save(refreshToken);
            OAuth2Requester.setAuthorization(accessTokenData);

            Utils.runOnMainThread(() -> {
                updateUI(true);

                CustomDialog.create(
                        getContext(),
                        // Title.
                        str("morphe_spoof_video_streams_sign_in_android_vr_success_dialog_title"),
                        // Message.
                        str("morphe_spoof_video_streams_sign_in_android_vr_success_dialog_message"),
                        // No EditText.
                        null,
                        // OK button text.
                        null,
                        // OK button action.
                        () -> {
                        },
                        // Cancel button action.
                        null,
                        // Neutral button text.
                        null,
                        // Neutral button action.
                        OAuth2Requester::revokeToken,
                        // Dismiss dialog when onNeutralClick.
                        true
                ).first.show();
            });
        });
    }
}