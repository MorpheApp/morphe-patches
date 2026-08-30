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
import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.preference.TwoStatePreference;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Pair;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Scanner;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedSettings;
import app.morphe.extension.shared.settings.preference.about.MorpheAboutPreference;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("deprecation")
public abstract class AbstractPreferenceFragment extends PreferenceFragment {

    private static void performToggleHaptic(View view, TwoStatePreference pref) {
        int feedbackConstant = Build.VERSION.SDK_INT >= 34
                ? (pref.isChecked() ? HapticFeedbackConstants.TOGGLE_OFF : HapticFeedbackConstants.TOGGLE_ON)
                : HapticFeedbackConstants.CLOCK_TICK;
        view.performHapticFeedback(feedbackConstant);
    }

    private static class ClickDebouncer {
        private static final long FAST_CLICK_MS = 300;
        private int lastPosition = AdapterView.INVALID_POSITION;
        private long lastTimeMs;

        boolean isFastRepeat(int position) {
            final long now = SystemClock.elapsedRealtime();
            if (position == lastPosition && now - lastTimeMs < FAST_CLICK_MS) {
                return true;
            }
            lastPosition = position;
            lastTimeMs = now;
            return false;
        }
    }

    private static class DebouncedListView extends ListView {
        private final ClickDebouncer debouncer = new ClickDebouncer();

        public DebouncedListView(Context context) {
            super(context);

            setId(android.R.id.list); // Required so PreferenceFragment recognizes it.

            // Match the default layout params
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }

        @Override
        public boolean performItemClick(View view, int position, long id) {
            Object item = getAdapter().getItem(position);

            if (item instanceof TwoStatePreference twoState) {
                performToggleHaptic(view, twoState);
                return super.performItemClick(view, position, id);
            }

            // Only debounce repeated taps on the same item to prevent accidental double-clicks.
            if (debouncer.isFastRepeat(position)) return true;
            return super.performItemClick(view, position, id);
        }
    }

    private static class DebouncedItemClickListener implements AdapterView.OnItemClickListener {
        private final AdapterView.OnItemClickListener originalListener;
        private final ClickDebouncer debouncer = new ClickDebouncer();

