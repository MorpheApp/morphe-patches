package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.oauth2.requests.OAuth2Requester.isActivationCodeDataAvailable;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.oauth2.object.AccessTokenData;
import app.morphe.extension.shared.oauth2.object.ActivationCodeData;
import app.morphe.extension.shared.oauth2.requests.OAuth2Requester;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings("deprecation")
public abstract class OAuth2Preference extends Preference implements Preference.OnPreferenceClickListener {

    {
        setOnPreferenceClickListener(this);
    }

    /**
     * How many times to try to get a refresh token after the user returns to the app.
     */
    private static final int GET_REFRESH_TOKENS_MAX_ATTEMPTS = 5;

    /**
     * Interval of fetched {@link ActivationCodeData#interval}.
     */
    private int getTokenIntervalCheckMilliseconds;

    /**
     * How many get token attempts are left. Used to prevent re-trying if the user abandons
     * the website sign-in process.
     */
    private int getTokenAttemptsLeft;

    /**
     * The last time token auth was checked. Required to prevent "too_fast" errors
     * if re-checking too quickly.
     */
    private long lastGetTokenAttemptTime;

    /**
     * If a get token attempt is scheduled for the future.
     */
    private boolean getTokenAttemptScheduled;

    /**
     * Callback when the app is resumed. Used to automatically finish the client side sign in process.
     */
    private final Application.ActivityLifecycleCallbacks ACTIVITY_LIFECYCLE_CALLBACKS
            = new Application.ActivityLifecycleCallbacks() {

        public void onActivityResumed(@NonNull Activity activity) {
            // Check for auth approval when the app resumes.
            // This could be done in the background on a timer, but the Google update interval is
            // usually 5 seconds which means if the user returns to the app there could be up to 5-second
            // delay before showing the success dialog.
            //
            // Instead, check when the app resumes with some logic to wait a few seconds if the
            // user is changing back and forth before auth is approved.
            Logger.printDebug(() -> "onActivityResumed");
            if (isActivationCodeDataAvailable()) {
                if (getTokenAttemptScheduled) {
                    return;
                }

                final long now = System.currentTimeMillis();
                if (System.currentTimeMillis() > (lastGetTokenAttemptTime + getTokenIntervalCheckMilliseconds)) {
                    lastGetTokenAttemptTime = now;
                    getRefreshToken();
                } else {
                    getTokenAttemptScheduled = true;
                    // Too soon to check again. Schedule a check in the future.
                    final long delayMillis = getTokenIntervalCheckMilliseconds - (now - lastGetTokenAttemptTime);
                    Logger.printDebug(() -> "Scheduling get token check in: " + delayMillis + "ms");
                    Utils.runOnMainThreadDelayed(() -> {
                        lastGetTokenAttemptTime = System.currentTimeMillis();
                        getTokenAttemptScheduled = false;
                        getRefreshToken();
                    }, delayMillis);
                }
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
        SpoofVideoStreamsPatch.getApplication().registerActivityLifecycleCallbacks(
                ACTIVITY_LIFECYCLE_CALLBACKS
        );
    }

    private void unregisterApplicationOnResumeCallback() {
        SpoofVideoStreamsPatch.getApplication().unregisterActivityLifecycleCallbacks(
                ACTIVITY_LIFECYCLE_CALLBACKS
        );
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        // Remove just in case the user never finished signing in.
        unregisterApplicationOnResumeCallback();
    }

    protected boolean isRefreshTokenSaved() {
        return !SharedYouTubeSettings.OAUTH2_REFRESH_TOKEN.get().isEmpty();
    }

    protected void updateUI(boolean currentlySignedIn) {
        final boolean isSettingEnabled = isSettingEnabled();
        setEnabled(isSettingEnabled);

        String summaryKey;
        if (isSettingEnabled) {
            summaryKey = currentlySignedIn
                    ? "morphe_spoof_video_streams_sign_in_android_vr_about_summary_signed_in"
                    : "morphe_spoof_video_streams_sign_in_android_vr_about_summary";
        } else {
            summaryKey = "morphe_spoof_video_streams_sign_in_android_vr_about_summary_disabled";
        }
        setSummary(str(summaryKey));
    }

    protected void updateUI() {
        updateUI(isRefreshTokenSaved());
    }

    public OAuth2Preference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        updateUI();
    }

    public OAuth2Preference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        updateUI();
    }

    public OAuth2Preference(Context context, AttributeSet attrs) {
        super(context, attrs);
        updateUI();
    }

    public OAuth2Preference(Context context) {
        super(context);
        updateUI();
    }

    /**
     * @return If the app spoof settings are configured in a way to allow using VR sign in.
     */
    protected abstract boolean isSettingEnabled();

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String titleKey;
        String messageKey;
        String okButtonTextKey;
        Runnable okButtonRunnable;

