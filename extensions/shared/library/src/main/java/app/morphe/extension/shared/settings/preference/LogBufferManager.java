/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Pair;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Manages a buffer for storing debug logs from {@link Logger}.
 * Stores just under 1MB of the most recent log data.
 * <p>
 * All methods are thread-safe.
 */
public final class LogBufferManager {
    /** Maximum byte size of all buffer entries. Must be less than Android's 1 MB Binder transaction limit. */
    private static final int BUFFER_MAX_BYTES = 900_000;
    /** Limit number of log lines. */
    private static final int BUFFER_MAX_SIZE = 10_000;

    /** Used for the native file picker routing. */
    public static final int WRITE_LOGS_REQUEST_CODE = 44;
    private static String pendingLogsToExport = null;

    private static final Deque<String> logBuffer = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger logBufferByteSize = new AtomicInteger();

    private static String getFilteredLogs() {
        StringBuilder filteredOutput = new StringBuilder(logBufferByteSize.get());

        for (String log : logBuffer) {
            filteredOutput.append(log).append('\n');
        }

        return filteredOutput.toString().trim();
    }

    public static void appendToLogBuffer(String message) {
        Objects.requireNonNull(message);

        // It's very important that no Settings are used in this method,
        // as this code is used when a context is not set and thus referencing
        // a setting will crash the app.
        logBuffer.addLast(message);
        int newSize = logBufferByteSize.addAndGet(message.length());

        // Remove the oldest entries if over the log size limits.
        while (newSize > BUFFER_MAX_BYTES || logBuffer.size() > BUFFER_MAX_SIZE) {
            String removed = logBuffer.pollFirst();
            if (removed == null) {
                // Thread race of two different calls to this method, and the other thread won.
                return;
            }

            newSize = logBufferByteSize.addAndGet(-removed.length());
        }
    }

    /**
     * Exports all logs from the internal buffer to the clipboard.
     * Displays a toast with the result.
     */
    private static void exportToClipboard(String logsToExport) {
        try {
            if (!BaseSettings.DEBUG.get()) {
                Utils.showToastShort(str("morphe_debug_logs_disabled"));
                return;
            }

            if (logsToExport == null || logsToExport.isBlank()) {
                Utils.showToastShort(str("morphe_debug_logs_none_found"));
                return;
            }

            // Most (but not all) Android 13+ devices always show a "copied to clipboard" toast
            // and there is no way to programmatically detect if a toast will show or not.
            // Show a toast even if using Android 13+, but show Morphe toast first (before copying to clipboard).
            Utils.showToastShort(str("morphe_debug_logs_copied_to_clipboard"));
            Utils.setClipboard(logsToExport);

        } catch (Exception ex) {
            // Handle security exception if clipboard access is denied.
            String errorMessage = String.format(str("morphe_debug_logs_failed_to_export"), ex.getMessage());
            Utils.showToastLong(errorMessage);
            Logger.printDebug(() -> errorMessage, ex);
        }
    }

    /**
     * Triggers the native Android file picker to save the logs.
     */
    @SuppressWarnings("deprecation")
    private static void exportToFile(Context context, String logsToExport) {
        try {
            if (!BaseSettings.DEBUG.get() || logsToExport == null || logsToExport.trim().isEmpty()) {
                Utils.showToastShort(str("morphe_debug_logs_none_found"));
                return;
            }

            pendingLogsToExport = logsToExport;

            String appName = Utils.getApplicationName();
            String safeAppName = appName.replaceAll("\\s+", "_");

            String formatDate = new SimpleDateFormat(
                    "yyyy-MM-dd", Locale.US).format(new Date());
            String fileName = safeAppName + "_Debug_Logs_" + formatDate + ".txt";

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, fileName);

            if (AbstractPreferenceFragment.instance != null) {
                AbstractPreferenceFragment.instance.startActivityForResult(intent, WRITE_LOGS_REQUEST_CODE);
            } else if (context instanceof Activity) {
                ((Activity) context).startActivityForResult(intent, WRITE_LOGS_REQUEST_CODE);
            } else {
                Utils.showToastShort("Cannot open file manager from this context.");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to launch file picker", ex);
        }
    }

    /**
     * Called from AbstractPreferenceFragment after the user picks a save location.
     */
    public static void saveLogsToUri(Context context, Uri uri) {
        if (pendingLogsToExport == null) return;

        try {
            try (OutputStream out = context.getContentResolver().openOutputStream(uri, "rwt")) {
                if (out != null) {
                    out.write(pendingLogsToExport.getBytes(StandardCharsets.UTF_8));
                }
            }

            String messageKey = "morphe_debug_export_logs_success";
            Utils.showToastLong(
                    ResourceUtils.getIdentifier(ResourceType.STRING, messageKey) != 0
                            ? str(messageKey)
                            : "Debug logs exported successfully");
        } catch (Exception e) {
            Utils.showToastLong("Failed to export debug logs");
            Logger.printException(() -> "saveLogsToUri failure", e);
        } finally {
            pendingLogsToExport = null;
        }
    }

    private static void clearLogBufferData() {
        // Cannot simply clear the log buffer because there is no
        // write lock for both the deque and the atomic int.
        // Instead, pop off log entries and decrement the size one by one.
        while (!logBuffer.isEmpty()) {
            String removed = logBuffer.pollFirst();
            if (removed != null) {
                logBufferByteSize.addAndGet(-removed.length());
            }
        }
    }