        DebouncedItemClickListener(AdapterView.OnItemClickListener originalListener) {
            this.originalListener = originalListener;
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Object item = parent.getAdapter().getItem(position);

            if (item instanceof TwoStatePreference twoState) {
                performToggleHaptic(view, twoState);
                originalListener.onItemClick(parent, view, position, id);
                return;
            }

            // Only debounce repeated taps on the same item to prevent accidental double-clicks.
            if (debouncer.isFastRepeat(position)) return;
            originalListener.onItemClick(parent, view, position, id);
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference instanceof PreferenceScreen screen) {
            Dialog dialog = screen.getDialog();
            if (dialog != null) {
                ListView listView = dialog.findViewById(android.R.id.list);
                if (listView != null) {
                    AdapterView.OnItemClickListener originalListener = listView.getOnItemClickListener();
                    if (originalListener != null && !(originalListener instanceof DebouncedItemClickListener)) {
                        listView.setOnItemClickListener(new DebouncedItemClickListener(originalListener));
                    }
                    if (listView.getOnItemLongClickListener() == null) {
                        listView.setOnItemLongClickListener(this::onPreferenceLongClick);
                    }
                }
            }
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    // Path is resolved by walking from the root PreferenceScreen because
    // android.preference.Preference does not expose getParent().
    private boolean onPreferenceLongClick(AdapterView<?> parent, View view, int position, long id) {
        try {
            Object item = parent.getAdapter().getItem(position);
            if (!(item instanceof Preference preference)) return false;

            List<CharSequence> path = findPreferencePath(preference);
            if (path == null || path.isEmpty()) return false;

            String text = TextUtils.join(" > ", path);
            Utils.setClipboard(text);
            Utils.showToastShort(str("morphe_settings_menu_copy_path", text));
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onPreferenceLongClick failure", ex);
            return false;
        }
    }

    @Nullable
    private List<CharSequence> findPreferencePath(Preference target) {
        PreferenceScreen root = getPreferenceScreen();
        if (root == null) return null;
        List<CharSequence> path = new ArrayList<>();
        path.add(str("morphe_settings_title"));
        if (target == root) return path;
        return searchPreferencePath(root, target, path) ? path : null;
    }

    private static boolean searchPreferencePath(PreferenceGroup group, Preference target, List<CharSequence> path) {
        for (int i = 0, n = group.getPreferenceCount(); i < n; i++) {
            Preference p = group.getPreference(i);
            // NoTitlePreferenceCategory reports its first child title so it can be sorted,
            // but that title is never shown to the user and must not appear in the path.
            CharSequence title = (p instanceof NoTitlePreferenceCategory) ? null : p.getTitle();
            if (p == target) {
                if (title != null) path.add(title);
                return true;
            }
            if (p instanceof PreferenceGroup subGroup) {
                int sizeBefore = path.size();
                if (title != null) path.add(title);
                if (searchPreferencePath(subGroup, target, path)) return true;
                while (path.size() > sizeBefore) path.remove(path.size() - 1);
            }
        }
        return false;
    }

    // Cached Collator instance with its locale.
    @Nullable
    private static Locale cachedCollatorLocale;

    @Nullable
    private static Collator cachedCollator;

    public static WeakReference<AbstractPreferenceFragment> instance = new WeakReference<>(null);

    /**
     * Indicates that if a preference changes,
     * to apply the change from the Setting to the UI component.
     */
    public static boolean settingImportInProgress;

    /**
     * Prevents recursive calls during preference <-> UI syncing from showing extra dialogs.
     */
    private static boolean updatingPreference;

    /**
     * Used to prevent showing reboot dialog, if user cancels a setting user dialog.
     */
    private static boolean showingUserDialogMessage;

    /**
     * Confirm and restart dialog button text and title.
     * Set by subclasses if Strings cannot be added as a resource.
     */
    @Nullable
    protected static CharSequence restartDialogTitle, restartDialogMessage, restartDialogButtonText, confirmDialogTitle;

    private ComponentCallbacks2 configurationListener;
    private int currentUiMode = -1;
    private static final int WRITE_TEXT_REQUEST_CODE = 45;
    private static final int READ_TEXT_REQUEST_CODE = 46;

    private String existingSettings = "";

    private String pendingTextToExport = null;
    private Consumer<Boolean> pendingTextExportResult = null;
    private Consumer<String> pendingTextImportResult = null;

    private EditText currentImportExportEditText;
    private Dialog currentImportExportDialog = null;
    private Dialog currentLogDialog = null;

    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (sharedPreferences, str) -> {
        try {
            if (updatingPreference) {
                Logger.printDebug(() -> "Ignoring preference change as sync is in progress");
                return;
            }

            Setting<?> setting = Setting.getSettingFromPath(Objects.requireNonNull(str));
            if (setting == null) {
                return;
            }
            Preference pref = findPreference(str);
            if (pref == null) {
                return;
            }
            Logger.printDebug(() -> "Preference changed: " + setting.key);

            if (!settingImportInProgress && !showingUserDialogMessage) {
                if (setting.userDialogMessage != null && !prefIsSetToDefault(pref, setting)) {
                    // Do not change the setting yet, to allow preserving whatever
                    // list/text value was previously set if it needs to be reverted.
                    showSettingUserDialogConfirmation(pref, setting);
                    return;
                } else if (setting.rebootApp) {
                    showRestartDialog(getContext());
                }
            }

            updatingPreference = true;
            // Apply 'Setting <- Preference', unless during importing when it needs to be 'Setting -> Preference'.
            // Updating here can cause a recursive call back into this same method.
            updatePreference(pref, setting, true, settingImportInProgress);
            // Update any other preference availability that may now be different.
            updateUIAvailability();
            updatingPreference = false;
        } catch (Exception ex) {
            Logger.printException(() -> "OnSharedPreferenceChangeListener failure", ex);
        }
    };

    /**
     * Initialize this instance, and do any custom behavior.
     * <p>
     * To ensure all {@link Setting} instances are correctly synced to the UI,
     * it is important that subclasses make a call or otherwise reference their Settings class bundle
     * so all app specific {@link Setting} instances are loaded before this method returns.
     */
    protected void initialize() {
        // Must use utils modified language context if language override is active.
        if (!BaseSettings.MORPHE_LANGUAGE.isSetToDefault()) {
            ResourceUtils.useActivityContextIfAvailable = false;
        }

        String preferenceResourceName;
        if (SharedSettings.SHOW_MENU_ICONS.get()) {
            preferenceResourceName = Utils.appIsUsingBoldIcons()
                    ? "morphe_prefs_icons_bold"
                    : "morphe_prefs_icons";
        } else {
            preferenceResourceName = "morphe_prefs";
        }

        final var identifier = ResourceUtils.getIdentifier(ResourceType.XML, preferenceResourceName);
        if (identifier == 0) return;
        addPreferencesFromResource(identifier);

        PreferenceScreen screen = getPreferenceScreen();
        sortPreferenceGroups(screen);
        Utils.setPreferenceTitlesToMultiLineIfNeeded(screen);
    }

    private void showSettingUserDialogConfirmation(Preference pref, Setting<?> setting) {
        Utils.verifyOnMainThread();

        final var context = getContext();
        if (confirmDialogTitle == null) {
            confirmDialogTitle = str("morphe_settings_confirm_user_dialog_title");
        }

        showingUserDialogMessage = true;

        CharSequence message = BulletPointPreference.formatIntoBulletPoints(
                Objects.requireNonNull(setting.userDialogMessage).toString());

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                confirmDialogTitle, // Title.
                message,
                null, // No EditText.
                null, // OK button text.
                () -> {
                    // OK button action. User confirmed, save to the Setting.
                    updatePreference(pref, setting, true, false);

                    // Update availability of other preferences that may be changed.
                    updateUIAvailability();

                    if (setting.rebootApp) {
                        showRestartDialog(context);
                    }
                },
                () -> {
                    // Cancel button action. Restore whatever the setting was before the change.
                    updatePreference(pref, setting, true, true);
                },
                null, // No Neutral button.
                null, // No Neutral button action.
                true  // Dismiss dialog when onNeutralClick.
        );

        dialogPair.first.setOnDismissListener(d -> showingUserDialogMessage = false);
        dialogPair.first.setCancelable(false);

        // Show the dialog.
        dialogPair.first.show();
    }

