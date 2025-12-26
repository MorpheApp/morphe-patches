package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.oauth2.requests.OAuth2Requester.isActivationCodeDataAvailable;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.oauth2.OAuth2Helper;
import app.morphe.extension.shared.oauth2.object.AccessTokenData;
import app.morphe.extension.shared.oauth2.object.ActivationCodeData;
import app.morphe.extension.shared.oauth2.requests.OAuth2Requester;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings({"FieldCanBeLocal", "deprecation", "unused"})
public class OAuth2Preference extends Preference implements Preference.OnPreferenceClickListener {

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

    @Override
    public boolean onPreferenceClick(Preference preference) {
        Context context = getContext();
        Pair<Dialog, LinearLayout> dialogPair;
        String dialogTitle = str("morphe_spoof_video_streams_sign_in_android_vr_dialog_title");
        String dialogMessage = str("morphe_spoof_video_streams_sign_in_android_vr_dialog_message");
        String resetButtonText = str("morphe_spoof_video_streams_sign_in_android_vr_dialog_reset_text");
        if (isActivationCodeDataAvailable()) {
            dialogPair = CustomDialog.create(
                    context,
                    // Title.
                    dialogTitle,
                    // Message.
                    dialogMessage,
                    // No EditText.
                    null,
                    // OK button text.
                    str("morphe_spoof_video_streams_sign_in_android_vr_dialog_get_authorization_token_text"),
                    // OK button action.
                    this::setRefreshToken,
                    // Cancel button action.
                    null,
                    // Neutral button text.
                    resetButtonText,
                    // Neutral button action.
                    OAuth2Helper::revokeToken,
                    // Dismiss dialog when onNeutralClick.
                    true
            );
        } else {
            dialogPair = CustomDialog.create(
                    context,
                    // Title.
                    dialogTitle,
                    // Message.
                    dialogMessage,
                    // No EditText.
                    null,
                    // OK button text.
                    str("morphe_spoof_video_streams_sign_in_android_vr_dialog_get_activation_code_text"),
                    // OK button action.
                    this::showActivationCodeDialog,
                    // Cancel button action.
                    null,
                    // Neutral button text.
                    resetButtonText,
                    // Neutral button action.
                    OAuth2Helper::revokeToken,
                    // Dismiss dialog when onNeutralClick.
                    true
            );
        }
        dialogPair.first.show();
        return true;
    }

    private void showActivationCodeDialog() {
        Utils.runOnBackgroundThread(() -> {
            ActivationCodeData activationCodeData = OAuth2Requester.getActivationCodeData();
            Utils.runOnMainThread(() -> {
                if (activationCodeData == null) {
                    Utils.showToastShort(str("morphe_spoof_video_streams_sign_in_android_vr_toast_get_activation_code_failed"));
                } else {
                    Context context = getContext();
                    String activationCode = activationCodeData.userCode;
                    String signInUrl = activationCodeData.verificationUrl;
                    String dialogTitle = str("morphe_spoof_video_streams_sign_in_android_vr_activation_code_dialog_title");
                    String dialogMessage = str("morphe_spoof_video_streams_sign_in_android_vr_activation_code_dialog_message", activationCode);
                    String okButtonText = str("morphe_spoof_video_streams_sign_in_android_vr_activation_code_dialog_open_website_text");
                    Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                            context,
                            // Title.
                            dialogTitle,
                            // Message.
                            dialogMessage,
                            // No EditText.
                            null,
                            // OK button text.
                            okButtonText,
                            // OK button action.
                            () -> {
                                Utils.setClipboard(activationCode);
                                Intent i = new Intent(Intent.ACTION_VIEW);
                                i.setData(Uri.parse(signInUrl));
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
                    );
                    dialogPair.first.show();
                }
            });
        });
    }

    private void setRefreshToken() {
        Utils.runOnBackgroundThread(() -> {
            AccessTokenData accessTokenData = OAuth2Requester.getRefreshTokenData();
            Utils.runOnMainThread(() -> {
                if (accessTokenData == null) {
                    Utils.showToastShort(str("morphe_spoof_video_streams_sign_in_android_vr_toast_get_authorization_code_failed"));
                } else {
                    String refreshToken = accessTokenData.refreshToken;
                    if (refreshToken != null && !refreshToken.isEmpty()) {
                        BaseSettings.OAUTH2_REFRESH_TOKEN.save(refreshToken);
                        OAuth2Helper.setAuthorization(accessTokenData);

                        Context context = getContext();
                        String dialogTitle =
                                str("morphe_spoof_video_streams_sign_in_android_vr_success_dialog_title");
                        String dialogMessage =
                                str("morphe_spoof_video_streams_sign_in_android_vr_success_dialog_message");

                        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                                context,
                                // Title.
                                dialogTitle,
                                // Message.
                                dialogMessage,
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
                                OAuth2Helper::revokeToken,
                                // Dismiss dialog when onNeutralClick.
                                true
                        );
                        dialogPair.first.show();
                    }
                }
            });
        });
    }
}