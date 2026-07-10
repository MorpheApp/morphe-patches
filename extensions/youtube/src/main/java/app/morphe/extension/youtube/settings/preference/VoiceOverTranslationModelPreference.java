/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation"})
public class VoiceOverTranslationModelPreference extends CustomDialogListPreference {

    private static final String CUSTOM_SENTINEL = "custom";

    private static final List<String> PRESET_IDS = List.of(
            "mistralai/mistral-nemo",
            "deepseek/deepseek-v4-flash",
            "google/gemma-4-26b-a4b-it"
    );

    private EditText editText;

    public VoiceOverTranslationModelPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        updateEntries();
    }

    public VoiceOverTranslationModelPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        updateEntries();
    }

    public VoiceOverTranslationModelPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        updateEntries();
    }

    public VoiceOverTranslationModelPreference(Context context) {
        super(context);
        updateEntries();
    }

    private static boolean isPreset(String modelId) {
        for (String preset : PRESET_IDS) {
            if (preset.equals(modelId)) return true;
        }
        return false;
    }

    private void updateEntries() {
        setEntries(new CharSequence[]{
                str("morphe_vot_openrouter_model_mistral_nemo"),
                str("morphe_vot_openrouter_model_deepseek_flash_v4"),
                str("morphe_vot_openrouter_model_gemma4_26b_a4b"),
                str("morphe_vot_openrouter_model_custom")
        });
        List<String> entryValues = new ArrayList<>(PRESET_IDS);
        entryValues.add(CUSTOM_SENTINEL);
        setEntryValues(entryValues.toArray(new String[0]));
    }

    @Override
    protected void showDialog(@Nullable Bundle state) {
        updateEntries();

        Context context = getContext();
        String currentModel = Settings.VOT_OPENROUTER_MODEL.get();
        final boolean isCustom = !isPreset(currentModel);

        final int fg = Utils.getAppForegroundColor();
        final int secondaryFg = Color.argb(153, Color.red(fg), Color.green(fg), Color.blue(fg));
        final int checkmarkRes = Utils.appIsUsingBoldIcons() ? DRAWABLE_CHECKMARK_BOLD : DRAWABLE_CHECKMARK;
        LayoutInflater inflater = LayoutInflater.from(context);

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout listLayout = new LinearLayout(context);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        listParams.bottomMargin = Dim.dp16;
        contentLayout.addView(listLayout, listParams);

        CharSequence[] entries = getEntries();
        CharSequence[] entryValues = getEntryValues();
        String[] currentSelection = {isCustom ? CUSTOM_SENTINEL : currentModel};
        List<Runnable> selectionUpdaters = new ArrayList<>();
        Runnable refreshChecks = () -> { for (Runnable u : selectionUpdaters) u.run(); };

        TextView[] customCostView = {null};

        for (int i = 0; i < entries.length; i++) {
            String value = entryValues[i].toString();
            final boolean isPresetEntry = !value.equals(CUSTOM_SENTINEL);

            View row = inflater.inflate(LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED, listLayout, false);

            ImageView check = row.findViewById(ID_MORPHE_CHECK_ICON);
            check.setImageResource(checkmarkRes);
            check.setColorFilter(fg);
            View checkPlaceholder = row.findViewById(ID_MORPHE_CHECK_ICON_PLACEHOLDER);

            final boolean initialSelected = value.equals(currentSelection[0]);
            check.setVisibility(initialSelected ? View.VISIBLE : View.GONE);
            checkPlaceholder.setVisibility(initialSelected ? View.GONE : View.VISIBLE);
            selectionUpdaters.add(() -> {
                final boolean selected = value.equals(currentSelection[0]);
                check.setVisibility(selected ? View.VISIBLE : View.GONE);
                checkPlaceholder.setVisibility(selected ? View.GONE : View.VISIBLE);
            });

            TextView itemText = row.findViewById(ID_MORPHE_ITEM_TEXT);
            itemText.setText(entries[i]);
            itemText.setTextColor(fg);

            LinearLayout.LayoutParams existingLp = (LinearLayout.LayoutParams) itemText.getLayoutParams();
            LinearLayout textContainer = new LinearLayout(context);
            textContainer.setOrientation(LinearLayout.VERTICAL);
            textContainer.setLayoutParams(existingLp);
            itemText.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout rowLayout = (LinearLayout) row;
            final int idx = rowLayout.indexOfChild(itemText);
            rowLayout.removeView(itemText);
            textContainer.addView(itemText);

            TextView costView = new TextView(context);
            costView.setTextColor(secondaryFg);
            costView.setTextSize(12);
            textContainer.addView(costView);
            rowLayout.addView(textContainer, idx);

            if (isPresetEntry) {
                VoiceOverTranslationPatch.fetchOpenRouterModelCost(value, cost ->
                        costView.setText(cost != null
                                ? VoiceOverTranslationPatch.formatOpenRouterCostPerHundredHours(cost)
                                : ""));
            } else {
                customCostView[0] = costView;
            }

            row.setOnClickListener(v -> {
                currentSelection[0] = value;
                if (value.equals(CUSTOM_SENTINEL)) {
                    String saved = Settings.VOT_OPENROUTER_MODEL.get();
                    editText.setText(isPreset(saved) ? "" : saved);
                    editText.setEnabled(true);
                    editText.requestFocus();
                } else {
                    editText.setText(value);
                    editText.setEnabled(false);
                }
                editText.setSelection(editText.getText().length());
                refreshChecks.run();
            });

            listLayout.addView(row);
        }

        // Custom cost: fetch immediately on open for the already-saved model, then on each edit
        // with debounce (see syncListSelection below).
        if (isCustom && !currentModel.isEmpty()) {
            VoiceOverTranslationPatch.fetchOpenRouterModelCost(currentModel,
                    cost -> customCostView[0].setText(cost != null
                            ? VoiceOverTranslationPatch.formatOpenRouterCostPerHundredHours(cost)
                            : ""));
        }

        //noinspection ExtractMethodRecommender
        final Handler costHandler = new Handler(Looper.getMainLooper());
        final Runnable[] pendingCostFetch = {null};

        Function<String, Void> syncListSelection = typedValue -> {
            currentSelection[0] = isPreset(typedValue) ? typedValue : CUSTOM_SENTINEL;
            refreshChecks.run();
            // Debounce so the OpenRouter /models endpoint is not hit on every keystroke.
            if (pendingCostFetch[0] != null) costHandler.removeCallbacks(pendingCostFetch[0]);
            if (!isPreset(typedValue) && !typedValue.isEmpty()) {
                customCostView[0].setText("");
                pendingCostFetch[0] = () -> VoiceOverTranslationPatch.fetchOpenRouterModelCost(
                        typedValue, cost -> customCostView[0].setText(cost != null
                                ? VoiceOverTranslationPatch.formatOpenRouterCostPerHundredHours(cost)
                                : "")
                );
                costHandler.postDelayed(pendingCostFetch[0], 800);
            } else {
                customCostView[0].setText("");
            }
            return null;
        };

        editText = createEditText(context, currentModel, isCustom, syncListSelection);
        contentLayout.addView(editText);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                getTitle() != null ? getTitle().toString() : "",
                null, null, null,
                () -> {
                    String newValue = editText.getText().toString().trim();
                    if (newValue.isEmpty()) return;
                    if (callChangeListener(newValue)) setValue(newValue);
                },
                () -> {},
                null, null, false
        );

        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dialogPair.second.addView(contentLayout, dialogPair.second.getChildCount() - 1, contentParams);
        dialogPair.first.show();
    }

    private EditText createEditText(Context context, String initialValue, boolean isCustom,
                                    Function<String, Void> textChangeCallback) {
        EditText editText = new EditText(context);
        editText.setText(initialValue);
        editText.setSelection(initialValue.length());
        editText.setHint(str("morphe_vot_openrouter_model_hint"));
        editText.setSingleLine(true);
        editText.setTextSize(16);
        editText.setEnabled(isCustom);

        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable edit) {
                textChangeCallback.apply(edit.toString().trim());
            }
        });

        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        background.getPaint().setColor(Utils.getEditTextBackground());
        editText.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);
        editText.setBackground(background);
        editText.setClipToOutline(true);

        return editText;
    }
}
