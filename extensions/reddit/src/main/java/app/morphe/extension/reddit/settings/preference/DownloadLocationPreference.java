/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3870
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.preference.Preference;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import app.morphe.extension.reddit.patches.DownloadLocationPatch;
import app.morphe.extension.reddit.patches.DownloadLocationPatch.Option;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.utils.DocumentTree;
import app.morphe.extension.reddit.utils.DownloadPathPattern;
import app.morphe.extension.reddit.utils.RedditAccounts;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Lists the download location of all accounts and of each account.
 * Each location is edited in a dialog with the folder, the subfolder and file name pattern, and an example path.
 */
@SuppressWarnings("deprecation")
public final class DownloadLocationPreference extends Preference {
    public static final int PICK_FOLDER_REQUEST_CODE = 0xC002;

    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    private static final String PRIMARY_VOLUME_PREFIX = "primary:";

    /**
     * Receives the folder picked for the open dialog. The choose button of the dialog keeps it alive.
     */
    private static WeakReference<Consumer<String>> pendingFolderPick = new WeakReference<>(null);

    /**
     * @param account Null for the location of all accounts.
     */
    private record Location(@Nullable String account, String label) {
    }

    public DownloadLocationPreference(Context context) {
        super(context);

        setTitle(str("morphe_download_location_title"));
        refreshSummary();

        setOnPreferenceClickListener(preference -> {
            showLocationsDialog();
            return true;
        });
    }

    private void refreshSummary() {
        setSummary(getLocationSummary(null));
    }

    /**
     * Shows the options instead of an example path, so Reddit's file names don't look like a chosen pattern.
     */
    private static String getLocationSummary(@Nullable String account) {
        if (account != null && !DownloadLocationPatch.isAccountEnabled(account)) {
            return str("morphe_download_location_uses_all_summary");
        }

        String folder = account == null
                ? Settings.DOWNLOAD_FOLDER.get().trim()
                : DownloadLocationPatch.getOptionValue(Option.FOLDER, account);
        String pattern = account == null
                ? Settings.DOWNLOAD_PATH_PATTERN.get().trim()
                : DownloadLocationPatch.getOptionValue(Option.PATH_PATTERN, account);
        if (folder.isEmpty() && pattern.isEmpty()) {
            return str("morphe_download_location_default_summary");
        }

        return str("morphe_download_location_summary_folder",
                folder.isEmpty() ? str("morphe_download_location_folder_default")
                        : DocumentTree.isTree(folder) ? DocumentTree.getLabel(folder)
                        : folder)
                + "\n" + str("morphe_download_location_summary_pattern",
                pattern.isEmpty() ? str("morphe_download_location_pattern_default") : pattern);
    }

    /**
     * @return All accounts, the active account, the other accounts logged in on this device,
     *         the anonymous account, and accounts that are no longer logged in but have options.
     */
    private static List<Location> getLocations() {
        String activeAccount = DownloadLocationPatch.getActiveAccount();
        List<String> loggedInAccounts = RedditAccounts.getLoggedIn();

        Set<String> accounts = new LinkedHashSet<>();
        accounts.add(activeAccount);
        accounts.addAll(loggedInAccounts);
        accounts.add(DownloadLocationPatch.ANONYMOUS_ACCOUNT);
        accounts.addAll(DownloadLocationPatch.getAccountsWithOptions());

        List<Location> locations = new ArrayList<>();
        locations.add(new Location(null, str("morphe_download_location_all_title")));
        for (String account : accounts) {
            final boolean anonymous = account.equals(DownloadLocationPatch.ANONYMOUS_ACCOUNT);
            String label = anonymous ? str("morphe_download_location_account_anonymous") : "u/" + account;
            if (account.equals(activeAccount)) {
                label = str("morphe_download_location_account_current", label);
            } else if (!anonymous && !loggedInAccounts.contains(account)) {
                label = str("morphe_download_location_account_logged_out", label);
            }
            locations.add(new Location(account, label));
        }
        return locations;
    }

