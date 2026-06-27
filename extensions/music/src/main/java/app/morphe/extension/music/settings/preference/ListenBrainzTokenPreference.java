package app.morphe.extension.music.settings.preference;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.morphe.extension.music.patches.listenbrainz.ListenBrainz;
import app.morphe.extension.music.patches.listenbrainz.ListenBrainzTokenStore;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

public class ListenBrainzTokenPreference extends Preference {

    public ListenBrainzTokenPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public ListenBrainzTokenPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public ListenBrainzTokenPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ListenBrainzTokenPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
        updateSummary();
    }

    private void updateSummary() {
        String token = ListenBrainzTokenStore.retrieve();
        if (token == null || token.trim().isEmpty()) {
            setSummary("Not logged in. Tap to configure.");
        } else {
            setSummary("Logged in. Tap to manage account.");
        }
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    private void showDialog() {
        Context context = getContext();
        String currentToken = ListenBrainzTokenStore.retrieve();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp8);

        TextView instruction = new TextView(context);
        instruction.setText("Enter your ListenBrainz User Token to enable scrobbling.");
        instruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        content.addView(instruction);

        EditText tokenInput = new EditText(context);
        tokenInput.setHint("Token");
        if (currentToken != null) {
            tokenInput.setText(currentToken);
            tokenInput.setSelection(currentToken.length());
        }
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = Dim.dp8;
        content.addView(tokenInput, inputParams);

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = Dim.dp8;
        content.addView(buttonRow, rowParams);

        Button getBtn = new Button(context);
        getBtn.setText("Get Token");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        btnParams.rightMargin = Dim.dp8;
        buttonRow.addView(getBtn, btnParams);

        Button verifyBtn = new Button(context);
        verifyBtn.setText("Verify");
        LinearLayout.LayoutParams verifyParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        buttonRow.addView(verifyBtn, verifyParams);

        getBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://listenbrainz.org/profile/"));
                context.startActivity(intent);
            } catch (Exception e) {
                Logger.printException(() -> "ListenBrainzTokenPreference failed to open browser", e);
            }
        });

        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = Dim.dp8;
        content.addView(status, statusParams);

        verifyBtn.setOnClickListener(v -> {
            String token = tokenInput.getText().toString().trim();
            if (token.isEmpty()) {
                status.setText("Please enter a token");
                status.setTextColor(0xFFFF0000);
                return;
            }
            status.setText("Validating...");
            status.setTextColor(0xFF888888);
            Utils.runOnBackgroundThread(() -> {
                try {
                    ListenBrainz.TokenValidation validation = ListenBrainz.validateToken(token);
                    Utils.runOnMainThread(() -> {
                        if (validation.valid) {
                            status.setText("Valid token! User: " + validation.userName);
                            status.setTextColor(0xFF00FF00);
                        } else {
                            status.setText("Invalid token: " + (validation.message != null ? validation.message : "unknown"));
                            status.setTextColor(0xFFFF0000);
                        }
                    });
                } catch (Exception e) {
                    Utils.runOnMainThread(() -> {
                        status.setText("Verification failed: " + e.getMessage());
                        status.setTextColor(0xFFFF0000);
                    });
                }
            });
        });

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                "ListenBrainz Account",
                null,
                null,
                "Save",
                () -> {
                    String token = tokenInput.getText().toString().trim();
                    if (token.isEmpty()) {
                        ListenBrainzTokenStore.clear();
                        updateSummary();
                        Toast.makeText(context, "Token cleared", Toast.LENGTH_SHORT).show();
                    } else {
                        ListenBrainzTokenStore.store(token);
                        updateSummary();
                        Toast.makeText(context, "Token saved", Toast.LENGTH_SHORT).show();
                        Utils.runOnBackgroundThread(() -> {
                            try {
                                ListenBrainz.TokenValidation validation = ListenBrainz.validateToken(token);
                                if (!validation.valid) {
                                    Utils.runOnMainThread(() -> {
                                        Toast.makeText(context, "Warning: Saved token is invalid!", Toast.LENGTH_LONG).show();
                                    });
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                },
                () -> {},
                currentToken != null ? "Log Out" : null,
                () -> {
                    ListenBrainzTokenStore.clear();
                    updateSummary();
                    Toast.makeText(context, "Logged out", Toast.LENGTH_SHORT).show();
                },
                true
        );

        dialogPair.second.addView(content, 1);
        dialogPair.first.show();
    }
}
