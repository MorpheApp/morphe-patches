/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4511
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.onthego;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.innertube.OnTheGoOuterClass.MenuItemElement;
import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class OnTheGoPatch {

    /**
     * Visibility key of the On-the-go item of the video player flyout menu.
     */
    private static final byte[] MENU_ITEM_KEY = "menu_item_on_the_go".getBytes(StandardCharsets.US_ASCII);

    /**
     * Browse id of the Premium upsell, which each Premium feature requests with its own parameters.
     */
    private static final byte[] UPSELL_BROWSE_ID = "PApremium_upsell".getBytes(StandardCharsets.US_ASCII);

    /**
     * Protobuf tags of the browse id and the parameters of a browse request (fields 2 and 3, length delimited).
     */
    private static final byte BROWSE_ID_TAG = 0x12;
    private static final byte BROWSE_PARAMS_TAG = 0x1A;

    /**
     * Largest length that is a single byte varint.
     */
    private static final int MAX_SINGLE_BYTE_LENGTH = 127;

    private static final boolean USE_EXPERIMENTAL_ICONS = VersionCheckPatch.IS_20_31_OR_GREATER
            && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");

    /**
     * Icon of the On-the-go item of the app, for the item that is added if the menu has none.
     */
    private static final String MENU_ITEM_ICON = USE_EXPERIMENTAL_ICONS
            ? "yt_outline_experimental_headset_vd_theme_24"
            : "yt_outline_headset_black_24";

    /**
     * Bytes of the browse id and parameters of the upsell of the On-the-go menu item,
     * or null if the account has Premium or the menu item was not loaded yet.
     */
    @Nullable
    private static volatile byte[] upsellRequest;

    /**
     * On-the-go state of the app. While it is active, the media controls of the lock screen
     * and notification have seek back and seek forward buttons.
     */
    public interface OnTheGoState {
        // Method is added during patching.
        void patch_setOnTheGoActive(boolean active);
    }

    /**
     * On-the-go state of the app, or null if the app version has no On-the-go media controls.
     */
    @Nullable
    private static volatile OnTheGoState onTheGoState;

    /**
     * Seek length of the app, which is the double tap to seek length of the app settings.
     */
    public interface SeekLength {
        // Method is added during patching.
        long patch_getSeekLengthMilliseconds();
    }

    /**
     * Seek length of the app versions that have no seek length class.
     */
    private static final long DEFAULT_SEEK_LENGTH_MILLISECONDS = 10_000;

    @Nullable
    private static volatile SeekLength seekLength;

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static void setOnTheGoState(@Nullable OnTheGoState state) {
        onTheGoState = state;
    }

    /**
     * Injection point.
     */
    public static void setSeekLength(@Nullable SeekLength length) {
        seekLength = length;
    }

    /**
     * @return The seek length of the app settings.
     */
    static long getSeekLengthMilliseconds() {
        SeekLength length = seekLength;
        if (length != null) {
            try {
                final long milliseconds = length.patch_getSeekLengthMilliseconds();
                if (milliseconds > 0) {
                    return milliseconds;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "getSeekLengthMilliseconds failure", ex);
            }
        }
        return DEFAULT_SEEK_LENGTH_MILLISECONDS;
    }

    /**
     * Turns the On-the-go media controls of the app on or off.
     */
    static void setOnTheGoActive(boolean active) {
        OnTheGoState state = onTheGoState;
        if (state != null) {
            Logger.printDebug(() -> "Setting On-the-go media controls active: " + active);
            state.patch_setOnTheGoActive(active);
        }
    }

    /**
     * Called when a menu is created. The filter state of the previous menu is cleared,
     * as the items of that menu can be built again after its items were added.
     */
    public static void onFlyoutCreated() {
        OnTheGoFilter.resetPlayerMenu();
    }

    /**
     * Adds an On-the-go item to the video player menu, if the menu has no On-the-go item of the app.
     * Some accounts get a menu without the item.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex) {
        try {
            final boolean addItem = OnTheGoFilter.isPlayerMenuWithoutOnTheGo()
                    && Settings.ON_THE_GO.get()
                    // The item of the app is hidden, and should not come back as this item.
                    && !Settings.HIDE_PLAYER_FLYOUT_ON_THE_GO.get();
            if (!addItem) {
                return nextButtonIndex;
            }

            Logger.printDebug(() -> "Adding On-the-go item to the video player menu");
            Drawable icon = ResourceUtils.getDrawable(MENU_ITEM_ICON);
            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    icon,
                    str("morphe_on_the_go_panel_title"),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        OnTheGoSheet.show();
                    },
                    nextButtonIndex
            );
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
            return nextButtonIndex;
        }
    }

    /**
     * Injection point.
     * <p>
     * Saves the upsell request of the On-the-go menu item, and removes its 'Premium' subtitle.
     */
    public static byte[] onElementLoaded(byte[] bytes) {
        if (!Settings.ON_THE_GO.get() || FlyoutUtils.byteIndexOf(bytes, MENU_ITEM_KEY) < 0) {
            return bytes;
        }

        try {
            final int browseIdIndex = FlyoutUtils.byteIndexOf(bytes, UPSELL_BROWSE_ID);
            if (browseIdIndex < 0) {
                // Premium account, the menu item opens the On-the-go panel of the app.
                upsellRequest = null;
                return bytes;
            }

            byte[] request = getUpsellRequest(bytes, browseIdIndex);
            if (request == null) {
                Logger.printException(() -> "Unknown On-the-go upsell request");
                return bytes;
            }
            upsellRequest = request;

            MenuItemElement element = MenuItemElement.parseFrom(bytes);
            var type = element.getType();
            var component = type.getComponentType();
            var model = component.getModel();
            var menuItem = model.getMenuItemModel();
            if (menuItem.getSubtitle().isEmpty()) {
                return bytes;
            }

            Logger.printDebug(() -> "Removing the subtitle of the On-the-go menu item");
            return element.toBuilder().setType(type.toBuilder().setComponentType(
                    component.toBuilder().setModel(model.toBuilder().setMenuItemModel(
                            menuItem.toBuilder().clearSubtitle())))).build().toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "onElementLoaded failure", ex);
            return bytes;
        }
    }

    /**
     * @param browseIdIndex Index of the browse id of the upsell.
     * @return The browse id and parameters fields of the upsell request,
     *         or null if the bytes are not a browse id followed by parameters.
     */
    @Nullable
    private static byte[] getUpsellRequest(byte[] bytes, int browseIdIndex) {
        final int start = browseIdIndex - 2;
        final int paramsTagIndex = browseIdIndex + UPSELL_BROWSE_ID.length;
        if (start < 0 || paramsTagIndex + 1 >= bytes.length
                || bytes[start] != BROWSE_ID_TAG
                || bytes[start + 1] != UPSELL_BROWSE_ID.length
                || bytes[paramsTagIndex] != BROWSE_PARAMS_TAG) {
            return null;
        }

        final int paramsLength = bytes[paramsTagIndex + 1];
        final int end = paramsTagIndex + 2 + paramsLength;
        if (paramsLength <= 0 || end > bytes.length) {
            return null;
        }

        return Arrays.copyOfRange(bytes, start, end);
    }

    /**
     * Injection point.
     * <p>
     * Called for each command the command router of an activity runs.
     *
     * @return If the command is the upsell of the On-the-go menu item, and the Morphe panel is shown instead.
     */
    public static boolean onCommand(MessageLite command) {
        final byte[] request = upsellRequest;
        if (request == null || !Settings.ON_THE_GO.get()) {
            return false;
        }

        try {
            if (FlyoutUtils.byteIndexOf(command.toByteArray(), request) < 0) {
                return false;
            }

            Logger.printDebug(() -> "Showing the On-the-go panel instead of the upsell");
            Utils.runOnMainThread(() -> {
                FlyoutUtils.dismissFlyout();
                OnTheGoSheet.show();
            });
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onCommand failure", ex);
            return false;
        }
    }
}
