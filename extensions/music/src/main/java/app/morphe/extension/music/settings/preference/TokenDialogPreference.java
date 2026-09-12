/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.net.Uri;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.music.patches.lyrics.requests.AppleMusicProvider;
import app.morphe.extension.music.patches.lyrics.requests.CaptionsFetcher;
import app.morphe.extension.music.patches.lyrics.requests.DeezerProvider;
import app.morphe.extension.music.patches.lyrics.requests.MusixmatchProvider;
import app.morphe.extension.music.patches.lyrics.requests.SpotifyProvider;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings({"unused", "deprecation"})
public class TokenDialogPreference extends Preference {

    public interface TokenValidator {
        boolean validate(String token);
    }

    private static final TokenValidator APPLE_VALIDATOR =
            token -> AppleMusicProvider.validateToken(token);

    private final String titleRes;
    private final String instructionRes;
    private final String hintRes;
    private final String toastSavedRes;
    private final String toastClearedRes;
    private final String toastInvalidRes;
    private final StringSetting setting;
    private final String getTokenUrl;
    private final String getTokenUrlLogTag;
    private final boolean multiline;
    private final TokenValidator validator;

    private TokenDialogPreference(Context context, String titleRes, String instructionRes,
            String hintRes, String toastSavedRes, String toastClearedRes, String toastInvalidRes,
            StringSetting setting, String getTokenUrl, String getTokenUrlLogTag,
            boolean multiline, TokenValidator validator) {
        super(context);
        this.titleRes = titleRes;
        this.instructionRes = instructionRes;
        this.hintRes = hintRes;
        this.toastSavedRes = toastSavedRes;
        this.toastClearedRes = toastClearedRes;
        this.toastInvalidRes = toastInvalidRes;
        this.setting = setting;
        this.getTokenUrl = getTokenUrl;
        this.getTokenUrlLogTag = getTokenUrlLogTag;
        this.multiline = multiline;
        this.validator = validator;
        setSelectable(true);
        setPersistent(false);
    }

    // --- Factory methods ---

    public static TokenDialogPreference apple(Context context) {
        return new TokenDialogPreference(context,
                "morphe_music_apple_music_token_title",
                "morphe_music_apple_music_token_dialog_instruction",
                "morphe_music_apple_music_token_dialog_hint",
                "morphe_music_apple_music_token_toast_saved",
                "morphe_music_apple_music_token_toast_cleared",
                "morphe_music_apple_music_token_toast_invalid",
                Settings.APPLE_MUSIC_TOKEN,
                "https://music.apple.com",
                "AppleMusicTokenPreference",
                false,
                APPLE_VALIDATOR);
    }

    public static TokenDialogPreference spotify(Context context) {
        return new TokenDialogPreference(context,
                "morphe_music_spotify_token_title",
                "morphe_music_spotify_token_dialog_instruction",
                "morphe_music_spotify_token_dialog_hint",
                "morphe_music_spotify_token_toast_saved",
                "morphe_music_spotify_token_toast_cleared",
                "morphe_music_spotify_token_toast_invalid",
                Settings.SPOTIFY_TOKEN,
                "https://open.spotify.com",
                "SpotifyTokenPreference",
                false,
                SpotifyProvider::validateToken);
    }

    public static TokenDialogPreference youtube(Context context) {
        return new TokenDialogPreference(context,
                "morphe_music_youtube_cookies_title",
                "morphe_music_youtube_cookies_dialog_instruction",
                "morphe_music_youtube_cookies_dialog_hint",
                "morphe_music_youtube_cookies_toast_saved",
                "morphe_music_youtube_cookies_toast_cleared",
                "morphe_music_youtube_cookies_toast_invalid",
                Settings.LYRICS_CAPTION_COOKIES,
                "https://youtube.com",
                "YouTubeCookiesPreference",
                true,
                CaptionsFetcher::validateYouTubeCookies);
    }

