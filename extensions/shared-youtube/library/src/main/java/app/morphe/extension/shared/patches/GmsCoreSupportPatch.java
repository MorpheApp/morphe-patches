/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.GMS_CORE_IGNORED_CONFLICTS;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.GMS_CORE_IGNORED_VERSION;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Pair;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.shared.settings.SharedSettings;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings("unused")
public class GmsCoreSupportPatch {
    private static final String GMS_CORE_PACKAGE_NAME
            = getGmsCoreVendorGroupId() + ".android.gms";
    private static final Uri GMS_CORE_PROVIDER
            = Uri.parse("content://" + getGmsCoreVendorGroupId() + ".android.gsf.gservices/prefix");
    private static final String DONT_KILL_MY_APP_URL
            = "https://dontkillmyapp.com/";
    private static final Route DONT_KILL_MY_APP_MANUFACTURER_API
            = new Route(Route.Method.GET, "/api/v2/{manufacturer}.json");
    private static final String DONT_KILL_MY_APP_NAME_PARAMETER
            = "?app=MicroG";
    private static final String BUILD_MANUFACTURER
            = Build.MANUFACTURER.toLowerCase(Locale.ROOT).replace(" ", "-");

    /**
     * GitHub API URL for the latest stable MicroG-RE release.
     * The /releases/latest endpoint only returns non-prerelease, non-draft releases.
     */
    private static final String MICROG_LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/MorpheApp/MicroG-RE/releases/latest";

    /**
     * SHA-256 digest of the signing certificate used by official MicroG-RE releases.
     * Verified against the release APKs (e.g. 7.1.0 and 7.1.1).
     */
    private static final String OFFICIAL_MICROG_SIGNING_CERT_SHA256 =
            "0b6c9515afb195fac59601696ba0a7907a0b217ccf720b43148427ccf64343e7";

    /**
     * Other package names that may hold a different MicroG install, which prevents MicroG-RE
     * from being installed or working correctly.
     * <p>
     * Google Play services is deliberately not checked: only a rooted or signature spoofing
     * setup can install another MicroG under that name, and genuine Google Play services
     * coexists with MicroG-RE without any problem.
     */
    private static final String[] CONFLICTING_GMS_PACKAGE_NAMES = {
            "com.mgoogle.android.gms",  // Older MicroG spoof builds.
            "org.microg.gms",           // Alternate MicroG package name.
    };

    /**
     * Platform extra (hidden from the public SDK) that makes the system uninstaller remove the
     * package for every user instead of only the current one.
     */
    private static final String EXTRA_UNINSTALL_ALL_USERS =
            "android.intent.extra.UNINSTALL_ALL_USERS";

    /**
     * Delay between two uninstall requests, so the system can show each of them.
     */
    private static final long UNINSTALL_PROMPT_DELAY_MILLIS = 1500;

    /**
     * If a manufacturer specific page exists on DontKillMyApp.
     */
    @Nullable
    private static volatile Boolean DONT_KILL_MY_APP_MANUFACTURER_SUPPORTED;

    private static String getOriginalPackageName() {
        return null; // Modified during patching.
    }

    /**
     * @return If the current package name is the same as the original unpatched app.
     *         If `GmsCore support` was not included during patching, this returns true;
     */
    public static boolean isPackageNameOriginal() {
        String originalPackageName = getOriginalPackageName();
        return originalPackageName == null
                || originalPackageName.equals(Utils.getContext().getPackageName());
    }

    private static void open(Activity context, String queryOrLink) {
        Logger.printInfo(() -> "Opening link: " + queryOrLink);

        Intent intent;
        try {
            // Check if queryOrLink is a valid URL.
            new URL(queryOrLink);

            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(queryOrLink));
        } catch (MalformedURLException e) {
            intent = new Intent(Intent.ACTION_WEB_SEARCH);
            intent.putExtra(SearchManager.QUERY, queryOrLink);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        // Gracefully exit, otherwise the broken app will continue to run.
        System.exit(0);
    }

