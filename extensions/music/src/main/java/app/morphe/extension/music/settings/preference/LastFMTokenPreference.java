package app.morphe.extension.music.settings.preference;

import android.app.Dialog;
import android.content.Context;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.morphe.extension.music.patches.lastfm.LastFM;
import app.morphe.extension.music.patches.lastfm.LastFMTokenStore;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

public class LastFMTokenPreference extends Preference {

    public LastFMTokenPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LastFMTokenPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LastFMTokenPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LastFMTokenPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
        updateSummary();
    }

    private void updateSummary() {
        String username = LastFMTokenStore.retrieveUsername();
        String sessionKey = LastFMTokenStore.retrieveSessionKey();
        if (sessionKey == null || sessionKey.trim().isEmpty() || username == null || username.trim().isEmpty()) {
            setSummary("Not logged in. Tap to configure.");
        } else {
            setSummary("Logged in as " + username + ". Tap to manage account.");
        }
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    private void showDialog() {
        Context context = getContext();
        String currentSessionKey = LastFMTokenStore.retrieveSessionKey();
        String currentUsername = LastFMTokenStore.retrieveUsername();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp8);

        TextView instruction = new TextView(context);
        instruction.setText("Enter your Last.fm credentials to log in.");
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        content.addView(instruction);

        EditText usernameInput = new EditText(context);
        usernameInput.setHint("Username");
        if (currentUsername != null) {
            usernameInput.setText(currentUsername);
            usernameInput.setSelection(currentUsername.length());
        }
        LinearLayout.LayoutParams usernameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        usernameParams.topMargin = Dim.dp8;
        content.addView(usernameInput, usernameParams);

        EditText passwordInput = new EditText(context);
        passwordInput.setHint("Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        passwordParams.topMargin = Dim.dp8;
        content.addView(passwordInput, passwordParams);

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = Dim.dp8;
        content.addView(buttonRow, rowParams);

        Button loginBtn = new Button(context);
        loginBtn.setText("Log In");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonRow.addView(loginBtn, btnParams);

        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = Dim.dp8;
        content.addView(status, statusParams);

        loginBtn.setOnClickListener(v -> {
            String username = usernameInput.getText().toString().trim();
            String password = passwordInput.getText().toString();
            if (username.isEmpty() || password.isEmpty()) {
                status.setText("Please enter username and password");
                status.setTextColor(0xFFFF0000);
                return;
            }
            status.setText("Logging in...");
            status.setTextColor(0xFF888888);
            Utils.runOnBackgroundThread(() -> {
                try {
                    LastFM.Session session = LastFM.getMobileSession(username, password);
                    Utils.runOnMainThread(() -> {
                        if (session != null && session.key != null) {
                            status.setText("Logged in successfully!");
                            status.setTextColor(0xFF00FF00);
                            
                            // Automatically save the credentials
                            LastFMTokenStore.store(session.key, session.name);
                            updateSummary();
                            Toast.makeText(context, "Last.fm credentials saved!", Toast.LENGTH_SHORT).show();
                        } else {
                            status.setText("Failed to get session from Last.fm");
                            status.setTextColor(0xFFFF0000);
                        }
                    });
                } catch (Exception e) {
                    Utils.runOnMainThread(() -> {
                        status.setText("Login failed: " + e.getMessage());
                        status.setTextColor(0xFFFF0000);
                    });
                }
            });
        });

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                "Last.fm Account",
                null,
                null,
                "Close",
                () -> {},
                () -> {},
                currentSessionKey != null ? "Log Out" : null,
                () -> {
                    LastFMTokenStore.clear();
                    updateSummary();
                    Toast.makeText(context, "Logged out", Toast.LENGTH_SHORT).show();
                },
                true
        );

        dialogPair.second.addView(content, 1);
        dialogPair.first.show();
    }
}
