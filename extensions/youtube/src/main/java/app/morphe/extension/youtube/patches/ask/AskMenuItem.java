/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.ask;

import static app.morphe.extension.youtube.patches.ask.AskPanelCommand.WIRE_TYPE_LENGTH_DELIMITED;
import static app.morphe.extension.youtube.patches.ask.AskPanelCommand.WIRE_TYPE_VARINT;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;

import app.morphe.extension.youtube.patches.ask.AskPanelCommand.Field;
import app.morphe.extension.youtube.patches.ask.AskPanelCommand.ProtoWriter;

/**
 * Builds the summarize item of the video menu as an item of the menu data, so the app shows it
 * as one of its own items, in its own list.
 * <p>
 * The command of the item has no command type, so the app does nothing with it,
 * and holds a field that the app does not know, which marks the item and holds its video.
 */
final class AskMenuItem {

    // Menu fields.
    private static final int MENU_ITEMS_FIELD = 1;
    /**
     * Set when the server makes the menu run a command instead of showing its items.
     */
    private static final int MENU_RUNS_COMMAND_FIELD = 19;

    // Menu item fields, the item is the one renderer.
    private static final int MENU_SERVICE_ITEM_FIELD = 66441155;

    // Menu service item fields.
    private static final int ITEM_TEXT_FIELD = 1;
    private static final int ITEM_ICON_FIELD = 2;
    private static final int ITEM_COMMAND_FIELD = 3;
    /**
     * Shows the item as loading and disabled.
     */
    private static final int ITEM_LOADING_FIELD = 16;
    /**
     * Shows the item as disabled with {@link #ITEM_STYLE_DISABLED}, with disabled text in a popup
     * that shows the menu items as they are, which shows no loading state.
     */
    private static final int ITEM_STYLE_FIELD = 14;
    private static final int ITEM_STYLE_DISABLED = 1;

    // Formatted string fields.
    private static final int TEXT_RUNS_FIELD = 1;
    private static final int RUN_TEXT_FIELD = 1;

    // Icon fields.
    private static final int ICON_TYPE_FIELD = 1;

    private static final int ICON_TYPE_SPARK = 1090;
    private static final int ICON_TYPE_SHARE = 48;
    private static final int ICON_TYPE_SHARE_ARROW = 100;
    private static final int ICON_TYPE_ADD_TO_WATCH_LATER = 59;
    private static final int ICON_TYPE_QUEUE_PLAY_NEXT = 251;

    /**
     * A command field that the app does not know, which marks the item.
     */
    private static final int MARKER_FIELD = 536_870_000;
    private static final String MARKER_PREFIX = "morphe_ask_summarize:";

    private AskMenuItem() {
    }