        if (isRefreshTokenSaved()) {
            titleKey = "morphe_spoof_video_streams_sign_in_android_vr_about_summary_signed_in";
            messageKey = "morphe_spoof_video_streams_sign_in_android_vr_success_dialog_message";
            okButtonTextKey = "morphe_spoof_video_streams_sign_in_android_vr_dialog_reset";
            okButtonRunnable = () -> {
                OAuth2Requester.revokeToken(SharedYouTubeSettings.OAUTH2_REFRESH_TOKEN.get());
                // Don't wait for revoke to finish and clear the token now so UI is up to date.
                SharedYouTubeSettings.OAUTH2_REFRESH_TOKEN.resetToDefault();
                updateUI(false);
            };
        } else {
            titleKey = "morphe_spoof_video_streams_sign_in_android_vr_dialog_title";
            messageKey = "morphe_spoof_video_streams_sign_in_android_vr_dialog_not_signed_in_message";
            okButtonTextKey = "morphe_spoof_video_streams_sign_in_android_vr_dialog_continue";
            okButtonRunnable = this::showActivationCodeDialog;
        }

        CustomDialog.create(
                getContext(),
                // Title.
                str(titleKey),
                // Message.
                BulletPointPreference.formatIntoBulletPoints(str(messageKey)),
                // No EditText.
                null,
                // OK button text.
                str(okButtonTextKey),
                // OK button action.
                okButtonRunnable,
                // Cancel button action.
                () -> {

                },
                // Neutral button text.
                null,
                // Neutral button action.
                null,
                // Dismiss dialog when onNeutralClick.
                true
        ).first.show();

        return true;
    }

    private void showActivationCodeDialog() {
        getTokenAttemptsLeft = GET_REFRESH_TOKENS_MAX_ATTEMPTS;
        Context context = getContext();

        Utils.runOnBackgroundThread(() -> {
            ActivationCodeData activationCodeData = OAuth2Requester.getActivationCodeData();
            if (activationCodeData == null) {
                Utils.showToastLong(str("morphe_spoof_video_streams_sign_in_android_vr_toast_get_activation_code_failed"));
                return;
            }

            Utils.runOnMainThread(() -> {
                String userCode = activationCodeData.userCode;
                String verificationUrl = activationCodeData.verificationUrl;
                getTokenIntervalCheckMilliseconds = 1000 * activationCodeData.interval;

                CustomDialog.create(
                        context,
                        // Title.
                        str("morphe_spoof_video_streams_sign_in_android_vr_activation_code_dialog_title"),
                        // Message.
                        str("morphe_spoof_video_streams_sign_in_android_vr_activation_code_dialog_message", userCode),
                        // No EditText.
                        null,
                        // OK button text.
                        str("morphe_spoof_video_streams_sign_in_android_vr_activation_code_dialog_open_website"),
                        // OK button action.
                        () -> {
                            // Automatically fetch the auth token after the user returns.
                            registerApplicationOnResumeCallback();

                            Utils.setClipboard(userCode);
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setData(Uri.parse(verificationUrl));
                            context.startActivity(i);
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
        final boolean reachedMaxGetTokenAttempts = --getTokenAttemptsLeft <= 0;
        if (reachedMaxGetTokenAttempts) {
            unregisterApplicationOnResumeCallback();
        }

        Context context = getContext();

        Utils.runOnBackgroundThread(() -> {
            AccessTokenData accessTokenData = OAuth2Requester.getRefreshTokenData(reachedMaxGetTokenAttempts);

            Utils.runOnMainThread(() -> {
                if (accessTokenData == null) {
                    Logger.printDebug(() -> "No refresh token found");
                    if (reachedMaxGetTokenAttempts) {
                        Utils.showToastLong(str("morphe_spoof_video_streams_sign_in_android_vr_toast_get_authorization_code_failed"));
                    }
                    return;
                }
                String refreshToken = accessTokenData.refreshToken;
                if (refreshToken == null || refreshToken.isEmpty()) {
                    Logger.printException(() -> "No refresh token found");
                    return;
                }

                unregisterApplicationOnResumeCallback();

                SharedYouTubeSettings.OAUTH2_REFRESH_TOKEN.save(refreshToken);
                OAuth2Requester.setAuthorization(accessTokenData);

                updateUI(true);

                CustomDialog.create(
                        context,
                        // Title.
                        str("morphe_spoof_video_streams_sign_in_android_vr_success_dialog_title"),
                        // Message.
                        BulletPointPreference.formatIntoBulletPoints(
                                str("morphe_spoof_video_streams_sign_in_android_vr_success_dialog_message")
                        ),
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
                        null,
                        // Dismiss dialog when onNeutralClick.
                        true
                ).first.show();
            });
        });
    }
}