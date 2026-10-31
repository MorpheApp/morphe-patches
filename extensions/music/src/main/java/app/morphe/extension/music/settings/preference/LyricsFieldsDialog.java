/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.util.Pair;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/** A dialog that edits several lyrics settings together, with save and reset buttons. */
final class LyricsFieldsDialog {

    private final Context context;
    private final LinearLayout content;

    LyricsFieldsDialog(Context context) {
        this.context = context;
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
    }

    void addLabel(String key) {
        TextView label = new TextView(context);
        label.setText(str(key));
        label.setTextColor(ThemeUtils.getAppForegroundColor());
        label.setTextSize(14);
        label.setPadding(0, Dim.dp12, 0, Dim.dp8);
        content.addView(label);
    }

    /** A field label, such as the format hint, in the dimmed note style the dictionaries use. */
    void addNoteLabel(String key) {
        TextView label = new TextView(context);
        label.setText(str(key));
        label.setTextColor(withAlpha(ThemeUtils.getAppForegroundColor(), 0x99));
        label.setTextSize(14);
        label.setPadding(0, Dim.dp12, 0, Dim.dp8);
        content.addView(label);
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    EditText addInput(String labelKey, String value, int inputType) {
        addLabel(labelKey);
        EditText input = ThemedInput.create(context);
        input.setInputType(inputType);
        input.setText(value);
        content.addView(input);
        return input;
    }

    EditText addNoteInput(String labelKey, String value, int inputType) {
        addNoteLabel(labelKey);
        EditText input = ThemedInput.create(context);
        input.setInputType(inputType);
        input.setText(value);
        content.addView(input);
        return input;
    }

    EditText addNumberInput(String labelKey, Number value, boolean decimal) {
        return addInput(labelKey, value.toString(), InputType.TYPE_CLASS_NUMBER
                | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
    }

    void show(String titleKey, Runnable onSave, Runnable onReset) {
        Pair<Dialog, LinearLayout> pair = CustomDialog.create(context,
                str(titleKey), null, null,
                str("morphe_settings_save"), onSave,
                () -> {},
                str("morphe_settings_reset"), onReset,
                true);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        pair.second.addView(scroll, pair.second.getChildCount() - 1,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        pair.first.show();
    }

    static String text(EditText input) {
        return input.getText().toString().trim();
    }
}