    /**
     * Updates all Preferences values and their availability using the current values in {@link Setting}.
     */
    protected void updateUIToSettingValues() {
        updatePreferenceScreen(getPreferenceScreen(), true, true);
    }

    /**
     * Updates Preferences availability only using the status of {@link Setting}.
     */
    protected void updateUIAvailability() {
        updatePreferenceScreen(getPreferenceScreen(), false, false);
    }

    /**
     * @return If the preference is currently set to the default value of the Setting.
     */
    protected boolean prefIsSetToDefault(Preference pref, Setting<?> setting) {
        Object defaultValue = setting.defaultValue;
        if (pref instanceof SwitchPreference switchPref) {
            return switchPref.isChecked() == (Boolean) defaultValue;
        }
        String defaultValueString = defaultValue.toString();
        if (pref instanceof EditTextPreference editPreference) {
            return editPreference.getText().equals(defaultValueString);
        }
        if (pref instanceof ListPreference listPref) {
            return listPref.getValue().equals(defaultValueString);
        }

        throw new IllegalStateException("Must override method to handle preference type: " + pref.getClass());
    }

    /**
     * Syncs all UI Preferences to any {@link Setting} they represent.
     */
    private void updatePreferenceScreen(@NonNull PreferenceGroup group,
                                        boolean syncSettingValue,
                                        boolean applySettingToPreference) {
        // Alternatively this could iterate through all Settings and check for any matching Preferences,
        // but there are many more Settings than UI preferences so it's more efficient to only check
        // the Preferences.
        for (int i = 0, prefCount = group.getPreferenceCount(); i < prefCount; i++) {
            Preference pref = group.getPreference(i);
            if (pref instanceof PreferenceGroup subGroup) {
                updatePreferenceScreen(subGroup, syncSettingValue, applySettingToPreference);
            } else if (pref.hasKey()) {
                String key = pref.getKey();
                Setting<?> setting = Setting.getSettingFromPath(key);

                if (setting != null) {
                    updatePreference(pref, setting, syncSettingValue, applySettingToPreference);
                } else if (BaseSettings.DEBUG.get() && (pref instanceof SwitchPreference
                        || pref instanceof EditTextPreference || pref instanceof ListPreference)) {
                    // Probably a typo in the patches preference declaration.
                    Logger.printException(() -> "Preference key has no setting: " + key);
                }
            }
        }
    }

