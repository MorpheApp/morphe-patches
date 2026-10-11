/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.content.Context;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import java.util.List;

final class LyricsSearchUi {

    static AutoCompleteTextView createSearchInput(Context context, String hint) {
        AutoCompleteTextView input = new AutoCompleteTextView(context);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setThreshold(1);
        input.setTextSize(16);
        input.setTextColor(ThemeUtils.getAppForegroundColor());

        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        background.getPaint().setColor(ThemeUtils.getEditTextBackground());
        input.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        input.setBackground(background);
        input.setClipToOutline(true);

        input.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = v.getWidth();
            if (width > 0) {
                input.setDropDownWidth(width);
            }
        });

        return input;
    }

    private static final float SUGGESTION_TEXT_SIZE_SP = 14;

    private static TextView createSuggestionRow(Context context) {
        TextView row = new TextView(context);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, SUGGESTION_TEXT_SIZE_SP);
        row.setTextColor(ThemeUtils.getAppForegroundColor());
        row.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        row.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static final class SuggestionAdapter extends ArrayAdapter<String> {
        private final AutoCompleteTextView input;

        SuggestionAdapter(Context context, List<String> suggestions, AutoCompleteTextView input) {
            super(context, 0, suggestions);
            this.input = input;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            TextView row = convertView instanceof TextView
                    ? (TextView) convertView : createSuggestionRow(getContext());
            int width = input.getWidth();
            if (width > 0) {
                row.setMaxWidth(width);
            }
            row.setText(getItem(position));
            return row;
        }

        @Override
        public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
            return getView(position, convertView, parent);
        }
    }

    static void setSuggestionAdapter(Context context, AutoCompleteTextView input,
                                             List<String> suggestions) {
        input.setAdapter(new SuggestionAdapter(context, suggestions, input));
    }

}