    private static void showBatteryOptimizationDialog(Activity context,
                                                      String dialogMessageRef,
                                                      String positiveButtonTextRef,
                                                      DialogInterface.OnClickListener onPositiveClickListener) {
        // Use a delay to allow the activity to finish initializing.
        // Otherwise, if device is in dark mode the dialog is shown with wrong color scheme.
        Utils.runOnMainThreadDelayed(() -> {
            // Create the custom dialog.
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("gms_core_dialog_title"), // Title.
                    str(dialogMessageRef), // Message.
                    null, // No EditText.
                    str(positiveButtonTextRef), // OK button text.
                    () -> onPositiveClickListener.onClick(null, 0), // Convert DialogInterface.OnClickListener to Runnable.
                    null, // No Cancel button action.
                    null, // No Neutral button text.
                    null, // No Neutral button action.
                    true // Dismiss dialog when onNeutralClick.
            );

            Dialog dialog = dialogPair.first;

            // Do not set cancelable too false to allow using back button to skip the action,
            // just in case the battery change can never be satisfied.
            dialog.setCancelable(true);

            // Show the dialog
            Utils.showDialog(context, dialog);
        }, 100);
    }

    /**
     * Injection point.
     */
    public static void checkGmsCore(Activity context) {
        try {
            // Verify the user has not included GmsCore for a root installation.
            // GmsCore Support changes the package name, but with a mounted installation
            // all manifest changes are ignored and the original package name is used.
            // Must check both original package name and if resources load correctly
            // to allow changing to original package name with YT Music.
            if (isPackageNameOriginal() && ResourceUtils.getIdentifier(
                    ResourceType.STRING, "gms_core_dialog_title") == 0) {
                Logger.printInfo(() -> "App is mounted with root, but GmsCore patch was included");
                // Cannot use localize text here, since the app will load resources
                // from the unpatched app and all patch strings are missing.
                Utils.showToastLong("Do not include 'GmsCore support' patch with root install");

                // Do not exit. If the app exits before launch completes (and without
                // opening another activity), then on some devices such as Pixel phone Android 10
                // no toast will be shown and the app will continually relaunch
                // with the appearance of a hung app.
                open(context, "https://morphe.software");
                return;
            }

            // Find every MicroG install and verify the installed MicroG-RE is signed by the
            // official key, since an install signed by another key cannot be updated.
            List<VariantPackage> variants = findMicroGVariants(context);
            List<VariantPackage> conflicts = new ArrayList<>();
            boolean officialGmsCoreInstalled = false;
            for (VariantPackage variant : variants) {
                if (variant.officialMicroG) {
                    if (variant.userId == currentUserId()) {
                        officialGmsCoreInstalled = true;
                    }
                } else {
                    conflicts.add(variant);
                }
            }

            // A conflicting install (MicroG-RE signed by a different key, or another MicroG
            // under a different package name) prevents MicroG-RE from being installed or
            // working, and should be removed first. The user can also keep using the app with
            // the conflicting installs, which is remembered until they change.
            boolean conflictsIgnored = false;
            if (!conflicts.isEmpty()) {
                final String conflictsSignature = conflictSignature(conflicts);
                conflictsIgnored = conflictsSignature.equals(GMS_CORE_IGNORED_CONFLICTS.get());

                if (!conflictsIgnored) {
                    showMicroGConflictDialog(context, conflicts, conflictsSignature);
                    return;
                }
            }

            // Verify MicroG-RE is installed. Conflicting installs that were ignored are
            // accepted as well, since they still provide GmsCore.
            if (!officialGmsCoreInstalled && !conflictsIgnored) {
                Logger.printInfo(() -> "GmsCore was not found");

                // Ask to uninstall only MicroG packages that are installed for any user,
                // so we don't trigger "App not found" warnings for uninstalled packages.
                List<String> installedPackageNames = new ArrayList<>();
                for (VariantPackage variant : variants) {
                    if (!installedPackageNames.contains(variant.packageName)) {
                        installedPackageNames.add(variant.packageName);
                    }
                }

                for (int i = installedPackageNames.size() - 1; i >= 0; i--) {
                    uninstallForAllUsers(context, installedPackageNames.get(i));
                }

                // Cannot show a dialog and must show a toast,
                // because on some installations the app crashes before a dialog can be displayed.
                Utils.showToastLong(str("gms_core_toast_not_installed_message"));

                // The download page is opened only after every confirmation dialog was sent to the
                // user. Opening it earlier would put the page in front of the dialogs and leave the
                // uninstall requests unconfirmed.
                Utils.runOnMainThreadDelayed(() -> open(context, getGmsCoreDownload()),
                        UNINSTALL_PROMPT_DELAY_MILLIS * (installedPackageNames.size() + 1));
                return;
            }

            // Check if GmsCore is outdated. An install signed by a different key cannot be
            // updated by installing a MicroG-RE release, so it is not asked to update.
            if (officialGmsCoreInstalled) {
                checkForMicroGUpdate(context);
            }

            // Check if GmsCore is whitelisted from battery optimizations.
            if (isAndroidAutomotive(context)) {
                // Ignore Android Automotive devices (Google built-in),
                // as there is no way to disable battery optimizations.
                Logger.printDebug(() -> "Device is Android Automotive");
            } else if (batteryOptimizationsEnabled(context)) {
                Logger.printInfo(() -> "GmsCore is not whitelisted from battery optimizations");

                if (SharedSettings.GMS_CORE_BATTERY_OPTIMIZATION_DIALOG.get()) {
                    showBatteryOptimizationDialog(context,
                            "gms_core_dialog_not_whitelisted_using_battery_optimizations_message",
                            "gms_core_dialog_continue_text",
                            (dialog, id) -> openGmsCoreDisableBatteryOptimizationsIntent(context));
                    return;
                }
            }

            // Check if GmsCore is currently running in the background.
            try (var client = context.getContentResolver().acquireContentProviderClient(GMS_CORE_PROVIDER)) {
                if (client == null) {
                    Logger.printInfo(() -> "GmsCore is not running in the background");

                    if (SharedSettings.GMS_CORE_BATTERY_OPTIMIZATION_DIALOG.get()) {
                        checkIfDontKillMyAppSupportsManufacturer();

                        showBatteryOptimizationDialog(context,
                                "gms_core_dialog_not_whitelisted_not_allowed_in_background_message",
                                "gms_core_dialog_open_website_text",
                                (dialog, id) -> openDontKillMyApp(context));
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "checkGmsCore failure", ex);
        }
    }

    /**
     * Finds every MicroG / Google Play services install that could conflict with MicroG-RE.
     * The current user is always scanned, and so is every profile of it the app is allowed to
     * query (such as the work profile).
     */
    private static List<VariantPackage> findMicroGVariants(Context context) {
        List<VariantPackage> variants = new ArrayList<>();
        final int currentUserId = currentUserId();

        scanUser(context, currentUserId, null, variants);

        try {
            // noinspection WrongConstant
            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (userManager != null) {
                List<UserHandle> profiles = userManager.getUserProfiles();
                for (UserHandle handle : profiles) {
                    int userId = userHandleIdentifier(handle);
                    if (userId < 0 || userId == currentUserId) continue;
                    scanUser(context, userId, handle, variants);
                }
            }
        } catch (Exception ex) {
            // A profile that cannot be listed has no installs that can be found.
            Logger.printInfo(() -> "Could not check for MicroG variants", ex);
        }

        return variants;
    }

    /**
     * @return Every package name that can hold a MicroG install, without duplicates.
     */
    private static String[] getMicroGPackageNames() {
        List<String> packageNames = new ArrayList<>();
        addPackageName(packageNames, GMS_CORE_PACKAGE_NAME);
        for (String packageName : CONFLICTING_GMS_PACKAGE_NAMES) {
            addPackageName(packageNames, packageName);
        }
        return packageNames.toArray(new String[0]);
    }

    private static void addPackageName(List<String> packageNames, @Nullable String packageName) {
        if (packageName != null && !packageNames.contains(packageName)) {
            packageNames.add(packageName);
        }
    }

    /**
     * Records every candidate package that is installed for the given user.
     *
     * @param handle The user handle of that user, or null when it is not known.
     */
    private static void scanUser(Context context, int userId, @Nullable UserHandle handle,
                                 List<VariantPackage> variants) {
        for (String packageName : getMicroGPackageNames()) {
            PackageInfo info = getPackageInfoForUser(context, packageName, userId, handle);
            if (info == null) continue;

            final boolean officialMicroG = packageName.equals(GMS_CORE_PACKAGE_NAME)
                    && isOfficialMicroG(info);

            variants.add(new VariantPackage(packageName, userId, info.versionName, officialMicroG));
        }
    }

    /**
     * @return The user id of the current process.
     */
    private static int currentUserId() {
        return Process.myUid() / 100000;
    }

    /**
     * Queries a package for the given user. The current user is queried directly, other users are
     * queried through a context scoped to that user when the device allows it, and otherwise
     * through the hidden {@code PackageManager.getPackageInfoAsUser}.
     *
     * @return The package information, or null when the package is not installed for that user
     *         or when that user cannot be queried at all.
     */
    @Nullable
    private static PackageInfo getPackageInfoForUser(Context context, String packageName, int userId,
                                                     @Nullable UserHandle handle) {
        if (userId == currentUserId()) {
            try {
                // Direct call; available in every SDK.
                return context.getPackageManager().getPackageInfo(packageName, signatureFlags());
            } catch (PackageManager.NameNotFoundException ex) {
                return null;
            }
        }

        // Public API: use a context scoped to the other user.
        if (handle != null && CREATE_CONTEXT_AS_USER != null) {
            try {
                Context userContext = (Context) CREATE_CONTEXT_AS_USER.invoke(context, handle, 0);
                if (userContext != null) {
                    return userContext.getPackageManager()
                            .getPackageInfo(packageName, signatureFlags());
                }
            } catch (Exception ex) {
                if (ex.getCause() instanceof PackageManager.NameNotFoundException) return null;
            }
        }

        // Hidden API fallback, not available to every app.
        if (PACKAGE_MANAGER_GET_PACKAGE_INFO_AS_USER == null) {
            return null;
        }
        try {
            return (PackageInfo) PACKAGE_MANAGER_GET_PACKAGE_INFO_AS_USER.invoke(
                    context.getPackageManager(), packageName, signatureFlags(), userId);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * @return The id of the given user handle, or -1 if it cannot be resolved.
     */
    private static int userHandleIdentifier(UserHandle handle) {
        try {
            if (USER_HANDLE_GET_IDENTIFIER == null) return -1;
            return (int) USER_HANDLE_GET_IDENTIFIER.invoke(handle);
        } catch (Exception ex) {
            return -1;
        }
    }

    /**
     * Public API since API 24, but missing from some compile SDKs. Resolved lazily at runtime.
     */
    @Nullable
    private static final Method CREATE_CONTEXT_AS_USER = getCreateContextAsUserMethod();

    /**
     * Hidden API, resolved lazily at runtime and skipped when it is not available.
     */
    @Nullable
    private static final Method PACKAGE_MANAGER_GET_PACKAGE_INFO_AS_USER = getPackageInfoAsUserMethod();

    /**
     * Hidden API, resolved lazily at runtime and skipped when it is not available.
     */
    @Nullable
    private static final Method USER_HANDLE_GET_IDENTIFIER = getUserHandleIdentifierMethod();

    @Nullable
    private static Method getCreateContextAsUserMethod() {
        try {
            return Context.class.getMethod("createContextAsUser", UserHandle.class, int.class);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    @Nullable
    private static Method getPackageInfoAsUserMethod() {
        try {
            return PackageManager.class.getMethod(
                    "getPackageInfoAsUser", String.class, int.class, int.class);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    @Nullable
    private static Method getUserHandleIdentifierMethod() {
        try {
            return UserHandle.class.getMethod("getIdentifier");
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    private static int signatureFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return PackageManager.GET_SIGNING_CERTIFICATES;
        }
        return PackageManager.GET_SIGNATURES;
    }

    /**
     * @return If the package is signed by the official MicroG-RE signing key.
     */
    private static boolean isOfficialMicroG(PackageInfo packageInfo) {
        return matchesSigningCert(packageInfo, OFFICIAL_MICROG_SIGNING_CERT_SHA256);
    }

    /**
     * @return If any certificate of the package, including the certificates of a signing key
     *         rotation, matches the expected SHA-256 digest.
     */
    private static boolean matchesSigningCert(PackageInfo packageInfo, String expectedSha256) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (packageInfo.signingInfo == null) return false;
                if (matchesAnySigningCert(packageInfo.signingInfo.getApkContentsSigners(), expectedSha256)) {
                    return true;
                }
                // A rotated signing key keeps the old certificate in the history.
                return matchesAnySigningCert(packageInfo.signingInfo.getSigningCertificateHistory(),
                        expectedSha256);
            }
            return matchesAnySigningCert(packageInfo.signatures, expectedSha256);
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean matchesAnySigningCert(@Nullable Signature[] signatures, String expectedSha256) {
        if (signatures == null) return false;
        for (Signature signature : signatures) {
            if (expectedSha256.equalsIgnoreCase(sha256Hex(signature.toByteArray()))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * @return A value that identifies the conflicting installs, used to remember which installs
     *         the user chose to ignore. Installing or updating a MicroG variant changes it,
     *         which asks again.
     */
    private static String conflictSignature(List<VariantPackage> conflicts) {
        List<String> entries = new ArrayList<>();
        for (VariantPackage variant : conflicts) {
            entries.add(variant.packageName + "@" + variant.userId + "@" + variant.versionName);
        }
        Collections.sort(entries);

        StringBuilder signature = new StringBuilder();
        for (String entry : entries) {
            //noinspection SizeReplaceableByIsEmpty
            if (signature.length() > 0) signature.append(';');
            signature.append(entry);
        }
        return signature.toString();
    }

    /**
     * Shows a dialog listing every conflicting MicroG install, with an action to uninstall them
     * all before installing MicroG-RE, and an action to keep using the app as it is.
     */
    private static void showMicroGConflictDialog(Activity context, List<VariantPackage> conflicts,
                                                 String conflictsSignature) {
        // Use a delay to allow the activity to finish initializing.
        // Otherwise, if device is in dark mode the dialog is shown with wrong color scheme.
        Utils.runOnMainThreadDelayed(() -> {
            StringBuilder list = new StringBuilder();
            for (VariantPackage variant : conflicts) {
                //noinspection SizeReplaceableByIsEmpty
                if (list.length() > 0) list.append('\n');
                list.append(variant);
            }

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("gms_core_dialog_title"), // Title.
                    str("gms_core_dialog_conflict_message", list), // Message.
                    null, // No EditText.
                    str("gms_core_dialog_uninstall_text"), // Uninstall button text.
                    () -> uninstallConflictingMicroG(context, conflicts), // Uninstall action.
                    null, // No Cancel button.
                    str("gms_core_dialog_ignore_text"), // Ignore button text.
                    // Remember which installs were ignored, and only ask again when they change.
                    () -> GMS_CORE_IGNORED_CONFLICTS.save(conflictsSignature), // Ignore action.
                    true // Dismiss dialog when the Ignore button is clicked.
            );

            Dialog dialog = dialogPair.first;
            dialog.setCancelable(true);
            Utils.showDialog(context, dialog);
        }, 100);
    }

    /**
     * Starts the system uninstall flow for every conflicting package, spacing out the
     * confirmation prompts so each one can appear. Every copy of the package is removed,
     * including copies installed for other users. After all are removed, reopening the app
     * will prompt to install the official MicroG-RE.
     */
    private static void uninstallConflictingMicroG(Activity context, List<VariantPackage> conflicts) {
        // Ask to uninstall each package only once, even when it is installed for several users,
        // since uninstalling removes it for every user at once.
        List<String> packageNames = new ArrayList<>();
        for (VariantPackage variant : conflicts) {
            if (!packageNames.contains(variant.packageName)) {
                packageNames.add(variant.packageName);
            }
        }

        for (int i = 0, size = packageNames.size(); i < size; i++) {
            String packageName = packageNames.get(i);
            Utils.runOnMainThreadDelayed(() -> uninstallForAllUsers(context, packageName),
                    UNINSTALL_PROMPT_DELAY_MILLIS * i);
        }
    }

    /**
     * Asks the system uninstaller to remove the package for every user, since copies that are
     * installed for another user cannot be removed from here, but still keep MicroG-RE from being
     * installed or working.
     */
    private static void uninstallForAllUsers(Context context, String packageName) {
        try {
            //noinspection deprecation
            Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                    Uri.parse("package:" + packageName));
            intent.putExtra(EXTRA_UNINSTALL_ALL_USERS, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            // The system uninstaller is part of every device, so this cannot normally happen.
            Logger.printInfo(() -> "Could not uninstall: " + packageName, ex);
        }
    }

    /**
     * A MicroG / Google Play services install found on the device.
     *
     * @param officialMicroG True if this is MicroG-RE signed by the official key.
     */
    private record VariantPackage(String packageName, int userId, @Nullable String versionName,
                                  boolean officialMicroG) {

        @NonNull
        @Override
        public String toString() {
            // Uninstall removes the package for every user,
            // so the user it belongs to does not have to be stated here.
            return Utils.getTextDirectionString() + str("gms_core_dialog_conflict_entry",
                    (versionName == null) ? packageName : versionName);
        }
    }

    private static void showOutdatedMicroGDialog(Activity context, String installedVersion, String latestVersion) {
        // Use a delay to allow the activity to finish initializing.
        // Otherwise, if device is in dark mode the dialog is shown with wrong color scheme.
        Utils.runOnMainThreadDelayed(() -> {
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("gms_core_dialog_title"), // Title.
                    str("gms_core_dialog_outdated_message", installedVersion, latestVersion), // Message.
                    null, // No EditText.
                    str("gms_core_dialog_update_text"), // Update button text.
                    () -> open(context, getGmsCoreDownload()), // Update action.
                    null, // No Cancel button.
                    str("gms_core_dialog_ignore_text"), // Ignore button text.
                    () -> GMS_CORE_IGNORED_VERSION.save(latestVersion), // Ignore action: persist and dismiss.
                    true // Dismiss dialog when the Ignore button is clicked.
            );

            Dialog dialog = dialogPair.first;
            dialog.setCancelable(true);
            Utils.showDialog(context, dialog);
        }, 100);
    }

    /**
     * Compares the installed MicroG version against the latest stable version fetched from GitHub,
     * and shows the update dialog when the installed version is older. Runs in the background.
     */
    private static void checkForMicroGUpdate(Activity context) {
        Utils.runOnBackgroundThread(() -> {
            try {
                String installedVersionName = context.getPackageManager()
                        .getPackageInfo(GMS_CORE_PACKAGE_NAME, 0).versionName;
                if (installedVersionName == null || parseVersion(installedVersionName) == null) {
                    // Unknown installed version format, do not nag the user.
                    return;
                }

                String latestVersionName = fetchLatestMicroGVersion();
                if (latestVersionName == null || compareVersions(installedVersionName, latestVersionName) >= 0) {
                    // Version could not be fetched, or MicroG is already up to date.
                    return;
                }

                String ignoredVersion = GMS_CORE_IGNORED_VERSION.get();
                if (!ignoredVersion.isEmpty() && compareVersions(latestVersionName, ignoredVersion) <= 0) {
                    // The user already ignored this version (or a newer one), do not ask again.
                    return;
                }

                Utils.runOnMainThread(() -> showOutdatedMicroGDialog(context, installedVersionName, latestVersionName));
            } catch (Exception ex) {
                // MicroG keeps working, so a failed check is only informational.
                Logger.printInfo(() -> "Could not check for MicroG update", ex);
            }
        });
    }

    /**
     * @return The latest stable MicroG version name from the main branch of the MicroG-RE repository,
     *         or null if it could not be fetched or parsed.
     */
    @Nullable
    private static String fetchLatestMicroGVersion() throws IOException {
        HttpURLConnection connection = Requester.openConnection(MICROG_LATEST_RELEASE_API_URL);
        connection.setFixedLengthStreamingMode(0);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        final int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            return null;
        }
        try {
            // The latest release endpoint returns the newest stable (non-prerelease) tag.
            return Requester.parseJSONObjectAndDisconnect(connection).optString("tag_name", null);
        } catch (JSONException ex) {
            return null;
        }
    }

    /**
     * Compares two MicroG version names such as "7.1.0-dev.2".
     * @return A negative value if a is older than b, zero if equal, a positive value if newer.
     */
    private static int compareVersions(String a, String b) {
        int[] av = parseVersion(a);
        int[] bv = parseVersion(b);
        if (av == null || bv == null) return 0;
        // Intentionally only check major and minor version,
        // to avoid nagging the user about 0.0.x updates.
        final int groupsToCheck = 2;
        for (int i = 0; i < groupsToCheck; i++) {
            if (av[i] != bv[i]) return Integer.compare(av[i], bv[i]);
        }
        return 0;
    }

    /**
     * Parses a MicroG version name such as "7.1.0-dev.2" into its numeric parts.
     * Returns null if the version does not start with a parseable number.
     */
    @Nullable
    private static int[] parseVersion(String versionName) {
        if (versionName == null) return null;
        String[] parts = versionName.split("-")[0].split("\\.");
        int[] parsed = new int[3];
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                parsed[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                if (i == 0) return null;
                break;
            }
        }
        return parsed;
    }

    @SuppressLint("BatteryLife") // Permission is part of GmsCore
    private static void openGmsCoreDisableBatteryOptimizationsIntent(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.fromParts("package", GMS_CORE_PACKAGE_NAME, null));
        activity.startActivityForResult(intent, 0);
    }

    private static void checkIfDontKillMyAppSupportsManufacturer() {
        Utils.runOnBackgroundThread(() -> {
            try {
                final long start = System.currentTimeMillis();
                HttpURLConnection connection = Requester.getConnectionFromRoute(
                        DONT_KILL_MY_APP_URL, DONT_KILL_MY_APP_MANUFACTURER_API, BUILD_MANUFACTURER);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                final boolean supported = connection.getResponseCode() == 200;
                Logger.printInfo(() -> "Manufacturer is " + (supported ? "" : "NOT ")
                        + "listed on DontKillMyApp: " + BUILD_MANUFACTURER
                        + " fetch took: " + (System.currentTimeMillis() - start) + "ms");
                DONT_KILL_MY_APP_MANUFACTURER_SUPPORTED = supported;
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not check if manufacturer is listed on DontKillMyApp: "
                        + BUILD_MANUFACTURER, ex);
                DONT_KILL_MY_APP_MANUFACTURER_SUPPORTED = null;
            }
        });
    }

    private static void openDontKillMyApp(Activity activity) {
        final Boolean manufacturerSupported = DONT_KILL_MY_APP_MANUFACTURER_SUPPORTED;

        String manufacturerPageToOpen;
        if (manufacturerSupported == null) {
            // Fetch has not completed yet. Only happens on extremely slow internet connections
            // and the user spends less than 1 second reading what's on screen.
            // Instead of waiting for the fetch (which may time out),
            // open the website without a vendor.
            manufacturerPageToOpen = "";
        } else if (manufacturerSupported) {
            manufacturerPageToOpen = BUILD_MANUFACTURER;
        } else {
            // No manufacturer specific page exists. Open the general page.
            manufacturerPageToOpen = "general";
        }

        open(activity, DONT_KILL_MY_APP_URL + manufacturerPageToOpen + DONT_KILL_MY_APP_NAME_PARAMETER);
    }

    /**
     * @return If GmsCore is not whitelisted from battery optimizations.
     */
    private static boolean batteryOptimizationsEnabled(Context context) {
        //noinspection ObsoleteSdkInt
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Android 5.0 does not have battery optimization settings.
            return false;
        }
        var powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return !powerManager.isIgnoringBatteryOptimizations(GMS_CORE_PACKAGE_NAME);
    }

    private static boolean isAndroidAutomotive(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    private static String getGmsCoreDownload() {
        //noinspection SwitchStatementWithTooFewBranches
        return switch (getGmsCoreVendorGroupId()) {
            case "app.revanced" -> "https://morphe.software/microg";
            default -> getGmsCoreVendorGroupId() + ".android.gms";
        };
    }

    private static String getGmsCoreVendorGroupId() {
        return "app.revanced"; // Modified during patching.
    }
}