    /**
     * Handles syncing a UI Preference with the {@link Setting} that backs it.
     * If needed, subclasses can override this to handle additional UI Preference types.
     *
     * @param applySettingToPreference If true, then apply {@link Setting} -> Preference.
     *                                 If false, then apply {@link Setting} <- Preference.
     */
    protected void syncSettingWithPreference(@NonNull Preference pref,
                                             @NonNull Setting<?> setting,
                                             boolean applySettingToPreference) {
        if (pref instanceof SwitchPreference switchPref) {
            // Skip sync when the Setting's backing type does not match SwitchPreference's boolean model.
            // The preference overrides persistBoolean/getPersistedBoolean and maps the boolean
            // to a non-boolean backing on its own.
            if (!(setting instanceof BooleanSetting boolSetting)) {
                return;
            }
            if (applySettingToPreference) {
                switchPref.setChecked(boolSetting.get());
            } else {
                BooleanSetting.privateSetValue(boolSetting, switchPref.isChecked());
            }
        } else if (pref instanceof EditTextPreference editPreference) {
            if (applySettingToPreference) {
                editPreference.setText(setting.get().toString());
            } else {
                Setting.privateSetValueFromString(setting, editPreference.getText());
            }
        } else if (pref instanceof ListPreference listPref) {
            if (applySettingToPreference) {
                listPref.setValue(setting.get().toString());
            } else {
                Setting.privateSetValueFromString(setting, listPref.getValue());
            }
            updateListPreferenceSummary(listPref, setting);
        }
    }

    /**
     * Updates a UI Preference with the {@link Setting} that backs it.
     *
     * @param syncSetting If the UI should be synced {@link Setting} <-> Preference
     * @param applySettingToPreference If true, then apply {@link Setting} -> Preference.
     *                                 If false, then apply {@link Setting} <- Preference.
     */
    private void updatePreference(@NonNull Preference pref, @NonNull Setting<?> setting,
                                  boolean syncSetting, boolean applySettingToPreference) {
        if (!syncSetting && applySettingToPreference) {
            throw new IllegalArgumentException();
        }

        if (syncSetting) {
            syncSettingWithPreference(pref, setting, applySettingToPreference);
        }

        updatePreferenceAvailability(pref, setting);
    }

    protected void updatePreferenceAvailability(@NonNull Preference pref, @NonNull Setting<?> setting) {
        pref.setEnabled(setting.isAvailable());
    }

    protected void updateListPreferenceSummary(ListPreference listPreference, Setting<?> setting) {
        String objectStringValue = setting.get().toString();
        final int entryIndex = listPreference.findIndexOfValue(objectStringValue);
        if (entryIndex >= 0) {
            listPreference.setSummary(listPreference.getEntries()[entryIndex]);
        } else {
            // Value is not an available option.
            // User manually edited import data, or options changed and current selection is no longer available.
            // Still show the value in the summary, so it's clear that something is selected.
            listPreference.setSummary(objectStringValue);
        }
    }

    public static void showRestartDialog(Context context) {
        Utils.verifyOnMainThread();
        if (restartDialogTitle == null) {
            restartDialogTitle = str("morphe_settings_restart_title");
        }
        if (restartDialogMessage == null) {
            restartDialogMessage = str("morphe_settings_restart_dialog_message");
        }
        if (restartDialogButtonText == null) {
            restartDialogButtonText = str("morphe_settings_restart");
        }

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                restartDialogTitle,              // Title.
                restartDialogMessage,            // Message.
                null,                            // No EditText.
                restartDialogButtonText,         // OK button text.
                () -> Utils.restartApp(context), // OK button action.
                () -> {},                        // Cancel button action (dismiss only).
                null,                            // No Neutral button text.
                null,                            // No Neutral button action.
                true                             // Dismiss dialog when onNeutralClick.
        );

        // Show the dialog.
        dialogPair.first.show();
    }