    private void showLocationsDialog() {
        Context context = getContext();
        List<Location> locations = getLocations();

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return locations.size();
            }

            @Override
            public Location getItem(int position) {
                return locations.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Location location = getItem(position);
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Dim.dp8, Dim.dp12, Dim.dp8, Dim.dp12);
                row.addView(createText(context, location.label, 16, 1));
                row.addView(createText(context, getLocationSummary(location.account), 14, 0.7f));
                return row;
            }
        };

        ListView listView = new ListView(context);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setOnItemClickListener((parent, view, position, id) ->
                showLocationDialog(context, locations.get(position), () -> {
                    // Deleted options of an account that is no longer logged in remove its row.
                    locations.clear();
                    locations.addAll(getLocations());
                    adapter.notifyDataSetChanged();
                    refreshSummary();
                }));

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_download_location_title"),
                null,
                null,
                null,
                () -> {},
                null,
                null,
                null,
                false
        );
        LinearLayout mainLayout = dialogPair.second;
        mainLayout.addView(listView, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        dialogPair.first.show();
    }

    private static void showLocationDialog(Context context, Location location, Runnable onSaved) {
        String account = location.account;
        final boolean hasOwnOptions = account != null && DownloadLocationPatch.hasAccountOptions(account);

        // An account without options starts with the options of all accounts.
        String initialFolder = hasOwnOptions
                ? DownloadLocationPatch.getAccountOptionValue(Option.FOLDER, account)
                : Settings.DOWNLOAD_FOLDER.get().trim();
        String initialPattern = hasOwnOptions
                ? DownloadLocationPatch.getAccountOptionValue(Option.PATH_PATTERN, account)
                : Settings.DOWNLOAD_PATH_PATTERN.get().trim();
        String exampleAccount = account != null ? account : DownloadLocationPatch.getActiveAccount();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        Switch ownLocation = new Switch(context);
        ownLocation.setText(str("morphe_download_location_own_title"));
        ownLocation.setTextColor(ThemeUtils.getAppForegroundColor());
        ownLocation.setChecked(account != null && DownloadLocationPatch.isAccountEnabled(account));
        if (account != null) {
            content.addView(ownLocation);
        }

        LinearLayout optionsLayout = new LinearLayout(context);
        optionsLayout.setOrientation(LinearLayout.VERTICAL);
        content.addView(optionsLayout);

        // An empty root folder is Reddit's default folders.
        String[] rootFolders = new String[DownloadPathPattern.ROOT_FOLDERS.length + 1];
        rootFolders[0] = "";
        System.arraycopy(DownloadPathPattern.ROOT_FOLDERS, 0, rootFolders, 1, DownloadPathPattern.ROOT_FOLDERS.length);
        final String[] rootFolder = {""};
        // A folder picked outside the root folders, which replaces the root folder and subfolder.
        final String[] tree = {null};
        EditText subfolderInput = CustomDialog.createEditText(context);
        subfolderInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        subfolderInput.setSingleLine(true);
        subfolderInput.setHint(str("morphe_download_location_subfolder_hint"));
        Consumer<String> setFolder = folder -> {
            tree[0] = DocumentTree.isTree(folder) ? folder : null;
            String normalized = folder.isEmpty() || tree[0] != null ? null : DownloadPathPattern.normalizeFolder(folder);
            if (normalized == null) {
                rootFolder[0] = "";
                subfolderInput.setText("");
                return;
            }
            final int separatorIndex = normalized.indexOf('/');
            rootFolder[0] = separatorIndex < 0 ? normalized : normalized.substring(0, separatorIndex);
            subfolderInput.setText(separatorIndex < 0 ? "" : normalized.substring(separatorIndex + 1));
        };

        optionsLayout.addView(createLabel(context, str("morphe_download_location_folder_title")));
        LinearLayout rootChips = new LinearLayout(context);
        rootChips.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] rootChipViews = new TextView[rootFolders.length];
        for (int i = 0; i < rootFolders.length; i++) {
            rootChipViews[i] = createChip(context, rootFolders[i].isEmpty()
                    ? str("morphe_download_location_folder_default_short")
                    : rootFolders[i]);
            rootChips.addView(rootChipViews[i]);
        }
        TextView treeChip = createChip(context, "");
        rootChips.addView(treeChip);
        HorizontalScrollView rootChipsScroll = new HorizontalScrollView(context);
        rootChipsScroll.setHorizontalScrollBarEnabled(false);
        rootChipsScroll.addView(rootChips);
        optionsLayout.addView(rootChipsScroll);

        TextView browse = createChip(context, str("morphe_download_location_browse"));
        LinearLayout subfolderRow = new LinearLayout(context);
        subfolderRow.setOrientation(LinearLayout.HORIZONTAL);
        subfolderRow.setGravity(Gravity.CENTER_VERTICAL);
        subfolderRow.setPadding(0, Dim.dp8, 0, 0);
        subfolderRow.addView(subfolderInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        subfolderRow.addView(browse);
        optionsLayout.addView(subfolderRow);

        optionsLayout.addView(createLabel(context, str("morphe_download_location_pattern_title")));
        EditText patternInput = CustomDialog.createEditText(context);
        patternInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        patternInput.setSingleLine(true);
        patternInput.setHint(str("morphe_download_location_pattern_hint"));
        patternInput.setText(initialPattern);
        patternInput.setSelection(patternInput.getText().length());
        optionsLayout.addView(patternInput);

        LinearLayout tokens = new LinearLayout(context);
        tokens.setOrientation(LinearLayout.HORIZONTAL);
        tokens.setPadding(0, Dim.dp8, 0, Dim.dp4);
        for (String token : DownloadPathPattern.TOKENS) {
            TextView chip = createChip(context, token);
            chip.setOnClickListener(view -> {
                final int start = Math.max(patternInput.getSelectionStart(), 0);
                final int end = Math.max(patternInput.getSelectionEnd(), 0);
                patternInput.getText().replace(Math.min(start, end), Math.max(start, end), token);
            });
            tokens.addView(chip);
        }
        HorizontalScrollView tokensScroll = new HorizontalScrollView(context);
        tokensScroll.setHorizontalScrollBarEnabled(false);
        tokensScroll.addView(tokens);
        optionsLayout.addView(tokensScroll);
        optionsLayout.addView(createText(context, str("morphe_download_location_pattern_help"), 13, 0.7f));

        TextView example = createText(context, "", 13, 0.7f);
        example.setPadding(0, Dim.dp16, 0, 0);
        content.addView(example);

        Runnable update = () -> {
            for (int i = 0; i < rootFolders.length; i++) {
                setChipSelected(rootChipViews[i], tree[0] == null && rootFolders[i].equals(rootFolder[0]));
            }
            treeChip.setVisibility(tree[0] == null ? View.GONE : View.VISIBLE);
            if (tree[0] != null) {
                treeChip.setText(DocumentTree.getLabel(tree[0]));
                setChipSelected(treeChip, true);
            }

            final boolean editable = account == null || ownLocation.isChecked();
            optionsLayout.setAlpha(editable ? 1 : 0.5f);
            setEnabledRecursive(optionsLayout, editable);
            subfolderInput.setEnabled(editable && tree[0] == null && !rootFolder[0].isEmpty());
            subfolderInput.setHint(str(tree[0] != null
                    ? "morphe_download_location_subfolder_tree_hint"
                    : rootFolder[0].isEmpty()
                    ? "morphe_download_location_folder_default"
                    : "morphe_download_location_subfolder_hint"));

            String folder = tree[0] != null ? tree[0] : getFolder(rootFolder[0], subfolderInput.getText().toString());
            String pattern = patternInput.getText().toString();
            if (!editable) {
                folder = Settings.DOWNLOAD_FOLDER.get();
                pattern = Settings.DOWNLOAD_PATH_PATTERN.get();
            } else if (account != null) {
                // Empty account values use the values of all accounts.
                if (folder.isEmpty()) folder = Settings.DOWNLOAD_FOLDER.get();
                if (pattern.trim().isEmpty()) pattern = Settings.DOWNLOAD_PATH_PATTERN.get();
            }
            String imagePath = DownloadLocationPatch.getExamplePath(folder, pattern, exampleAccount, false);
            String videoPath = DownloadLocationPatch.getExamplePath(folder, pattern, exampleAccount, true);
            // noinspection SetTextI18n
            example.setText(str("morphe_download_location_example_images", imagePath)
                    + '\n' + str("morphe_download_location_example_videos", videoPath));
        };

        setFolder.accept(initialFolder);
        update.run();

        // Kept while Default is selected, so the field can show that Reddit's folders are used.
        final String[] hiddenSubfolder = {""};
        for (int i = 0; i < rootFolders.length; i++) {
            String root = rootFolders[i];
            rootChipViews[i].setOnClickListener(view -> {
                tree[0] = null;
                if (root.isEmpty() && !rootFolder[0].isEmpty()) {
                    hiddenSubfolder[0] = subfolderInput.getText().toString();
                    subfolderInput.setText("");
                } else if (!root.isEmpty() && rootFolder[0].isEmpty()) {
                    subfolderInput.setText(hiddenSubfolder[0]);
                }
                rootFolder[0] = root;
                update.run();
            });
        }
        ownLocation.setOnCheckedChangeListener((buttonView, isChecked) -> update.run());
        TextWatcher updateOnChange = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                update.run();
            }
        };
        subfolderInput.addTextChangedListener(updateOnChange);
        patternInput.addTextChangedListener(updateOnChange);
        Consumer<String> onFolderPicked = pickedFolder -> {
            setFolder.accept(pickedFolder);
            update.run();
        };
        browse.setOnClickListener(view -> openPicker(
                tree[0] != null || rootFolder[0].isEmpty() ? Environment.DIRECTORY_DOWNLOADS
                        : getFolder(rootFolder[0], subfolderInput.getText().toString()),
                onFolderPicked));

        final Dialog[] locationDialog = {null};
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                location.label,
                null,
                null,
                str("morphe_download_location_save"),
                () -> {
                    save(account, tree[0] != null ? tree[0]
                                    : getFolder(rootFolder[0], subfolderInput.getText().toString()),
                            patternInput.getText().toString().trim(), ownLocation.isChecked(), hasOwnOptions);
                    releaseUnusedFolderPermissions();
                    onSaved.run();
                },
                () -> {},
                str(account == null ? "morphe_download_location_reset" : "morphe_download_location_delete"),
                () -> CustomDialog.create(
                        context,
                        str(account == null ? "morphe_download_location_reset" : "morphe_download_location_delete"),
                        str(account == null
                                ? "morphe_download_location_reset_confirm"
                                : "morphe_download_location_delete_confirm", location.label),
                        null,
                        null,
                        () -> {
                            resetOrDelete(account);
                            releaseUnusedFolderPermissions();
                            onSaved.run();
                            locationDialog[0].dismiss();
                        },
                        () -> {},
                        null,
                        null,
                        false
                ).first.show(),
                false
        );
        locationDialog[0] = dialogPair.first;

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(content);
        LinearLayout mainLayout = dialogPair.second;
        mainLayout.addView(scrollView, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        dialogPair.first.setOnDismissListener(dialog -> {
            if (pendingFolderPick.get() == onFolderPicked) {
                pendingFolderPick = new WeakReference<>(null);
            }
            releaseUnusedFolderPermissions();
        });
        dialogPair.first.show();
    }

    private static void releaseUnusedFolderPermissions() {
        try {
            List<String> folders = new ArrayList<>();
            folders.add(Settings.DOWNLOAD_FOLDER.get());
            for (String account : DownloadLocationPatch.getAccountsWithOptions()) {
                folders.add(DownloadLocationPatch.getAccountOptionValue(Option.FOLDER, account));
            }
            DocumentTree.releaseUnusedPermissions(folders);
        } catch (Exception ex) {
            Logger.printException(() -> "releaseUnusedFolderPermissions failure", ex);
        }
    }

    /**
     * @return The folder setting, or an empty string for Reddit's default folders.
     */
    private static String getFolder(String rootFolder, String subfolder) {
        if (rootFolder.isEmpty()) return "";
        String folder = DownloadPathPattern.normalizeFolder(rootFolder + "/" + subfolder);
        return folder != null ? folder : rootFolder;
    }

    private static void save(@Nullable String account, String folder, String pattern,
                             boolean ownLocation, boolean hadOwnOptions) {
        if (account == null) {
            Settings.DOWNLOAD_FOLDER.save(folder);
            Settings.DOWNLOAD_PATH_PATTERN.save(pattern);
        } else if (ownLocation || hadOwnOptions) {
            DownloadLocationPatch.setAccountOptions(account, ownLocation, folder, pattern);
        }
    }

    private static void resetOrDelete(@Nullable String account) {
        if (account == null) {
            Settings.DOWNLOAD_FOLDER.save("");
            Settings.DOWNLOAD_PATH_PATTERN.save("");
        } else {
            DownloadLocationPatch.deleteAccountOptions(account);
        }
    }

    private static TextView createText(Context context, String text, float sizeSp, float alpha) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(ThemeUtils.getAppForegroundColor());
        view.setAlpha(alpha);
        return view;
    }

    private static TextView createLabel(Context context, String text) {
        TextView label = createText(context, text, 16, 1);
        label.setPadding(0, Dim.dp16, 0, Dim.dp4);
        return label;
    }

    private static TextView createChip(Context context, String text) {
        Button chip = CustomDialog.createButton(context, null, text, null, false, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Dim.dp36);
        params.setMarginEnd(Dim.dp8);
        chip.setLayoutParams(params);
        return chip;
    }

    /**
     * Uses the style of the OK button for the selected chip.
     */
    private static void setChipSelected(TextView chip, boolean selected) {
        chip.setBackground(CustomDialog.createRoundedBackground(20, selected
                ? ThemeUtils.getOkButtonBackgroundColor()
                : ThemeUtils.getCancelOrNeutralButtonBackgroundColor()));
        chip.setTextColor(Utils.isDarkModeEnabled() == selected ? Color.BLACK : Color.WHITE);
    }

    private static void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledRecursive(group.getChildAt(i), enabled);
            }
        }
    }

    private static void openPicker(String initialFolder, Consumer<String> onFolderPicked) {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null) {
            Logger.printException(() -> "No settings fragment to pick a folder from");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                EXTERNAL_STORAGE_AUTHORITY, PRIMARY_VOLUME_PREFIX + initialFolder));

        try {
            pendingFolderPick = new WeakReference<>(onFolderPicked);
            fragment.startActivityForResult(intent, PICK_FOLDER_REQUEST_CODE);
        } catch (ActivityNotFoundException ex) {
            // Some devices have no folder picker.
            Logger.printException(() -> "No folder picker", ex);
            Utils.showToastLong(str("morphe_download_location_no_picker"));
        }
    }

    public static void handleActivityResult(int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;

        Consumer<String> onFolderPicked = pendingFolderPick.get();
        Uri uri = data.getData();
        if (onFolderPicked == null || uri == null) return;

        try {
            // Folders the media store can save to are kept as a path, so no permission is needed for them.
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            String folder = EXTERNAL_STORAGE_AUTHORITY.equals(uri.getAuthority())
                    && documentId.startsWith(PRIMARY_VOLUME_PREFIX)
                    ? DownloadPathPattern.normalizeFolder(documentId.substring(PRIMARY_VOLUME_PREFIX.length()))
                    : null;

            if (folder == null) {
                // The permission is released when the dialog is cancelled or the folder is no longer used.
                DocumentTree.takePermission(uri);
                folder = uri.toString();
            }

            onFolderPicked.accept(folder);
        } catch (Exception ex) {
            Logger.printException(() -> "handleActivityResult failure", ex);
        }
    }
}
