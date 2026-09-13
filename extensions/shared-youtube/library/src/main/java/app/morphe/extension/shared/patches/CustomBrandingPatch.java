/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2518
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.IconListPreference;

/**
 * Patch shared by YouTube and YT Music.
 */
@SuppressWarnings("unused")
public class CustomBrandingPatch {

    // Important: In the future, additional branding themes can be added but all existing and prior
    // themes cannot be removed or renamed.
    //
    // This is because if a user has a branding theme selected, then only that launch alias is enabled.
    // If a future update removes or renames that alias, then after updating the app is effectively
    // broken, and it cannot be opened and not even clearing the app data will fix it.
    // In that situation the only fix is to completely uninstall and reinstall again.
    //
    // The most that can be done is to hide a theme from the UI and keep the alias with dummy data.
    public enum BrandingTheme {
        /** Original unpatched icon. */
        ORIGINAL,
        LIGHT,
        DARK,
        BLACK,
        PLAY,
        PLAY_BLACK,
        /**  User provided custom icon. */
        CUSTOM;

        private String packageAndNameIndexToClassAlias(String packageName, int appIndex) {
            if (appIndex <= 0) {
                throw new IllegalArgumentException("App index starts at index 1");
            }
            return packageName + ".morphe_" + name().toLowerCase(Locale.US) + '_' + appIndex;
        }

        /**
         * Returns the notification icon resource name for this theme.
         * <p>
         * Each built-in theme has its own XML vector drawable so the notification icon
         * matches the selected launcher icon. The CUSTOM theme uses a separate resource
         * that can be overridden at patch time with either an XML or a PNG supplied by
         * the user.
         * <p>
         * Returns {@code null} for {@link #ORIGINAL} (no override desired).
         */
        @Nullable
        String notificationIconResourceName() {
            return switch (this) {
                case ORIGINAL -> null;
                case CUSTOM -> "morphe_notification_icon_custom";
                // LIGHT, DARK, BLACK, PLAY – each has its own bundled XML.
                default ->"morphe_notification_icon_" + name().toLowerCase(Locale.US);
            };
        }
    }

    /**
     * Notification icon theme - selected independently of the launcher icon.
     */
    public enum NotificationIconTheme {
        /**  * Follow the currently selected launcher icon theme. */
        FOLLOW,
        ORIGINAL,
        LIGHT,
        DARK,
        BLACK,
        PLAY,
        PLAY_BLACK,
        /** * User provided custom PNG notification icon. */
        CUSTOM;

        /**
         * Resolves the effective {@link BrandingTheme} to use for the notification icon.
         * When {@link #FOLLOW}, delegates to the current launcher icon setting.
         */
        @NonNull
        BrandingTheme resolveNotificationBrandingTheme() {
            if (this == FOLLOW) {
                return SharedYouTubeSettings.CUSTOM_BRANDING_ICON.get();
            }
            // Map 1:1 to BrandingTheme by name (both enums share the same names except FOLLOW).
            try {
                return BrandingTheme.valueOf(name());
            } catch (IllegalArgumentException e) {
                return BrandingTheme.BLACK;
            }
        }
    }

    @Nullable
    private static Integer notificationSmallIcon;

