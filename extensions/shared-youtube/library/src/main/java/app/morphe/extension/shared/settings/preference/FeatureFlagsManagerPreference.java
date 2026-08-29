/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.preference.Preference;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.Checkable;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.EnableDebuggingPatch;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * A custom preference that opens a dialog for managing feature flags.
 * Flags can be blocked, forced on, exported and imported, and the flag that causes
 * an app behavior can be found with a binary search.
 */
@SuppressWarnings({"deprecation", "unused"})
public class FeatureFlagsManagerPreference extends Preference {

    private static final int DRAWABLE_MORPHE_SETTINGS_SELECT_ALL =
            ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, "morphe_settings_select_all");
    private static final int DRAWABLE_MORPHE_SETTINGS_DESELECT_ALL =
            ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, "morphe_settings_deselect_all");
    private static final int DRAWABLE_MORPHE_SETTINGS_COPY_ALL =
            ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, "morphe_settings_copy_all");
    private static final int DRAWABLE_MORPHE_SETTINGS_IMPORT_EXPORT =
            ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, "morphe_settings_import_export");
    private static final int DRAWABLE_MORPHE_SETTINGS_BISECT =
            ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, "morphe_settings_bisect");
    private static final int DRAWABLE_MORPHE_SETTINGS_SEARCH =
            ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, "morphe_settings_search_icon_bold");
    private static final int DRAWABLE_MORPHE_SETTINGS_SEARCH_REMOVE =
            ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, "morphe_settings_search_remove_bold");

    /**
     * Flags to hide from the UI.
     */
    private static final Set<Long> FLAGS_TO_IGNORE = Set.of(
            45386834L, // 'You' tab settings icon.
            45532100L  // Cairo flag. Turning this off with all other flags causes the settings menu to be a mix of old/new.
    );

    /**
     * What the app does with a flag.
     */
    private enum FlagState {
        /** The app decides. */
        AUTO,
        /** Always off. */
        BLOCKED,
        /** Always on. */
        FORCED
    }

    /**
     * Which flags the list shows.
     */
    private enum FlagFilter {
        ACTIVE("morphe_debug_feature_flags_manager_filter_active"),
        INACTIVE("morphe_debug_feature_flags_manager_filter_inactive"),
        BLOCKED("morphe_debug_feature_flags_manager_filter_blocked"),
        FORCED("morphe_debug_feature_flags_manager_filter_forced");

        final String stringKey;

        FlagFilter(String stringKey) {
            this.stringKey = stringKey;
        }

        boolean matches(FlagState state, @Nullable Boolean loggedValue) {
            return switch (this) {
                case ACTIVE -> state == FlagState.AUTO && Boolean.TRUE.equals(loggedValue);
                case INACTIVE -> state == FlagState.AUTO && !Boolean.TRUE.equals(loggedValue);
                case BLOCKED -> state == FlagState.BLOCKED;
                case FORCED -> state == FlagState.FORCED;
            };
        }
    }

    /**
     * Tracks state for range selection in ListView.
     */
    private static class ListViewSelectionState {
        int lastClickedPosition = -1; // Position of the last clicked item.
        boolean isRangeSelecting = false; // True while a range is being selected.
    }

    /**
     * All flags shown in the dialog and what the app does with each of them.
     */
    private final TreeMap<Long, FlagState> flagStates = new TreeMap<>();

    /**
     * The last logged state of each flag.
     */
    private final Map<Long, Boolean> loggedFlagStates = new HashMap<>();

    private final Map<FlagFilter, TextView> chips = new LinkedHashMap<>();

    private FlagFilter filter = FlagFilter.ACTIVE;

    private FlagAdapter adapter;

    private ListView listView;

    private EditText searchBox;

    private LinearLayout actionButtons;

    private LinearLayout selectionButtons;

    private ImageButton deselectButton;

    {
        setOnPreferenceClickListener(pref -> {
            showFlagsManagerDialog();
            return true;
        });
    }

    public FeatureFlagsManagerPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public FeatureFlagsManagerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public FeatureFlagsManagerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FeatureFlagsManagerPreference(Context context) {
        super(context);
    }

    /**
     * Shows the main dialog for managing feature flags.
     */
    private void showFlagsManagerDialog() {
        if (!BaseSettings.DEBUG.get()) {
            Utils.showToastShort(str("morphe_debug_logs_disabled"));
            return;
        }

        Context context = getContext();

        // A search in progress replaces the manager until it is finished or canceled.
        FeatureFlagsBisect bisect = FeatureFlagsBisect.isActive() ? FeatureFlagsBisect.load() : null;
        if (bisect != null) {
            showBisectDialog(context, bisect);
            return;
        }

        if (!loadFlagStates()) {
            // It's impossible to reach the settings menu without reaching at least one flag.
            // So if there's no flags, then that means the user has just enabled debugging
            // but has not restarted the app yet.
            Utils.showToastShort(str("morphe_debug_feature_flags_manager_toast_no_flags"));
            return;
        }

        filter = FlagFilter.ACTIVE;
        chips.clear();

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                getTitle() != null ? getTitle().toString() : "",
                null,
                null,
                str("morphe_settings_save"),
                this::saveFlags,
                () -> {},
                str("morphe_settings_reset"),
                this::resetFlags,
                true
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);

        // Insert content before the dialog button row.
        View contentView = createContentView(context, dialog);
        mainLayout.addView(contentView, mainLayout.getChildCount() - 1, contentParams);

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);

            // Keep the buttons reachable while the keyboard is open, and do not
            // open the keyboard until the search box is tapped.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /**
     * Loads the state of every flag from the app log and the saved settings.
     *
     * @return True if at least one flag was detected by the app.
     */
    private boolean loadFlagStates() {
        Map<Long, Boolean> loggedFlags = EnableDebuggingPatch.getAllLoggedFlags();
        loggedFlagStates.clear();
        loggedFlagStates.putAll(loggedFlags);

        Set<Long> blockedFlags = EnableDebuggingPatch.parseFlags(
                SharedYouTubeSettings.DISABLED_FEATURE_FLAGS.get());
        Set<Long> forcedFlags = EnableDebuggingPatch.parseFlags(
                SharedYouTubeSettings.FORCED_FEATURE_FLAGS.get());

        flagStates.clear();
        // 1. Add logged flags.
        for (Long flag : loggedFlags.keySet()) {
            if (FLAGS_TO_IGNORE.contains(flag)) continue;
            flagStates.put(flag, blockedFlags.contains(flag)
                    ? FlagState.BLOCKED
                    : forcedFlags.contains(flag)
                    ? FlagState.FORCED
                    : FlagState.AUTO);
        }

        // 2. Add overrides that haven't been logged yet.
        // This ensures they stay in the list so the user can see/remove them.
        for (Long flag : blockedFlags) {
            if (FLAGS_TO_IGNORE.contains(flag)) continue;
            flagStates.putIfAbsent(flag, FlagState.BLOCKED);
        }
        for (Long flag : forcedFlags) {
            if (FLAGS_TO_IGNORE.contains(flag)) continue;
            flagStates.putIfAbsent(flag, FlagState.FORCED);
        }

        return !loggedFlags.isEmpty();
    }

    /**
     * Creates the dialog content: search box, filter chips, flag list and the bottom buttons.
     */
    private View createContentView(Context context, Dialog dialog) {
        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);

        adapter = new FlagAdapter(context);
        listView = createListView(context);

        searchBox = createSearchBox(context);
        contentLayout.addView(searchBox);
        contentLayout.addView(createFilterChips(context));
        contentLayout.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        contentLayout.addView(createBottomBar(context, dialog));

        adapter.refresh();
        updateChips();
        updateBottomBar();

        // Take the initial focus so the search box does not open the keyboard.
        contentLayout.setFocusableInTouchMode(true);
        contentLayout.requestFocus();

        return contentLayout;
    }

    /**
     * Creates the search box that filters the list.
     */
    @SuppressLint("ClickableViewAccessibility")
    private EditText createSearchBox(Context context) {
        EditText search = new EditText(context);
        search.setInputType(InputType.TYPE_CLASS_NUMBER);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setHint(str("morphe_debug_feature_flags_manager_search_hint"));
        search.setTextColor(ThemeUtils.getAppForegroundColor());
        search.setHapticFeedbackEnabled(false);
        search.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        search.setCompoundDrawablePadding(Dim.dp8);

        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(20), null, null));
        background.getPaint().setColor(ThemeUtils.getEditTextBackground());
        search.setBackground(background);

        Drawable searchIcon = createSearchBoxIcon(context, DRAWABLE_MORPHE_SETTINGS_SEARCH);
        Drawable clearIcon = createSearchBoxIcon(context, DRAWABLE_MORPHE_SETTINGS_SEARCH_REMOVE);
        search.setCompoundDrawables(searchIcon, null, null, null);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.setSearchQuery(s.toString());
                listView.clearChoices();
                updateChips();
                updateBottomBar();
                search.setCompoundDrawables(searchIcon, null,
                        TextUtils.isEmpty(s) ? null : clearIcon, null);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        search.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                Drawable[] compoundDrawables = search.getCompoundDrawables();
                if (compoundDrawables[2] != null &&
                        event.getRawX() >= (search.getRight() - compoundDrawables[2].getBounds().width())) {
                    search.setText("");
                    return true;
                }
            }
            return false;
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, Dim.dp8);
        search.setLayoutParams(params);

        return search;
    }

    private View createFilterChips(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        for (FlagFilter flagFilter : FlagFilter.values()) {
            TextView chip = createChip(context, flagFilter);
            chips.put(flagFilter, chip);
            row.addView(chip);
        }

        // Chips can be wider than the dialog once the counts are translated.
        HorizontalScrollView scrollView = new HorizontalScrollView(context);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // Fade the chip that is cut off, otherwise nothing shows the row can be scrolled.
        scrollView.setHorizontalFadingEdgeEnabled(true);
        scrollView.setFadingEdgeLength(Dim.dp24);
        scrollView.addView(row);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, Dim.dp8);
        scrollView.setLayoutParams(params);

        return scrollView;
    }

    private TextView createChip(Context context, FlagFilter chipFilter) {
        TextView chip = new TextView(context);
        chip.setTextSize(13);
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Dim.dp12, Dim.dp6, Dim.dp12, Dim.dp6);
        chip.setOnClickListener(v -> {
            filter = chipFilter;
            listView.clearChoices();
            adapter.refresh();
            updateChips();
            updateBottomBar();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, Dim.dp8, 0);
        chip.setLayoutParams(params);

        return chip;
    }

    /**
     * Updates the chip text and highlights the selected chip.
     */
    private void updateChips() {
        for (Map.Entry<FlagFilter, TextView> entry : chips.entrySet()) {
            FlagFilter chipFilter = entry.getKey();
            TextView chip = entry.getValue();

            int flagCount = 0;
            for (Map.Entry<Long, FlagState> flagEntry : flagStates.entrySet()) {
                if (chipFilter.matches(flagEntry.getValue(), loggedFlagStates.get(flagEntry.getKey()))) flagCount++;
            }

            final boolean selected = chipFilter == filter;
            chip.setText(str(chipFilter.stringKey, flagCount));
            chip.setTextColor(selected
                    ? (Utils.isDarkModeEnabled() ? Color.BLACK : Color.WHITE)
                    : ThemeUtils.getAppForegroundColor());

            ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                    Dim.roundedCorners(16), null, null));
            background.getPaint().setColor(selected
                    ? ThemeUtils.getOkButtonBackgroundColor()
                    : ThemeUtils.getEditTextBackground());
            chip.setBackground(background);
        }
    }

    /**
     * The icons use a theme attribute for their color, which is only resolved if the drawable
     * is loaded with a theme, and the app theme can differ from the dialog theme.
     */
    private Drawable createSearchBoxIcon(Context context, int drawableResId) {
        Drawable icon = context.getDrawable(drawableResId);
        //noinspection DataFlowIssue
        icon.setBounds(0, 0, Dim.dp20, Dim.dp20);
        icon.setTint(ThemeUtils.getAppForegroundColor());

        return icon;
    }

    /**
     * Creates the flag list with multi-select and range selection.
     */
    @SuppressLint("ClickableViewAccessibility")
    private ListView createListView(Context context) {
        ListView list = new ListView(context);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setDividerHeight(0);
        list.setAdapter(adapter);
        list.setPadding(0, Dim.dp4, 0, Dim.dp4);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);

        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        background.getPaint().setColor(ThemeUtils.getEditTextBackground());
        list.setBackground(background);

        final ListViewSelectionState state = new ListViewSelectionState();

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (!state.isRangeSelecting) {
                state.lastClickedPosition = position;
            } else {
                state.isRangeSelecting = false;
            }
            updateBottomBar();
        });

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (state.lastClickedPosition == -1) {
                list.setItemChecked(position, true);
                state.lastClickedPosition = position;
            } else {
                int start = Math.min(state.lastClickedPosition, position);
                int end = Math.max(state.lastClickedPosition, position);
                for (int i = start; i <= end; i++) {
                    list.setItemChecked(i, true);
                }
                state.isRangeSelecting = true;
            }
            updateBottomBar();
            return true;
        });

        list.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard(view);
            }
            if (event.getAction() == MotionEvent.ACTION_UP && state.isRangeSelecting) {
                state.isRangeSelecting = false;
            }
            return false;
        });

        return list;
    }

    private void hideKeyboard(View view) {
        InputMethodManager manager = (InputMethodManager)
                view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        if (searchBox != null) {
            searchBox.clearFocus();
        }
    }

    /**
     * Creates the bottom bar, which shows the state buttons while flags are selected
     * and the global actions otherwise.
     */
    private View createBottomBar(Context context, Dialog dialog) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);

        actionButtons = new LinearLayout(context);
        actionButtons.setOrientation(LinearLayout.HORIZONTAL);
        actionButtons.setGravity(Gravity.CENTER);
        actionButtons.addView(createIconButton(context, DRAWABLE_MORPHE_SETTINGS_SELECT_ALL, () -> {
            for (int i = 0, count = adapter.getCount(); i < count; i++) {
                listView.setItemChecked(i, true);
            }
            updateBottomBar();
        }));
        actionButtons.addView(createIconButton(context, DRAWABLE_MORPHE_SETTINGS_COPY_ALL, this::copyFlags));
        actionButtons.addView(createIconButton(context, DRAWABLE_MORPHE_SETTINGS_IMPORT_EXPORT,
                () -> showImportExportDialog(context)));
        actionButtons.addView(createIconButton(context, DRAWABLE_MORPHE_SETTINGS_BISECT,
                () -> showBisectStartDialog(context, dialog)));

        selectionButtons = new LinearLayout(context);
        selectionButtons.setOrientation(LinearLayout.HORIZONTAL);
        selectionButtons.setGravity(Gravity.CENTER);

        deselectButton = createIconButton(context, DRAWABLE_MORPHE_SETTINGS_DESELECT_ALL, () -> {
            listView.clearChoices();
            adapter.notifyDataSetChanged();
            updateBottomBar();
        });

        bar.addView(actionButtons);
        bar.addView(selectionButtons);

        return bar;
    }

    /**
     * Shows the state buttons only while flags are selected.
     */
    private void updateBottomBar() {
        final boolean hasSelection = !getSelectedFlags().isEmpty();
        actionButtons.setVisibility(hasSelection ? View.GONE : View.VISIBLE);
        selectionButtons.setVisibility(hasSelection ? View.VISIBLE : View.GONE);

        if (hasSelection) {
            selectionButtons.removeAllViews();
            Context context = getContext();

            Button actionButton = switch (filter) {
                case ACTIVE -> createStateButton(context,
                        "morphe_debug_feature_flags_manager_action_force_off", FlagState.BLOCKED);
                case INACTIVE -> createStateButton(context,
                        "morphe_debug_feature_flags_manager_action_force_on", FlagState.FORCED);
                case BLOCKED, FORCED -> createStateButton(context,
                        "morphe_debug_feature_flags_manager_action_remove", FlagState.AUTO);
            };

            selectionButtons.addView(actionButton);
            selectionButtons.addView(deselectButton);
        }
    }

    /**
     * Creates a button that applies a state to the selected flags.
     */
    private Button createStateButton(Context context, String stringKey, FlagState state) {
        Button button = CustomDialog.createButton(context, null, str(stringKey),
                () -> applyStateToSelection(state), false, false);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Dim.dp36, 1f);
        params.setMargins(Dim.dp4, Dim.dp8, Dim.dp4, Dim.dp8);
        button.setLayoutParams(params);

        return button;
    }

    /**
     * Creates a styled ImageButton.
     */
    @SuppressLint("ResourceType")
    private ImageButton createIconButton(Context context, int drawableResId, Runnable action) {
        ImageButton button = new ImageButton(context);

        button.setImageResource(drawableResId);
        button.setScaleType(ImageView.ScaleType.CENTER);
        int[] attrs = {android.R.attr.selectableItemBackgroundBorderless};
        //noinspection Recycle
        TypedArray ripple = context.obtainStyledAttributes(attrs);
        button.setBackgroundDrawable(ripple.getDrawable(0));
        ripple.close();

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Dim.dp32, Dim.dp32);
        params.setMargins(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);
        button.setLayoutParams(params);

        button.setOnClickListener(v -> action.run());

        return button;
    }

    /**
     * @return The selected flags, or an empty list if nothing is selected.
     */
    private List<Long> getSelectedFlags() {
        List<Long> selected = new ArrayList<>();
        if (listView == null) return selected;

        SparseBooleanArray checked = listView.getCheckedItemPositions();
        for (int i = 0, count = adapter.getCount(); i < count; i++) {
            if (checked.get(i)) {
                selected.add(adapter.getItem(i));
            }
        }

        return selected;
    }

    private void applyStateToSelection(FlagState state) {
        for (Long flag : getSelectedFlags()) {
            flagStates.put(flag, state);
        }

        listView.clearChoices();
        adapter.refresh();
        updateChips();
        updateBottomBar();
    }

    /**
     * Copies the selected flags, or all shown flags if nothing is selected.
     */
    private void copyFlags() {
        List<Long> flags = getSelectedFlags();
        if (flags.isEmpty()) {
            for (int i = 0, count = adapter.getCount(); i < count; i++) {
                flags.add(adapter.getItem(i));
            }
        }

        Utils.setClipboard(EnableDebuggingPatch.serializeFlags(flags));
        Utils.showToastShort(str("morphe_debug_feature_flags_manager_toast_copied"));
    }


    /**
     * Shows a dialog with the current flags as text, which can be shared with
     * another device to reproduce the same set of flags.
     */
    private void showImportExportDialog(Context context) {
        EditText editText = new EditText(context);
        editText.setTextSize(14);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setSingleLine(false);
        editText.setText(buildExportText());

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_debug_feature_flags_manager_import_export_title"),
                null,
                editText,
                str("morphe_settings_save"),
                () -> importText(editText.getText().toString()),
                () -> {},
                str("morphe_settings_import_copy"),
                () -> Utils.setClipboard(editText.getText().toString()),
                true
        );

        // Insert the file buttons between the text and the dialog buttons.
        dialogPair.second.addView(createFileButtons(context, editText), 2);

        dialogPair.first.show();
    }

    private LinearLayout createFileButtons(Context context, EditText editText) {
        Button buttonExport = CustomDialog.createButton(context, null,
                str("morphe_settings_export_file"),
                () -> exportToFile(editText.getText().toString()), false, false);
        Button buttonImport = CustomDialog.createButton(context, null,
                str("morphe_settings_import_file"), () -> importFromFile(editText), false, false);

        return CustomDialog.createButtonRow(context, buttonExport, buttonImport);
    }

    private void exportToFile(String text) {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null) return;

        fragment.exportTextActivity(AbstractPreferenceFragment.exportFileName("Feature_Flags"), text,
                success -> Utils.showToastShort(str(success
                        ? "morphe_debug_feature_flags_manager_export_file_success"
                        : "morphe_debug_feature_flags_manager_export_file_failed")));
    }

    private void importFromFile(EditText editText) {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null) return;

        fragment.importTextActivity(text -> {
            if (text == null) {
                Utils.showToastShort(str("morphe_debug_feature_flags_manager_import_file_failed"));
                return;
            }

            editText.setText(text);
            Utils.showToastShort(str("morphe_debug_feature_flags_manager_import_file_success"));
        });
    }

    /**
     * @return The flags that are in the given state, in ascending order.
     */
    private List<Long> flagsWithState(FlagState state) {
        List<Long> flags = new ArrayList<>();
        for (Map.Entry<Long, FlagState> entry : flagStates.entrySet()) {
            if (entry.getValue() == state) flags.add(entry.getKey());
        }

        return flags;
    }

    private String buildExportText() {
        List<Long> on = new ArrayList<>();
        List<Long> off = new ArrayList<>();
        for (Long flag : flagStates.keySet()) {
            if (Boolean.TRUE.equals(loggedFlagStates.get(flag))) {
                on.add(flag);
            } else {
                off.add(flag);
            }
        }

        return "app=" + Utils.getAppVersionName()
                + "\nblocked=" + EnableDebuggingPatch.serializeFlags(flagsWithState(FlagState.BLOCKED), ',')
                + "\nforced=" + EnableDebuggingPatch.serializeFlags(flagsWithState(FlagState.FORCED), ',')
                + "\non=" + EnableDebuggingPatch.serializeFlags(on, ',')
                + "\noff=" + EnableDebuggingPatch.serializeFlags(off, ',');
    }

    /**
     * Applies exported text. Lines that are only flag IDs are treated as blocked flags,
     * so the output of the copy button can be pasted as well.
     */
    private void importText(String text) {
        try {
            TreeSet<Long> blocked = new TreeSet<>();
            TreeSet<Long> forced = new TreeSet<>();
            TreeSet<Long> on = new TreeSet<>();
            TreeSet<Long> off = new TreeSet<>();

            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                final int separator = trimmed.indexOf('=');
                String key = separator < 0 ? "blocked" : trimmed.substring(0, separator).trim();
                String value = separator < 0 ? trimmed : trimmed.substring(separator + 1);

                switch (key) {
                    case "blocked": blocked.addAll(EnableDebuggingPatch.parseFlags(value)); break;
                    case "forced": forced.addAll(EnableDebuggingPatch.parseFlags(value)); break;
                    case "on": on.addAll(EnableDebuggingPatch.parseFlags(value)); break;
                    case "off": off.addAll(EnableDebuggingPatch.parseFlags(value)); break;
                    default: break; // Ignore anything else, such as the app version.
                }
            }

            on.removeAll(FLAGS_TO_IGNORE);
            off.removeAll(FLAGS_TO_IGNORE);
            blocked.removeAll(FLAGS_TO_IGNORE);
            forced.removeAll(FLAGS_TO_IGNORE);

            if (on.isEmpty() && off.isEmpty() && blocked.isEmpty() && forced.isEmpty()) {
                Utils.showToastLong(str("morphe_debug_feature_flags_manager_import_failed"));
                return;
            }

            flagStates.clear();
            loggedFlagStates.clear();

            // 1. Add all known flags as AUTO and record their logged state.
            for (Long flag : on) {
                flagStates.put(flag, FlagState.AUTO);
                loggedFlagStates.put(flag, Boolean.TRUE);
            }
            for (Long flag : off) {
                flagStates.put(flag, FlagState.AUTO);
                loggedFlagStates.put(flag, Boolean.FALSE);
            }

            // 2. Apply user overrides (overwrites the AUTO state).
            // forced takes priority over AUTO, blocked takes priority over both.
            for (Long flag : forced) flagStates.put(flag, FlagState.FORCED);
            for (Long flag : blocked) flagStates.put(flag, FlagState.BLOCKED);

            persistFlagStates();

            listView.clearChoices();
            adapter.refresh();
            updateChips();
            updateBottomBar();

            Utils.showToastShort(str("morphe_debug_feature_flags_manager_import_success", flagStates.size()));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not import feature flags", ex);
        }
    }

    /**
     * Explains the binary search, then starts it.
     */
    private void showBisectStartDialog(Context context, Dialog managerDialog) {
        List<Long> candidates = new ArrayList<>(flagStates.keySet());
        // A forced flag is set by the user and cannot be the cause of an unexpected behavior.
        candidates.removeAll(flagsWithState(FlagState.FORCED));
        // A blocked flag is already off and cannot be the cause of a behavior that is present.
        candidates.removeAll(flagsWithState(FlagState.BLOCKED));

        if (candidates.isEmpty()) {
            CustomDialog.create(
                    context,
                    str("morphe_debug_feature_flags_manager_bisect_title"),
                    str("morphe_debug_feature_flags_manager_bisect_no_candidates"),
                    null,
                    null,
                    null,
                    () -> {},
                    null,
                    null,
                    false
            ).first.show();
            return;
        }

        CustomDialog.create(
                context,
                str("morphe_debug_feature_flags_manager_bisect_title"),
                str("morphe_debug_feature_flags_manager_bisect_start_message", candidates.size()),
                null,
                str("morphe_debug_feature_flags_manager_bisect_start_button"),
                () -> {
                    // The search blocks flags on top of what is saved, so save the current list first.
                    persistFlagStates();
                    FeatureFlagsBisect.start(candidates);
                    managerDialog.dismiss();
                    AbstractPreferenceFragment.showRestartDialog(context);
                },
                () -> {},
                null,
                null,
                false
        ).first.show();
    }

    /**
     * Shows the three answers of a binary search in progress.
     */
    private void showBisectDialog(Context context, FeatureFlagsBisect bisect) {
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_debug_feature_flags_manager_bisect_title"),
                str("morphe_debug_feature_flags_manager_bisect_status",
                        bisect.getStep(), bisect.getRemainingCount(), bisect.getTestingCount()),
                null,
                null,
                null,
                () -> {},
                null,
                null,
                false
        );

        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.addView(createBisectButton(context, dialog,
                "morphe_debug_feature_flags_manager_bisect_present", true,
                () -> answerBisect(context, bisect, true)));
        buttons.addView(createBisectButton(context, dialog,
                "morphe_debug_feature_flags_manager_bisect_absent", false,
                () -> answerBisect(context, bisect, false)));
        buttons.addView(createBisectButton(context, dialog,
                "morphe_debug_feature_flags_manager_bisect_cancel", false,
                () -> {
                    bisect.cancel();
                    Utils.showToastShort(str("morphe_debug_feature_flags_manager_bisect_canceled"));
                    AbstractPreferenceFragment.showRestartDialog(context);
                }));

        // Insert the answers before the dialog button row.
        mainLayout.addView(buttons, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.show();
    }

    private Button createBisectButton(Context context, Dialog dialog, String stringKey,
                                      boolean isOkButton, Runnable action) {
        Button button = CustomDialog.createButton(context, dialog, str(stringKey), action, isOkButton, true);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
        params.setMargins(0, Dim.dp8, 0, 0);
        button.setLayoutParams(params);

        return button;
    }

    private void answerBisect(Context context, FeatureFlagsBisect bisect, boolean behaviorPresent) {
        FeatureFlagsBisect.Result result = bisect.answer(behaviorPresent);
        if (result == FeatureFlagsBisect.Result.CONTINUE) {
            AbstractPreferenceFragment.showRestartDialog(context);
            return;
        }

        String message = result == FeatureFlagsBisect.Result.FOUND
                ? str("morphe_debug_feature_flags_manager_bisect_found", bisect.getFoundFlag())
                : str("morphe_debug_feature_flags_manager_bisect_exhausted");

        CustomDialog.create(
                context,
                str("morphe_debug_feature_flags_manager_bisect_title"),
                message,
                null,
                null,
                () -> AbstractPreferenceFragment.showRestartDialog(context),
                null,
                null,
                null,
                false
        ).first.show();
    }

    /**
     * Saves the blocked and forced flags without any user feedback.
     */
    private void persistFlagStates() {
        List<Long> blocked = flagsWithState(FlagState.BLOCKED);
        List<Long> forced = flagsWithState(FlagState.FORCED);

        SharedYouTubeSettings.DISABLED_FEATURE_FLAGS.save(EnableDebuggingPatch.serializeFlags(blocked));
        SharedYouTubeSettings.FORCED_FEATURE_FLAGS.save(EnableDebuggingPatch.serializeFlags(forced));

        Logger.printDebug(() -> "Feature flags saved. Blocked: " + blocked.size()
                + " forced: " + forced.size());
    }

    /**
     * Saves the flags and asks to restart.
     */
    private void saveFlags() {
        persistFlagStates();
        Utils.showToastShort(str("morphe_debug_feature_flags_manager_toast_saved"));

        AbstractPreferenceFragment.showRestartDialog(getContext());
    }

    /**
     * Resets all blocked and forced flags.
     */
    private void resetFlags() {
        SharedYouTubeSettings.DISABLED_FEATURE_FLAGS.resetToDefault();
        SharedYouTubeSettings.FORCED_FEATURE_FLAGS.resetToDefault();
        Utils.showToastShort(str("morphe_debug_feature_flags_manager_toast_reset"));

        AbstractPreferenceFragment.showRestartDialog(getContext());
    }

    /**
     * A row showing a flag and its state, highlighted while selected.
     */
    private static class FlagRow extends LinearLayout implements Checkable {
        private final TextView flagText;
        private final ShapeDrawable background;
        private boolean checked;

        FlagRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);

            background = new ShapeDrawable(new RoundRectShape(Dim.roundedCorners(8), null, null));
            background.getPaint().setColor(Color.TRANSPARENT);
            setBackground(background);

            flagText = new TextView(context);
            flagText.setTextSize(15);
            flagText.setSingleLine(true);
            flagText.setEllipsize(TextUtils.TruncateAt.END);
            flagText.setTextColor(ThemeUtils.getAppForegroundColor());
            addView(flagText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }

        void bind(long flag) {
            flagText.setText(String.valueOf(flag));
        }

        @Override
        public void setChecked(boolean checked) {
            this.checked = checked;
            int color = ThemeUtils.getAppForegroundColor();
            int alpha = 0x33;
            background.getPaint().setColor(checked
                    ? (alpha << 24) | (color & 0x00FFFFFF)
                    : Color.TRANSPARENT);
            invalidate();
        }

        @Override
        public boolean isChecked() {
            return checked;
        }

        @Override
        public void toggle() {
            setChecked(!checked);
        }

    }

    /**
     * Shows the flags of the current filter that match the search.
     */
    private class FlagAdapter extends BaseAdapter {
        private final Context context;
        private final List<Long> shownFlags = new ArrayList<>();
        private String searchQuery = "";

        FlagAdapter(Context context) {
            this.context = context;
        }

        void setSearchQuery(String query) {
            searchQuery = query == null ? "" : query.trim();
            refresh();
        }

        void refresh() {
            shownFlags.clear();
            for (Map.Entry<Long, FlagState> entry : flagStates.entrySet()) {
                if (!filter.matches(entry.getValue(), loggedFlagStates.get(entry.getKey()))) continue;
                if (!searchQuery.isEmpty() && !String.valueOf(entry.getKey()).contains(searchQuery)) continue;
                shownFlags.add(entry.getKey());
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return shownFlags.size();
        }

        @Override
        public Long getItem(int position) {
            return shownFlags.get(position);
        }

        @Override
        public long getItemId(int position) {
            return shownFlags.get(position);
        }

        @Override
        public View getView(int position, @Nullable View convertView, ViewGroup parent) {
            FlagRow row = convertView instanceof FlagRow
                    ? (FlagRow) convertView
                    : new FlagRow(context);

            row.bind(shownFlags.get(position));

            return row;
        }
    }
}
