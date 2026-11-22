/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5200
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.util.Pair;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Asks for the words to search, such as for a search of the comments, a channel or a playlist.
 */
public final class SearchDialog {

    private SearchDialog() {
    }

    /**
     * Shows an empty search field, whose hint is the title.
     *
     * @param onSearch Called with the trimmed words to search, if any were entered.
     */
    public static void show(Context context, String title, String searchButtonText, Consumer<String> onSearch) {
        show(context, title, title, "", searchButtonText, text -> {
            String query = text.trim();
            if (!query.isEmpty()) {
                onSearch.accept(query);
            }
        }, null, null);
    }

    /**
     * @param text            Text the search field starts with, such as the words of the search that is shown.
     * @param onSearch        Called with the text of the search field as entered.
     * @param clearButtonText Text of a button that clears the search, or null for no such button.
     * @param onClear         Called when the search is cleared.
     */
    public static void show(Context context, String title, String hint, String text, String searchButtonText,
                            Consumer<String> onSearch,
                            @Nullable String clearButtonText, @Nullable Runnable onClear) {
        try {
            EditText editText = CustomDialog.createEditText(context);
            editText.setHint(hint);
            editText.setSingleLine(true);
            editText.setInputType(InputType.TYPE_CLASS_TEXT);
            editText.setText(text);
            editText.setSelection(text.length());

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    title,
                    null,
                    editText,
                    searchButtonText,
                    () -> onSearch.accept(editText.getText().toString()),
                    () -> {},
                    clearButtonText,
                    onClear,
                    true
            );
            Dialog dialog = dialogPair.first;
            Window window = dialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
            editText.requestFocus();
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "show failure", ex);
        }
    }
}