    /**
     * Import / Export Subroutines
     */
    public void showImportExportTextDialog() {
        try {
            if (currentImportExportDialog != null && currentImportExportDialog.isShowing()) {
                return;
            }

            Activity context = getActivity();
            // Must set text before showing dialog,
            // otherwise text is non-selectable if this preference is later reopened.
            existingSettings = Setting.exportToJson(context);
            currentImportExportEditText = getEditText(context);

            // Create a custom dialog with the EditText.
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    // Title.
                    str("morphe_pref_import_export_title"),
                    // No message (EditText replaces it).
                    null,
                    // Pass the EditText.
                    currentImportExportEditText,
                    // OK button text.
                    str("morphe_settings_save"),
                    // OK button action.
                    () -> importSettingsText(context, currentImportExportEditText.getText().toString()),
                    // Cancel button action (dismiss only).
                    () -> {},
                    // Neutral button (Copy) text.
                    str("morphe_settings_import_copy"),
                    // Neutral button (Copy) action. Show the user the settings in JSON format.
                    () -> Utils.setClipboard(currentImportExportEditText.getText().toString()),
                    // Dismiss dialog when onNeutralClick.
                    true
            );

            currentImportExportDialog = dialogPair.first;

            Button buttonExport = CustomDialog.createButton(context, null,
                    str("morphe_settings_export_file"), this::exportActivity, false, false);
            Button buttonImport = CustomDialog.createButton(context, null,
                    str("morphe_settings_import_file"), this::importActivity, false, false);

            dialogPair.second.addView(
                    CustomDialog.createButtonRow(context, buttonExport, buttonImport), 2);

            dialogPair.first.setOnDismissListener(d -> {
                currentImportExportEditText = null;
                currentImportExportDialog = null;
            });

            // If there are no settings yet, then show the on-screen keyboard and bring focus to
            // the edit text. This makes it easier to paste saved settings after a reinstallation.
            dialogPair.first.setOnShowListener(dialogInterface -> {
                if (existingSettings.isEmpty() && currentImportExportEditText != null) {
                    currentImportExportEditText.postDelayed(() -> {
                        if (currentImportExportEditText != null) {
                            currentImportExportEditText.requestFocus();
                            InputMethodManager imm =(InputMethodManager) context.getSystemService(
                                    Context.INPUT_METHOD_SERVICE);
                            if (imm != null) imm.showSoftInput(
                                    currentImportExportEditText, InputMethodManager.SHOW_IMPLICIT);
                        }
                    }, 100);
                }
            });

            // Show the dialog.
            dialogPair.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showImportExportTextDialog failure", ex);
        }
    }

    @NonNull
    private EditText getEditText(Context context) {
        EditText editText = new EditText(context);
        editText.setText(existingSettings);
        editText.setAutofillHints((String) null);
        editText.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setSingleLine(false);
        editText.setTextSize(14);
        return editText;
    }

    /**
     * @param kind What is exported, such as the settings or the debug logs.
     * @return App name, kind and date, suggested as the name of an exported file.
     */
    public static String exportFileName(String kind) {
        String appName = Utils.getApplicationName().replaceAll("\\s+", "_");
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        return appName + "_" + kind + "_" + date + ".txt";
    }

    public void exportActivity() {
        String textToExport = currentImportExportEditText != null
                ? currentImportExportEditText.getText().toString()
                : existingSettings;

        exportTextActivity(exportFileName("Settings"), textToExport, success -> showLocalizedToast(
                success ? "morphe_settings_export_file_success" : "morphe_settings_export_file_failed",
                success ? "Settings exported successfully" : "Failed to export settings"));
    }

    public void importActivity() {
        importTextActivity(text -> {
            if (text == null) {
                showLocalizedToast(
                        "morphe_settings_import_file_failed",
                        "Failed to import settings");
                return;
            }

            if (currentImportExportEditText != null) {
                currentImportExportEditText.setText(text);
                showLocalizedToast("morphe_settings_import_file_success",
                        "Settings imported successfully, tap Save to apply");
            } else {
                importSettingsText(getContext(), text);
            }
        });
    }

    /**
     * Opens the file picker to save arbitrary text, for preferences that have
     * their own import and export dialog.
     *
     * @param fileName Name suggested to the user.
     * @param text     Text to write.
     * @param onResult Called with the result, or not called if the user picks no file.
     */
    public void exportTextActivity(String fileName, String text, Consumer<Boolean> onResult) {
        try {
            pendingTextToExport = text;
            pendingTextExportResult = onResult;

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            startActivityForResult(intent, WRITE_TEXT_REQUEST_CODE);
        } catch (Exception ex) {
            Logger.printException(() -> "exportTextActivity failure", ex);
        }
    }

    /**
     * Opens the file picker to read arbitrary text, for preferences that have
     * their own import and export dialog.
     *
     * @param onResult Called with the text read, or null if it could not be read.
     *                 Not called if the user picks no file.
     */
    public void importTextActivity(Consumer<String> onResult) {
        try {
            pendingTextImportResult = onResult;

            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, READ_TEXT_REQUEST_CODE);
        } catch (Exception ex) {
            Logger.printException(() -> "importTextActivity failure", ex);
        }
    }

    private void saveTextToUri(Uri uri) {
        if (pendingTextExportResult == null) return;

        boolean success = false;
        try (OutputStream out = getContext().getContentResolver().openOutputStream(uri, "rwt")) {
            if (out != null) {
                out.write(pendingTextToExport.getBytes(StandardCharsets.UTF_8));
                success = true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "saveTextToUri failure", ex);
        }

        pendingTextExportResult.accept(success);
        pendingTextToExport = null;
        pendingTextExportResult = null;
    }

    @SuppressWarnings("CharsetObjectCanBeUsed")
    private void readTextFromUri(Uri uri) {
        if (pendingTextImportResult == null) return;

        String text = null;
        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            if (in != null) {
                Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
                text = scanner.hasNext() ? scanner.next() : "";
            }
        } catch (Exception ex) {
            Logger.printException(() -> "readTextFromUri failure", ex);
        }

        pendingTextImportResult.accept(text);
        pendingTextImportResult = null;
    }

    private void exportToClipboard(String logsToExport) {
        try {
            if (!BaseSettings.DEBUG.get()) {
                Utils.showToastShort(str("morphe_debug_logs_disabled"));
                return;
            }

            if (logsToExport == null || logsToExport.isBlank()) {
                Utils.showToastShort(str("morphe_debug_logs_none_found"));
                return;
            }

            Utils.showToastShort(str("morphe_debug_logs_copied_to_clipboard"));
            Utils.setClipboard(logsToExport);

        } catch (Exception ex) {
            String errorMessage = String.format(str("morphe_debug_logs_failed_to_export"), ex.getMessage());
            Utils.showToastLong(errorMessage);
            Logger.printDebug(() -> errorMessage, ex);
        }
    }

    private void exportToFile(String logsToExport) {
        if (!BaseSettings.DEBUG.get() || logsToExport == null || logsToExport.trim().isEmpty()) {
            Utils.showToastShort(str("morphe_debug_logs_none_found"));
            return;
        }

        exportTextActivity(exportFileName("Debug_Logs"), logsToExport, success -> showLocalizedToast(
                success ? "morphe_debug_export_logs_success" : "morphe_debug_export_logs_failed",
                success ? "Debug logs exported successfully" : "Failed to export debug logs"));
    }

    private static class LogAdapter extends BaseAdapter {
        private final List<String> allLogs;
        private final List<String> filteredLogs = new ArrayList<>();
        private final Context context;
        private String query = "";

        LogAdapter(Context context, String[] logs) {
            this.context = context;
            this.allLogs = List.of(logs);
            this.filteredLogs.addAll(this.allLogs);
        }

        void filter(String q) {
            this.query = q.toLowerCase(Locale.ROOT);
            filteredLogs.clear();
            if (query.isEmpty()) {
                filteredLogs.addAll(allLogs);
            } else {
                for (String log : allLogs) {
                    if (log.toLowerCase(Locale.ROOT).contains(query)) {
                        filteredLogs.add(log);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return filteredLogs.size(); }
        @Override public Object getItem(int position) { return filteredLogs.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView textview;
            if (convertView == null) {
                textview = new TextView(context);
                textview.setTextSize(12);
                textview.setPadding(Dim.dp4, Dim.dp4, Dim.dp4, Dim.dp4);
                textview.setTextIsSelectable(true);
            } else {
                textview = (TextView) convertView;
            }

            String line = filteredLogs.get(position);

            if (query.isEmpty()) {
                textview.setText(line);
            } else {
                SpannableStringBuilder ssb = new SpannableStringBuilder(line);
                String lowerLine = line.toLowerCase(Locale.ROOT);
                int index = lowerLine.indexOf(query);
                while (index >= 0) {
                    ssb.setSpan(new BackgroundColorSpan(Color.LTGRAY),
                            index, index + query.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new ForegroundColorSpan(Color.BLACK),
                            index, index + query.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    index = lowerLine.indexOf(query, index + query.length());
                }
                textview.setText(ssb);
            }
            return textview;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    public void showLogDialog() {
        try {
            if (currentLogDialog != null && currentLogDialog.isShowing()) {
                return;
            }

            Context context = getContext();
            if (!BaseSettings.DEBUG.get()) {
                Utils.showToastShort(str("morphe_debug_logs_disabled"));
                return;
            }

            if (Logger.getLogBuffer().isEmpty()) {
                Utils.showToastShort(str("morphe_debug_logs_none_found"));
                Logger.clearLogBufferData();
                return;
            }

            final String allLogs = Logger.getFilteredLogs();
            final String[] logLines = allLogs.split("\n");

            ListView logViewer = new ListView(context);
            LogAdapter logAdapter = new LogAdapter(context, logLines);
            logViewer.setAdapter(logAdapter);
            logViewer.setDividerHeight(0);
            logViewer.setPadding(0, Dim.dp4, 0, Dim.dp4);
            logViewer.setOverScrollMode(View.OVER_SCROLL_NEVER);
            logViewer.setBackground(CustomDialog.createRoundedBackground(10, ThemeUtils.getEditTextBackground()));
            logViewer.setClipToOutline(true);

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_debug_export_logs_title"),
                    null,
                    null,
                    str("morphe_settings_import_copy"),
                    () -> exportToClipboard(allLogs),
                    () -> {},
                    str("morphe_debug_export_logs_clear"),
                    Logger::clearLogBufferData,
                    true
            );

            currentLogDialog = dialogPair.first;
            currentLogDialog.setOnDismissListener(d -> currentLogDialog = null);

            int margin = Dim.dp(16);

            final Handler searchHandler = new Handler(Looper.getMainLooper());
            final Runnable[] searchRunnable = {null};

            EditText searchBar = CustomDialog.createSearchBar(context,
                    str("morphe_debug_logs_search_hint"), query -> {
                if (searchRunnable[0] != null) {
                    searchHandler.removeCallbacks(searchRunnable[0]);
                }
                searchRunnable[0] = () -> logAdapter.filter(query);
                searchHandler.postDelayed(searchRunnable[0], 200);
            });

            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            searchParams.setMargins(0, 0, 0, Dim.dp8);
            searchBar.setLayoutParams(searchParams);

            LinearLayout fileButtonsContainer = new LinearLayout(context);
            fileButtonsContainer.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams fbParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            fbParams.setMargins(0, margin, 0, 0);
            fileButtonsContainer.setLayoutParams(fbParams);

            Button buttonExport = CustomDialog.createButton(context, dialogPair.first,
                    str("morphe_debug_export_logs_file"),
                    () -> exportToFile(allLogs),
                    false, true);

            LinearLayout.LayoutParams buttonExportParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
            buttonExport.setLayoutParams(buttonExportParams);
            fileButtonsContainer.addView(buttonExport);

            LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
            listParams.setMargins(0, 0, 0, 0);
            logViewer.setLayoutParams(listParams);

            LinearLayout mainLayout = dialogPair.second;
            mainLayout.addView(searchBar, 1);
            mainLayout.addView(logViewer, 2);
            mainLayout.addView(fileButtonsContainer, 3);

            dialogPair.first.show();

        } catch (Exception ex) {
            Logger.printException(() -> "showLogDialog failure", ex);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        DebouncedListView listView = new DebouncedListView(getActivity());
        listView.setOnItemLongClickListener(this::onPreferenceLongClick);
        return listView;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WRITE_TEXT_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            saveTextToUri(data.getData());
        } else if (requestCode == READ_TEXT_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            readTextFromUri(data.getData());
        }
    }

    protected static void showLocalizedToast(String resourceKey, String fallbackMessage) {
        if (ResourceUtils.getIdentifier(ResourceType.STRING, resourceKey) != 0) {
            Utils.showToastLong(str(resourceKey));
        } else {
            Utils.showToastLong(fallbackMessage);
        }
    }

    private void importSettingsText(Context context, String replacementSettings) {
        try {
            existingSettings = Setting.exportToJson(null);
            if (replacementSettings.equals(existingSettings)) {
                return;
            }
            settingImportInProgress = true;
            final boolean rebootNeeded = Setting.importFromJSON(getActivity(), replacementSettings);
            if (rebootNeeded) {
                showRestartDialog(context);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "importSettingsText failure", ex);
        } finally {
            settingImportInProgress = false;
        }
    }

    @SuppressLint("ResourceType")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentUiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        instance = new WeakReference<>(this);

        configurationListener = new ComponentCallbacks2() {
            @SuppressLint("ChromeOsOnConfigurationChanged")
            @Override
            public void onConfigurationChanged(@NonNull Configuration newConfig) {
                int newUiMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
                if (currentUiMode != -1 && newUiMode != currentUiMode) {
                    currentUiMode = newUiMode;
                    Utils.setIsDarkModeEnabled(newUiMode == Configuration.UI_MODE_NIGHT_YES);

                    Activity activity = getActivity();
                    if (activity != null) {
                        Intent intent = activity.getIntent();
                        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        activity.finish();
                        activity.overridePendingTransition(0, 0);
                        startActivity(intent);
                        activity.overridePendingTransition(0, 0);
                    }
                }
            }

            @Override
            public void onLowMemory() {}

            @Override
            public void onTrimMemory(int level) {}
        };
        getActivity().getApplicationContext().registerComponentCallbacks(configurationListener);

        try {
            PreferenceManager preferenceManager = getPreferenceManager();
            preferenceManager.setSharedPreferencesName(Setting.preferences.name);

            // Must initialize before adding change listener,
            // otherwise the syncing of Setting -> UI
            // causes a callback to the listener even though nothing changed.
            initialize();
            updateUIToSettingValues();

            preferenceManager.getSharedPreferences().registerOnSharedPreferenceChangeListener(listener);
        } catch (Exception ex) {
            Logger.printException(() -> "onCreate() failure", ex);
        }
    }

    @Override
    public void onDestroy() {
        if (instance.get() == this) {
            instance = new WeakReference<>(null);
        }
        if (configurationListener != null && getActivity() != null) {
            getActivity().getApplicationContext().unregisterComponentCallbacks(configurationListener);
        }
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(listener);
        super.onDestroy();
    }

    /**
     * Sorts a {@link PreferenceGroup} and all nested subgroups by title or key.
     * <p>
     * The sort order is controlled by the {@link Sort} suffix present in the preference key.
     * Preferences without a key or without a {@link Sort} suffix remain in their original order.
     * <p>
     * Sorting is performed using {@link Collator} with the current user locale,
     * ensuring correct alphabetical ordering for all supported languages
     * (e.g., Ukrainian "і", German "ß", French accented characters, etc.).
     *
     * @param group the {@link PreferenceGroup} to sort
     */
    @SuppressWarnings("deprecation")
    protected static void sortPreferenceGroups(PreferenceGroup group) {
        Sort groupSort = Sort.fromKey(group.getKey(), Sort.UNSORTED);
        List<Pair<String, Preference>> preferences = new ArrayList<>();

        // Get cached Collator for locale-aware string comparison.
        Collator collator = getCollator();

        for (int i = 0, prefCount = group.getPreferenceCount(); i < prefCount; i++) {
            Preference preference = group.getPreference(i);

            final Sort preferenceSort;
            if (preference instanceof PreferenceGroup subGroup) {
                sortPreferenceGroups(subGroup);
                preferenceSort = groupSort; // Sort value for groups is for it's content, not itself.
            } else {
                // Allow individual preferences to set a key sorting.
                // Used to force a preference to the top or bottom of a group.
                preferenceSort = Sort.fromKey(preference.getKey(), groupSort);
            }

            final String sortValue;
            switch (preferenceSort) {
                case BY_TITLE:
                    sortValue = Utils.removePunctuationToLowercase(preference.getTitle());
                    break;
                case BY_KEY:
                    String key = preference.getKey();
                    if (key == null) {
                        if (preference instanceof NoTitlePreferenceCategory noTitlePref) {
                            // Use first preference and not the category.
                            key = noTitlePref.getPreference(0).getKey();
                        } else {
                            throw new IllegalStateException(
                                    "Group is sort by key but preference has null key: " + preference
                            );
                        }
                    }
                    sortValue = key;
                    break;
                case UNSORTED:
                    continue; // Keep original sorting.
                default:
                    throw new IllegalStateException();
            }

            preferences.add(new Pair<>(sortValue, preference));
        }

        // Sort the list using locale-specific collation rules.
        preferences.sort((pair1, pair2)
                -> collator.compare(pair1.first, pair2.first));

        // Reassign order values to reflect the new sorted sequence
        int index = 0;
        for (Pair<String, Preference> pair : preferences) {
            int order = index++;
            Preference pref = pair.second;

            // Move any screens, intents, and the one off About preference to the top.
            if (pref instanceof PreferenceScreen || pref instanceof MorpheAboutPreference
                    || pref.getIntent() != null) {
                // Any arbitrary large number.
                order -= 1000;
            }

            pref.setOrder(order);
        }
    }

    /**
     * {@link PreferenceScreen} and {@link PreferenceGroup} sorting styles.
     */
    private enum Sort {
        /**
         * Sort by the localized preference title.
         */
        BY_TITLE("_sort_by_title"),

        /**
         * Sort by the preference keys.
         */
        BY_KEY("_sort_by_key"),

        /**
         * Unspecified sorting.
         */
        UNSORTED("_sort_by_unsorted");

        final String keySuffix;

        Sort(String keySuffix) {
            this.keySuffix = keySuffix;
        }

        static Sort fromKey(@Nullable String key, Sort defaultSort) {
            if (key != null) {
                for (Sort sort : values()) {
                    if (key.endsWith(sort.keySuffix)) {
                        return sort;
                    }
                }
            }
            return defaultSort;
        }
    }

    /**
     * Returns a cached Collator for the current locale, or creates a new one if locale changed.
     */
    private static Collator getCollator() {
        Locale currentLocale = BaseSettings.MORPHE_LANGUAGE.get().getLocale();

        if (cachedCollator == null || !currentLocale.equals(cachedCollatorLocale)) {
            cachedCollatorLocale = currentLocale;
            cachedCollator = Collator.getInstance(currentLocale);
            cachedCollator.setStrength(Collator.SECONDARY); // Case-insensitive, diacritic-insensitive.
        }

        return cachedCollator;
    }
}