    /**
     * Clears the internal log buffer and displays a toast with the result.
     */
    public static void clearLogBuffer() {
        if (!BaseSettings.DEBUG.get()) {
            Utils.showToastShort(str("morphe_debug_logs_disabled"));
            return;
        }

        // Show toast before clearing, otherwise toast log will still remain.
        Utils.showToastShort(str("morphe_debug_logs_clear_toast"));
        clearLogBufferData();
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void showLogDialog(Context context) {
        try {
            if (!BaseSettings.DEBUG.get()) {
                Utils.showToastShort(str("morphe_debug_logs_disabled"));
                return;
            }

            if (logBuffer.isEmpty()) {
                Utils.showToastShort(str("morphe_debug_logs_none_found"));
                clearLogBufferData();
                return;
            }

            final String allLogs = getFilteredLogs();
            final String[] logLines = allLogs.split("\n");

            EditText logViewer = createLogEditText(context, allLogs);

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_debug_export_logs_title"),
                    null,
                    logViewer,
                    str("morphe_settings_import_copy"),
                    () -> exportToClipboard(logViewer.getText().toString()),
                    () -> {},
                    str("morphe_debug_export_logs_clear"),
                    LogBufferManager::clearLogBuffer,
                    true
            );

            int margin = Dim.dp(16);

            EditText searchBar = new EditText(context);
            searchBar.setTextSize(16);
            searchBar.setHint(str("morphe_debug_logs_search_hint"));
            searchBar.setSingleLine(true);
            searchBar.setHapticFeedbackEnabled(false);

            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            searchParams.setMargins(0, 0, 0, margin);
            searchBar.setLayoutParams(searchParams);

            searchBar.addTextChangedListener(new TextWatcher() {
                final Handler searchHandler = new Handler(Looper.getMainLooper());
                Runnable searchRunnable;

                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    final String query = s.toString().toLowerCase(Locale.ROOT);

                    Drawable clearIcon = context.getDrawable(android.R.drawable.ic_menu_close_clear_cancel);
                    if (clearIcon != null) {
                        final int iconSize = Dim.dp20;
                        clearIcon.setBounds(0, 0, iconSize, iconSize);
                    }
                    searchBar.setCompoundDrawables(null, null,
                            TextUtils.isEmpty(s) ? null : clearIcon, null);

                    if (searchRunnable != null) {
                        searchHandler.removeCallbacks(searchRunnable);
                    }

                    searchRunnable = () -> new Thread(() -> {
                        final CharSequence resultText;
                        if (query.isEmpty()) {
                            resultText = allLogs;
                        } else {
                            SpannableStringBuilder ssb = new SpannableStringBuilder();

                            for (String line : logLines) {
                                String lowerLine = line.toLowerCase(Locale.ROOT);

                                if (lowerLine.contains(query)) {
                                    final int startOffset = ssb.length();
                                    ssb.append(line).append('\n');

                                    int index = lowerLine.indexOf(query);
                                    while (index >= 0) {
                                        final int matchStart = startOffset + index;
                                        final int matchEnd = matchStart + query.length();

                                        ssb.setSpan(new BackgroundColorSpan(Color.LTGRAY),
                                                matchStart, matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                        ssb.setSpan(new ForegroundColorSpan(Color.BLACK),
                                                matchStart, matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                                        index = lowerLine.indexOf(query, index + query.length());
                                    }
                                }
                            }

                            if (ssb.length() > 0) {
                                ssb.delete(ssb.length() - 1, ssb.length());
                            }
                            resultText = ssb;
                        }

                        searchHandler.post(() -> logViewer.setText(resultText));
                    }).start();

                    searchHandler.postDelayed(searchRunnable, 300);
                }
                @Override public void afterTextChanged(Editable s) {}
            });

            searchBar.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    Drawable[] compoundDrawables = searchBar.getCompoundDrawables();
                    if (compoundDrawables[2] != null && event.getRawX() >= (searchBar.getRight() - compoundDrawables[2].getBounds().width())) {
                        searchBar.setText("");
                        return true;
                    }
                }
                return false;
            });

            LinearLayout fileButtonsContainer = new LinearLayout(context);
            fileButtonsContainer.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams fbParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            fbParams.setMargins(0, margin, 0, 0);
            fileButtonsContainer.setLayoutParams(fbParams);

            Button btnExport = CustomDialog.createButton(context, dialogPair.first,
                    str("morphe_debug_export_logs_file"),
                    () -> exportToFile(context, logViewer.getText().toString()),
                    false, true);
            LinearLayout.LayoutParams btnExportParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
            btnExport.setLayoutParams(btnExportParams);
            fileButtonsContainer.addView(btnExport);
            LinearLayout mainLayout = dialogPair.second;
            mainLayout.addView(searchBar, 1);
            mainLayout.addView(fileButtonsContainer, 3);
            dialogPair.first.show();

        } catch (Exception ex) {
            Logger.printException(() -> "showLogDialog failure", ex);
        }
    }

    @NonNull
    private static EditText createLogEditText(Context context, String logs) {
        EditText editText = new EditText(context);
        editText.setText(logs);
        editText.setTextIsSelectable(true);
        editText.setFocusable(false);
        editText.setFocusableInTouchMode(false);
        editText.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setSingleLine(false);
        editText.setTextSize(12);
        return editText;
    }
}