    /**
     * @return The menu with the item before the share item, or after the last item if the menu has no share item,
     *         or null if the menu is not the menu of a video, or does not show its items.
     */
    @Nullable
    static byte[] addToMenu(byte[] menu, String videoId, String text, boolean isLoading) {
        List<Field> fields = AskPanelCommand.parse(menu);
        if (fields == null) {
            return null;
        }

        boolean isVideoMenu = false;
        for (Field field : fields) {
            if (field.number == MENU_RUNS_COMMAND_FIELD && field.wireType == WIRE_TYPE_VARINT && field.varint != 0) {
                return null;
            }
            if (field.number == MENU_ITEMS_FIELD) {
                final int icon = getIconType(field.value);
                if (icon == ICON_TYPE_ADD_TO_WATCH_LATER || icon == ICON_TYPE_QUEUE_PLAY_NEXT) {
                    isVideoMenu = true;
                }
            }
        }
        if (!isVideoMenu) {
            return null;
        }

        byte[] item = build(videoId, text, isLoading);
        ProtoWriter result = new ProtoWriter();
        boolean isAdded = false;
        int lastItemIndex = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).number == MENU_ITEMS_FIELD) {
                lastItemIndex = i;
            }
        }
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (!isAdded && field.number == MENU_ITEMS_FIELD) {
                final int icon = getIconType(field.value);
                if (icon == ICON_TYPE_SHARE_ARROW || icon == ICON_TYPE_SHARE) {
                    result.writeLengthDelimited(MENU_ITEMS_FIELD, item);
                    isAdded = true;
                }
            }
            result.writeRaw(field.raw);
            if (!isAdded && i == lastItemIndex) {
                result.writeLengthDelimited(MENU_ITEMS_FIELD, item);
                isAdded = true;
            }
        }
        return result.toByteArray();
    }

    /**
     * @param isLoading If the item shows as loading and disabled.
     */
    static byte[] build(String videoId, String text, boolean isLoading) {
        return build(videoId, text, isLoading, false);
    }

    /**
     * @param isLoading  If the item shows as loading and disabled.
     * @param isTextOnly If a popup shows the item as is, which shows only its text,
     *                   so a loading item shows disabled text.
     */
    static byte[] build(String videoId, String text, boolean isLoading, boolean isTextOnly) {
        ProtoWriter run = new ProtoWriter();
        run.writeLengthDelimited(RUN_TEXT_FIELD, text.getBytes(StandardCharsets.UTF_8));
        ProtoWriter formattedText = new ProtoWriter();
        formattedText.writeLengthDelimited(TEXT_RUNS_FIELD, run.toByteArray());

        ProtoWriter icon = new ProtoWriter();
        icon.writeVarint(ICON_TYPE_FIELD, ICON_TYPE_SPARK);

        ProtoWriter command = new ProtoWriter();
        command.writeLengthDelimited(MARKER_FIELD, (MARKER_PREFIX + videoId).getBytes(StandardCharsets.UTF_8));

        ProtoWriter serviceItem = new ProtoWriter();
        serviceItem.writeLengthDelimited(ITEM_TEXT_FIELD, formattedText.toByteArray());
        serviceItem.writeLengthDelimited(ITEM_ICON_FIELD, icon.toByteArray());
        serviceItem.writeLengthDelimited(ITEM_COMMAND_FIELD, command.toByteArray());
        if (isLoading) {
            serviceItem.writeVarint(ITEM_LOADING_FIELD, 1);
            if (isTextOnly) {
                serviceItem.writeVarint(ITEM_STYLE_FIELD, ITEM_STYLE_DISABLED);
            }
        }

        ProtoWriter item = new ProtoWriter();
        item.writeLengthDelimited(MENU_SERVICE_ITEM_FIELD, serviceItem.toByteArray());
        return item.toByteArray();
    }

    /**
     * @return The video of the item, or null if the menu item is not this item.
     */
    @Nullable
    static String getVideoId(byte[] menuItem) {
        Field command = getServiceItemField(menuItem, ITEM_COMMAND_FIELD);
        List<Field> commandFields = command == null ? null : AskPanelCommand.parse(command.value);
        if (commandFields != null) {
            for (Field field : commandFields) {
                if (field.number == MARKER_FIELD && field.wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                    String marker = new String(field.value, StandardCharsets.UTF_8);
                    if (marker.startsWith(MARKER_PREFIX)) {
                        return marker.substring(MARKER_PREFIX.length());
                    }
                }
            }
        }
        return null;
    }

    static boolean isLoading(byte[] menuItem) {
        Field loading = getServiceItemField(menuItem, ITEM_LOADING_FIELD);
        return loading != null && loading.wireType == WIRE_TYPE_VARINT && loading.varint != 0;
    }

    /**
     * @return The icon type of a menu item of any renderer, or -1 if it has none.
     */
    private static int getIconType(byte[] menuItem) {
        List<Field> item = AskPanelCommand.parse(menuItem);
        if (item == null || item.isEmpty() || item.get(0).wireType != WIRE_TYPE_LENGTH_DELIMITED) {
            return -1;
        }
        List<Field> renderer = AskPanelCommand.parse(item.get(0).value);
        if (renderer == null) {
            return -1;
        }
        for (Field field : renderer) {
            if (field.number == ITEM_ICON_FIELD && field.wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                List<Field> icon = AskPanelCommand.parse(field.value);
                if (icon != null) {
                    for (Field iconField : icon) {
                        if (iconField.number == ICON_TYPE_FIELD && iconField.wireType == WIRE_TYPE_VARINT) {
                            return (int) iconField.varint;
                        }
                    }
                }
            }
        }
        return -1;
    }

    @Nullable
    private static Field getServiceItemField(byte[] menuItem, int number) {
        List<Field> item = AskPanelCommand.parse(menuItem);
        if (item == null) {
            return null;
        }
        for (Field itemField : item) {
            if (itemField.number != MENU_SERVICE_ITEM_FIELD || itemField.wireType != WIRE_TYPE_LENGTH_DELIMITED) {
                continue;
            }
            List<Field> serviceItem = AskPanelCommand.parse(itemField.value);
            if (serviceItem != null) {
                for (Field field : serviceItem) {
                    if (field.number == number) {
                        return field;
                    }
                }
            }
        }
        return null;
    }
}