    public static TokenDialogPreference deezer(Context context) {
        return new TokenDialogPreference(context,
                "morphe_music_deezer_arl_title",
                "morphe_music_deezer_arl_dialog_instruction",
                "morphe_music_deezer_arl_dialog_hint",
                "morphe_music_deezer_arl_toast_saved",
                "morphe_music_deezer_arl_toast_cleared",
                "morphe_music_deezer_arl_toast_invalid",
                Settings.DEEZER_ARL,
                "https://www.deezer.com",
                "DeezerArlPreference",
                false,
                DeezerProvider::validateArl);
    }

    public static TokenDialogPreference musixmatch(Context context) {
        return new TokenDialogPreference(context,
                "morphe_music_musixmatch_token_title",
                "morphe_music_musixmatch_token_dialog_instruction",
                "morphe_music_musixmatch_token_dialog_hint",
                "morphe_music_musixmatch_token_toast_saved",
                "morphe_music_musixmatch_token_toast_cleared",
                "morphe_music_musixmatch_token_toast_invalid",
                Settings.MUSIXMATCH_TOKEN,
                "https://www.musixmatch.com",
                "MusixmatchTokenPreference",
                false,
                MusixmatchProvider::validateToken);
    }

    @Override
    protected void onClick() {
        showDialog(null);
    }

    public void showDialog(Runnable onDismissed) {
        Context context = getContext();
        final boolean configured = !setting.get().isBlank();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView instruction = new TextView(context);
        instruction.setText(str(instructionRes));
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        instruction.setTextColor(ThemeUtils.getAppForegroundColor());
        LinearLayout.LayoutParams instructionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        instructionParams.bottomMargin = Dim.dp12;
        content.addView(instruction, instructionParams);

        EditText tokenInput = createThemedEditText(context);
        tokenInput.setHint(str(hintRes));
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (multiline) {
            tokenInput.setSingleLine(false);
            tokenInput.setMinLines(3);
        }
        if (configured) {
            String currentToken = setting.get();
            tokenInput.setText(currentToken);
            tokenInput.setSelection(currentToken.length());
        }
        content.addView(tokenInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(ThemeUtils.getAppForegroundColor());
        status.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = Dim.dp12;

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str(titleRes),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    String token = tokenInput.getText().toString().trim();
                    if (token.isEmpty()) {
                        setting.resetToDefault();
                        Utils.showToastShort(str(toastClearedRes));
                        if (onDismissed != null) onDismissed.run();
                    } else if (validator != null) {
                        Utils.runOnBackgroundThread(() -> {
                            boolean valid = validator.validate(token);
                            Utils.runOnMainThread(() -> {
                                if (valid) {
                                    setting.save(token);
                                    Utils.showToastShort(str(toastSavedRes));
                                } else {
                                    Utils.showToastShort(str(toastInvalidRes));
                                }
                                if (onDismissed != null) onDismissed.run();
                            });
                        });
                    } else {
                        setting.save(token);
                        Utils.showToastShort(str(toastSavedRes));
                        if (onDismissed != null) onDismissed.run();
                    }
                },
                null,
                str("morphe_music_scrobbling_log_out"),
                configured ? () -> {
                    setting.resetToDefault();
                    Utils.showToastShort(str(toastClearedRes));
                    if (onDismissed != null) onDismissed.run();
                } : null,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        if (getTokenUrl != null) {
            Button getTokenBtn = CustomDialog.createButton(context, null,
                    str("morphe_music_token_dialog_get_token"),
                    () -> {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(getTokenUrl));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(intent);
                        } catch (Exception ignored) {
                        }
                    },
                    false, false);

            LinearLayout.LayoutParams getTokenParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
            getTokenParams.topMargin = Dim.dp12;
            content.addView(getTokenBtn, getTokenParams);
        }

        content.addView(status, statusParams);

        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.show();
    }

    private static EditText createThemedEditText(Context context) {
        EditText editText = new EditText(context);
        editText.setSingleLine(true);
        editText.setTextSize(16);
        editText.setTextColor(ThemeUtils.getAppForegroundColor());
        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        background.getPaint().setColor(ThemeUtils.getEditTextBackground());
        editText.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        editText.setBackground(background);
        editText.setClipToOutline(true);
        return editText;
    }
}