    private static int getNotificationSmallIcon() {
        // Cannot use static initialization block otherwise cyclic references exist
        // between Settings initialization and this class.
        if (notificationSmallIcon == null) {
            if (GmsCoreSupportPatch.isPackageNameOriginal()) {
                // A mounted install has the original icon resource replaced while patching.
                Logger.printDebug(() -> "App is root mounted. Not overriding small notification icon");
                return notificationSmallIcon = 0;
            }

            // Resolve the effective BrandingTheme for the notification icon.
            NotificationIconTheme notificationTheme = SharedYouTubeSettings.CUSTOM_BRANDING_NOTIFICATION_ICON.get();
            BrandingTheme branding = notificationTheme.resolveNotificationBrandingTheme();
            String iconName = branding.notificationIconResourceName();
            if (iconName == null) {
                notificationSmallIcon = 0;
            } else {
                notificationSmallIcon = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, iconName);
                if (notificationSmallIcon == 0) {
                    Logger.printException(() -> "Could not load notification small icon: " + iconName);
                }
            }
        }
        return notificationSmallIcon;
    }

    /**
     * Injection point.
     */
    public static View getLottieViewOrNull(View lottieStartupView) {
        if (GmsCoreSupportPatch.isPackageNameOriginal()) {
            // A mounted install cannot change the icon at runtime, so the icon chosen
            // while patching decides if the original startup animation is kept.
            return mountedIconApplied() ? null : lottieStartupView;
        }

        if (SharedYouTubeSettings.CUSTOM_BRANDING_ICON.get() == BrandingTheme.ORIGINAL) {
            return lottieStartupView;
        }

        return null;
    }

    /**
     * Injection point.
     */
    public static int getSmallIcon(int original) {
        try {
            final int smallIcon = getNotificationSmallIcon();
            if (smallIcon != 0) {
                return smallIcon;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getSmallIcon failure", ex);
        }
        return original;
    }

    /**
     * Injection point.
     */
    public static int getColor(int original) {
        try {
            if (GmsCoreSupportPatch.isPackageNameOriginal()) {
                // A mounted install has the notification icon resource replaced while patching,
                // and the tint of the original icon does not match the icon that replaced it.
                return mountedNotificationIconApplied()
                        ? Color.TRANSPARENT
                        : original;
            }

            final int smallIcon = getNotificationSmallIcon();
            if (smallIcon != 0) {
                // Remove YT red tint.
                return Color.TRANSPARENT;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getColor failure", ex);
        }
        return original;
    }

    /**
     * Injection point.
     * <p>
     * The total number of app name aliases, including dummy aliases.
     */
    private static int numberOfPresetAppNames() {
        // Modified during patching, but requires a default if custom branding is excluded.
        return 1;
    }

    /**
     * Injection point.
     * <p>
     * The icon style selected during patching.
     */
    private static String defaultIconStyleName() {
        // Modified during patching, but requires a default if custom branding is excluded.
        return "";
    }

    /**
     * Injection point.
     * <p>
     * If a mounted (root) install had a launcher icon applied during patching.
     */
    private static boolean mountedIconApplied() {
        // Modified during patching, but requires a default if custom branding is excluded.
        return false;
    }

    /**
     * Injection point.
     * <p>
     * If a mounted (root) install had the notification icon applied during patching.
     */
    private static boolean mountedNotificationIconApplied() {
        // Modified during patching, but requires a default if custom branding is excluded.
        return false;
    }

    /**
     * Injection point.
     * <p>
     * If a custom icon was provided during patching.
     */
    private static boolean userProvidedCustomIcon() {
        // Modified during patching, but requires a default if custom branding is excluded.
        return false;
    }

    /**
     * Injection point.
     * <p>
     * The mipmap resource name of the original unpatched launcher icon.
     * Differs per app: YouTube uses "ringo2_ic_launcher", YT Music uses "ic_launcher_release".
     */
    private static String originalLauncherIconName() {
        // Modified during patching.
        return "";
    }

    /**
     * Injection point.
     * <p>
     * The drawable resource name of the original notification icon.
     * Differs per app: YouTube uses "ic_stat_yt_notification_logo",
     * YT Music uses "music_push_notification_white".
     */
    private static String originalNotificationIconName() {
        // Modified during patching.
        return "";
    }

    /**
     * Injection point.
     * <p>
     * If a custom name was provided during patching.
     */
    private static boolean userProvidedCustomName() {
        // Modified during patching, but requires a default if custom branding is excluded.
        return false;
    }

    public static int getDefaultAppNameIndex() {
        return userProvidedCustomName()
                ? numberOfPresetAppNames()
                : 1;
    }

    public static BrandingTheme getDefaultIconStyle() {
        // Cannot log here, because this runs while Settings are initializing.
        String styleName = defaultIconStyleName();

        if (!styleName.isEmpty()) {
            try {
                return BrandingTheme.valueOf(styleName.toUpperCase(Locale.US));
            } catch (IllegalArgumentException ex) {
                // An icon style was added to the patch but not to this enum.
            }
        }

        // Custom branding is excluded from the app.
        return userProvidedCustomIcon()
                ? BrandingTheme.CUSTOM
                : BrandingTheme.BLACK;
    }

    /**
     * Asks a launcher to reload the icon of the app after the branding was applied by patching.
     * <p>
     * Mounting changes nothing a launcher uses to notice the app was updated, because the package
     * is still the one the unpatched app was installed with. So a launcher keeps showing the icon
     * it cached when the unpatched app was first seen, and not even a reboot refreshes it.
     * <p>
     * Changing the state of a component makes the system broadcast that the package changed, which
     * is what makes a launcher reload the icon. The launch component is enabled either way, so
     * setting it and then restoring it changes nothing else.
     */
    private static void refreshMountedLauncherIcon() {
        String patchedIconStyle = defaultIconStyleName();
        if (patchedIconStyle.equals(SharedYouTubeSettings.CUSTOM_BRANDING_ICON_PATCHED.get())) {
            return;
        }

        Context context = Utils.getContext();
        PackageManager pm = context.getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(context.getPackageName());
        ComponentName launchComponent = launchIntent == null ? null : launchIntent.getComponent();

        if (launchComponent == null) {
            Logger.printException(() -> "Could not find the launch component of the app");
            return;
        }

        pm.setComponentEnabledSetting(launchComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(launchComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);

        SharedYouTubeSettings.CUSTOM_BRANDING_ICON_PATCHED.save(patchedIconStyle);
        Logger.printInfo(() -> "Asked the launcher to reload the icon: " + patchedIconStyle);
    }

    /**
     * Injection point.
     */
    @SuppressWarnings("ConstantConditions")
    public static void setBranding() {
        try {
            if (GmsCoreSupportPatch.isPackageNameOriginal()) {
                Logger.printInfo(() -> "App is root mounted. Branding was applied while patching");
                refreshMountedLauncherIcon();
                return;
            }

            // Patching with a different icon overrides the icon selected in the app, otherwise
            // the patch option would silently do nothing for anyone who already has the app.
            // Patching with the same icon keeps the selection, so an app update does not undo it.
            String patchedIconStyle = defaultIconStyleName();
            if (!patchedIconStyle.equals(SharedYouTubeSettings.CUSTOM_BRANDING_ICON_PATCHED.get())) {
                Logger.printInfo(() -> "Applying the icon of the last patch: " + patchedIconStyle);
                SharedYouTubeSettings.CUSTOM_BRANDING_ICON_PATCHED.save(patchedIconStyle);
                SharedYouTubeSettings.CUSTOM_BRANDING_ICON.save(getDefaultIconStyle());
            }

            Context context = Utils.getContext();
            PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();

            BrandingTheme selectedBranding = SharedYouTubeSettings.CUSTOM_BRANDING_ICON.get();
            final int selectedNameIndex = SharedYouTubeSettings.CUSTOM_BRANDING_NAME.get();
            ComponentName componentToEnable = null;
            ComponentName defaultComponent = null;
            List<ComponentName> componentsToDisable = new ArrayList<>();

            for (BrandingTheme theme : BrandingTheme.values()) {
                // Must always update all aliases including custom alias (last index).
                final int numberOfPresetAppNames = numberOfPresetAppNames();

                // App name indices starts at 1.
                for (int index = 1; index <= numberOfPresetAppNames; index++) {
                    String aliasClass = theme.packageAndNameIndexToClassAlias(packageName, index);
                    ComponentName component = new ComponentName(packageName, aliasClass);
                    if (defaultComponent == null) {
                        // Default is always the first alias.
                        defaultComponent = component;
                    }

                    if (index == selectedNameIndex && theme == selectedBranding) {
                        componentToEnable = component;
                    } else {
                        componentsToDisable.add(component);
                    }
                }
            }

            if (componentToEnable == null) {
                // User imported a bad app name index value. Either the imported data
                // was corrupted, or they previously had custom name enabled and the app
                // no longer has a custom name specified.
                Utils.showToastLong("Custom branding reset");
                SharedYouTubeSettings.CUSTOM_BRANDING_ICON.resetToDefault();
                SharedYouTubeSettings.CUSTOM_BRANDING_NAME.resetToDefault();

                componentToEnable = defaultComponent;
                componentsToDisable.remove(defaultComponent);
            }

            IconListPreference.setOriginalLauncherIconName(originalLauncherIconName());
            IconListPreference.setOriginalNotificationIconName(originalNotificationIconName());

            // Reset cached notification icon so it is re-resolved with the current theme
            // on the next notification. This handles the case where setBranding() is called
            // more than once in a session (e.g. after a settings change).
            notificationSmallIcon = null;

            for (ComponentName disable : componentsToDisable) {
                pm.setComponentEnabledSetting(disable,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }

            // Use info logging because if the alias status become corrupt the app cannot launch.
            ComponentName componentToEnableFinal = componentToEnable;
            Logger.printInfo(() -> "Enabling:  " + componentToEnableFinal.getClassName());

            pm.setComponentEnabledSetting(componentToEnable,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
        } catch (Exception ex) {
            Logger.printException(() -> "setBranding failure", ex);
        }
    }
}
