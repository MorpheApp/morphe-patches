/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4963
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedOutputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class OpenPostImagesFullscreenPatch {

    /**
     * Browse id of the posts feed that the images of a post open when tapped.
     */
    private static final String POSTS_FEED_BROWSE_ID = "FEpost_home";

    /**
     * Field numbers of the command an image of a post runs when tapped:
     * of each image of a post with many images, and of the image of a post with one image.
     * Other parts of the post, such as the 'View all posts' button, have other field numbers.
     */
    private static final int[][] IMAGE_TAP_COMMAND_PATHS = {
            {9, 1, 30},
            {2, 30}
    };
    private static final int INNERTUBE_COMMAND_FIELD = 169495254;
    private static final int BROWSE_ENDPOINT_FIELD = 48687626;
    private static final int BROWSE_ID_FIELD = 2;

    /**
     * Image of the posts of the card layout (lockup.eml), of a post with one image and of each
     * image of a post with many images. The templates of the card layout cannot open the image
     * in fullscreen, so its command is replaced with the command the posts feed runs
     * to open the image in fullscreen.
     */
    private static final int CARD_IMAGE_FIELD = 2120;
    private static final int CARD_IMAGE_COMMAND_FIELD = 30;
    /**
     * The images of a post with many images, each in an item with its image.
     */
    private static final int MULTI_IMAGE_FIELD = 2911;
    private static final int MULTI_IMAGE_ITEM_FIELD = 1;
    /**
     * Key of the state of the image indicator of a post with many images, which the fullscreen
     * view of the images uses with {@link #FULLSCREEN_INDEX_STATE_SUFFIX} for its shown image.
     */
    private static final int MULTI_IMAGE_STATE_KEY_FIELD = 2;
    private static final String FULLSCREEN_INDEX_STATE_SUFFIX = "_lb";

    /**
     * Fields of the element of a card and of its model, see {@link FeedCardLayoutPatch}.
     */
    private static final int ELEMENT_FIELD = 1;
    private static final int ELEMENT_EXTENSION = 168777401;
    private static final int ELEMENT_TEMPLATE_FIELD = 3;
    private static final int ELEMENT_TEMPLATE_EXTENSION = 172035250;
    private static final int ELEMENT_TEMPLATE_URI_FIELD = 1;
    private static final int ELEMENT_MODEL_FIELD = 5;
    private static final int ELEMENT_BINDINGS_FIELD = 6;
    private static final int CARD_MODEL_EXTENSION = 413471385;
    /** The context of the model, such as the theme and the capabilities of the app. */
    private static final int MODEL_CONTEXT_FIELD = 3;
    private static final int MODEL_SIZE_FIELD = 5;
    private static final int THEME_BINDING_EXTENSION = 386334485;
    private static final int CAPABILITIES_BINDING_EXTENSION = 386334486;
    private static final int BINDING_ENTITY_FIELD = 1;
    private static final int BINDING_KEY_FIELD = 1;

    /**
     * Template of the fullscreen view of the images of a post, and the fields of its model.
     */
    private static final String FULLSCREEN_TEMPLATE_NAME = "post_image_lightbox.eml";
    private static final int FULLSCREEN_MODEL_EXTENSION = 33554878;
    private static final int FULLSCREEN_MODEL_CONTENT_FIELD = 1;
    private static final int FULLSCREEN_CONTENT_IMAGE_FIELD = 1;
    private static final int FULLSCREEN_CONTENT_INDEX_STATE_FIELD = 3;
    private static final int FULLSCREEN_CONTENT_CLOSE_BUTTON_FIELD = 5;
    private static final int FULLSCREEN_MODEL_SIZE_FIELD = 2;
    private static final int FULLSCREEN_MODEL_CONTEXT_FIELD = 3;
    private static final int FULLSCREEN_MODEL_CAPABILITIES_FIELD = 4;
    private static final int CLOSE_BUTTON_LABEL_FIELD = 1;
    private static final int CLOSE_BUTTON_TYPE_FIELD = 2;
    private static final int CLOSE_BUTTON_TYPE = 1;
    /** Field of the theme in the context of a model. */
    private static final int THEME_FIELD = 352047819;
    /** The binding of the states and the entities of the fullscreen view to its model. */
    private static final int STATE_BINDING_EXTENSION = 188490103;
    private static final int STATE_BINDING_MODEL_FIELD = 2;
    private static final int STATE_BINDING_ITEM_FIELD = 1;
    private static final int STATE_BINDING_KEY_FIELD = 1;
    private static final int STATE_BINDING_FIELD_FIELD = 2;
    private static final int STATE_BINDING_PATH_FIELD = 3;
    private static final int STATE_BINDING_ENTITY_FIELD = 4;
    private static final int STATE_BINDING_TYPE_FIELD = 6;
    private static final int THEME_STATE_ENTITY = 1;
    private static final int THEME_STATE_TYPE = 1;
    private static final int CAPABILITIES_STATE_TYPE = 2;
    /** Field of the model of the fullscreen view that gets the index of the shown image. */
    private static final int INDEX_STATE_FIELD = 5;
    private static final int THEME_BINDING_PATH_FIELD = 2;
    private static final int CAPABILITIES_BINDING_PATH_FIELD = 2;
    private static final int BINDING_MODEL_FIELD = 2;
    private static final int MODEL_BINDING_EXTENSION = 212323971;
    private static final int MODEL_BINDING_MODEL_FIELD = 1;
    private static final int MODEL_BINDING_FIELDS_FIELD = 4;
    private static final int MODEL_BINDING_FIELD_FIELD = 1;
    private static final int MODEL_BINDING_TYPE_FIELD = 2;
    private static final int MODEL_BINDING_TYPE = 2;
    /** Fields of the model of the fullscreen view that get the state of its gestures. */
    private static final int TOUCH_POSITION_STATE_FIELD = 8;
    private static final int DRAGGING_STATE_FIELD = 9;
    private static final int WINDOW_INSETS_STATE_FIELD = 11;
    private static final int ORIENTATION_STATE_FIELD = 12;

    /**
     * States of the gestures of the fullscreen view, which its command sets before it's shown.
     */
    private static final String DRAG_DISTANCE_STATE = "post_image_lightbox_drag_distance";
    private static final String TOUCH_POSITION_STATE = "post_image_lightbox_touch_position";
    private static final String ZOOMING_STATE = "post_image_lightbox_is_zooming";
    private static final String DRAGGING_STATE = "post_image_lightbox_is_dragging";
    private static final String DRAG_END_STATE = "post_image_lightbox_is_drag_end";
    private static final String WINDOW_INSETS_STATE = "post_image_lightbox_window_insets";
    private static final String ORIENTATION_STATE = "post_image_lightbox_orientation";
    private static final float NO_TOUCH_POSITION = -1;
    private static final int STATE_VALUE_BOOLEAN_FIELD = 1;
    private static final int STATE_VALUE_INT_FIELD = 2;
    private static final int STATE_VALUE_X_FIELD = 1;
    private static final int STATE_VALUE_Y_FIELD = 2;

    /**
     * Commands of the fullscreen view: a list of commands, commands run one after another,
     * setting a state, and showing an element in a dialog.
     */
    private static final int COMMAND_LIST_EXTENSION = 170382656;
    private static final int COMMAND_SEQUENCE_EXTENSION = 170382688;
    private static final int COMMANDS_FIELD = 1;
    private static final int SET_STATE_EXTENSION = 170382720;
    private static final int SET_STATE_KEY_FIELD = 1;
    private static final int SET_STATE_VALUE_FIELD = 2;
    private static final int SHOW_DIALOG_EXTENSION = 221293762;
    private static final int SHOW_DIALOG_CONTENT_FIELD = 2;
    private static final int SHOW_DIALOG_ELEMENTS_FIELD = 1;
    private static final int SHOW_DIALOG_ELEMENT_FIELD = 1;
    private static final int SHOW_DIALOG_FULLSCREEN_FIELD = 15;
    private static final int SHOW_DIALOG_CANCELABLE_FIELD = 5;
    private static final int SHOW_DIALOG_ANIMATION_FIELD = 9;
    private static final int SHOW_DIALOG_STYLE_FIELD = 3;
    private static final int SHOW_DIALOG_THEME_FIELD = 8;
    private static final int SHOW_DIALOG_THEME_NAME_FIELD = 1;
    private static final int SHOW_DIALOG_THEME_COLOR_FIELD = 2;
    private static final int SHOW_DIALOG_THEME_COLOR = 0x1A000000;
    private static final String[] SHOW_DIALOG_THEMES = {"USER_INTERFACE_THEME_LIGHT", "USER_INTERFACE_THEME_DARK"};

    /**
     * Uri of the template of the fullscreen view, with the hash of the version the app has.
     */
    private static final FeedCardLayoutPatch.VideoTemplate FULLSCREEN_TEMPLATE = new FeedCardLayoutPatch.VideoTemplate(
            FULLSCREEN_TEMPLATE_NAME, null, FULLSCREEN_MODEL_EXTENSION,
            FULLSCREEN_MODEL_CONTEXT_FIELD, FULLSCREEN_MODEL_CAPABILITIES_FIELD);

    private OpenPostImagesFullscreenPatch() {
    }

    /**
     * Injection point.
     * <p>
     * The images of a post open the posts feed when tapped, where the image must be tapped
     * again to open it in fullscreen. Without the command of the image, the post opens
     * the image in fullscreen. The images of the card layout do nothing without their command,
     * so their command opens the image in fullscreen instead.
     */
    @Nullable
    public static byte[] onElementLoaded(@Nullable byte[] bytes) {
        if (!Settings.OPEN_POST_IMAGES_FULLSCREEN.get() || bytes == null
                || !FeedVideoCardsPatch.getElementText(bytes).contains(POSTS_FEED_BROWSE_ID)) {
            return bytes;
        }

        try {
            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) {
                return bytes;
            }

            boolean modified = false;
            for (int[] path : IMAGE_TAP_COMMAND_PATHS) {
                for (ProtoNode command : ProtoNode.findMessages(root, path)) {
                    if (opensPostsFeed(command)) {
                        command.remove();
                        modified = true;
                    }
                }
            }
            if (replaceCardImageCommands(root)) {
                modified = true;
            }
            if (!modified) {
                return bytes;
            }

            Logger.printDebug(() -> "Changed the posts feed command of post images");
            return ProtoNode.write(root);
        } catch (Exception ex) {
            Logger.printException(() -> "onElementLoaded failure", ex);
            return bytes;
        }
    }

    private static boolean opensPostsFeed(ProtoNode command) {
        List<ProtoNode> fields = command.children;
        if (fields == null) {
            return false;
        }
        ProtoNode browseId = ProtoNode.field(ProtoNode.message(fields,
                INNERTUBE_COMMAND_FIELD, BROWSE_ENDPOINT_FIELD), BROWSE_ID_FIELD);
        return browseId != null && POSTS_FEED_BROWSE_ID.equals(browseId.decodeText());
    }

    /**
     * Replaces the command of each image of the card layout with the command that opens
     * the images of the post in fullscreen, with the tapped image shown.
     *
     * @return If a command was replaced.
     */
    private static boolean replaceCardImageCommands(List<ProtoNode> root) throws IOException {
        List<ProtoNode> images = ProtoNode.findMessages(root, CARD_IMAGE_FIELD);
        if (images.isEmpty()) {
            return false;
        }
        List<ProtoNode> element = ProtoNode.message(root, ELEMENT_FIELD, ELEMENT_EXTENSION);
        List<ProtoNode> model = ProtoNode.message(element, ELEMENT_MODEL_FIELD, CARD_MODEL_EXTENSION);
        ProtoNode context = ProtoNode.field(model, MODEL_CONTEXT_FIELD);
        ProtoNode size = ProtoNode.field(model, MODEL_SIZE_FIELD);
        ProtoNode themeKey = ProtoNode.field(ProtoNode.message(element, ELEMENT_BINDINGS_FIELD,
                THEME_BINDING_EXTENSION, BINDING_ENTITY_FIELD, BINDING_KEY_FIELD), BINDING_KEY_FIELD);
        ProtoNode capabilitiesKey = ProtoNode.field(ProtoNode.message(element, ELEMENT_BINDINGS_FIELD,
                CAPABILITIES_BINDING_EXTENSION, BINDING_ENTITY_FIELD), BINDING_KEY_FIELD);
        if (context == null || size == null || themeKey == null || capabilitiesKey == null) {
            Logger.printDebug(() -> "Post card has no theme or size");
            return false;
        }
        String uri = FULLSCREEN_TEMPLATE.getUri();
        if (uri == null) {
            // The images still open the posts feed.
            Logger.printDebug(() -> "Fullscreen post image template is not cached");
            return false;
        }

        FullscreenView view = new FullscreenView(uri, context.getPayload(), size.getPayload(),
                themeKey.decodeText(), capabilitiesKey.decodeText(), str("morphe_settings_about_close"));

        boolean replaced = false;
        for (Map.Entry<ProtoNode, List<List<ProtoNode>>> post : getPostImages(images).entrySet()) {
            List<List<ProtoNode>> postImages = post.getValue();
            String indexState = null;
            if (post.getKey().getFieldNumber() == MULTI_IMAGE_FIELD) {
                ProtoNode stateKey = ProtoNode.field(post.getKey().children, MULTI_IMAGE_STATE_KEY_FIELD);
                if (stateKey == null) {
                    continue;
                }
                indexState = stateKey.decodeText() + FULLSCREEN_INDEX_STATE_SUFFIX;
            }

            // The images are shown without their command.
            List<byte[]> imageBytes = new ArrayList<>(postImages.size());
            for (List<ProtoNode> imageFields : postImages) {
                List<ProtoNode> fields = new ArrayList<>(imageFields);
                fields.removeIf(field -> field.getFieldNumber() == CARD_IMAGE_COMMAND_FIELD);
                imageBytes.add(ProtoNode.write(fields));
            }
            for (int index = 0; index < postImages.size(); index++) {
                ProtoNode command = ProtoNode.field(postImages.get(index), CARD_IMAGE_COMMAND_FIELD);
                if (command != null && opensPostsFeed(command)) {
                    command.setPayload(view.buildCommand(imageBytes, index, indexState));
                    replaced = true;
                }
            }
        }
        return replaced;
    }

    /**
     * @return The fields of the images of each post, in the order of the post, by the images
     *         of a post with many images or by the image of a post with one image.
     */
    private static Map<ProtoNode, List<List<ProtoNode>>> getPostImages(List<ProtoNode> images) {
        Map<ProtoNode, List<List<ProtoNode>>> posts = new IdentityHashMap<>();
        for (ProtoNode image : images) {
            List<ProtoNode> imageFields = image.children;
            if (imageFields == null) {
                continue;
            }
            ProtoNode item = image.getParent();
            ProtoNode multiImage = item == null ? null : item.getParent();
            ProtoNode post = multiImage != null && multiImage.getFieldNumber() == MULTI_IMAGE_FIELD
                    && item.getFieldNumber() == MULTI_IMAGE_ITEM_FIELD
                    ? multiImage
                    : image;
            List<List<ProtoNode>> postImages = posts.get(post);
            if (postImages == null) {
                postImages = new ArrayList<>();
                posts.put(post, postImages);
            }
            postImages.add(imageFields);
        }
        return posts;
    }

    /**
     * The fullscreen view of the images of the posts of a card, the same as of the posts feed.
     */
    private record FullscreenView(String uri, byte[] context, byte[] size, String themeKey,
                                  String capabilitiesKey, String closeLabel) {

        /**
         * @param indexState Key of the state of the shown image, or null if the post has one image.
         * @return The command that shows the images in fullscreen, with the image at the index shown.
         */
        byte[] buildCommand(List<byte[]> images, final int index, @Nullable String indexState)
                throws IOException {
            byte[] content = ProtoNode.encode(output -> {
                output.writeByteArray(FULLSCREEN_CONTENT_CLOSE_BUTTON_FIELD, ProtoNode.encode(close -> {
                    close.writeString(CLOSE_BUTTON_LABEL_FIELD, closeLabel);
                    close.writeInt32(CLOSE_BUTTON_TYPE_FIELD, CLOSE_BUTTON_TYPE);
                }));
                for (byte[] image : images) {
                    output.writeByteArray(FULLSCREEN_CONTENT_IMAGE_FIELD, image);
                }
                output.writeString(FULLSCREEN_CONTENT_INDEX_STATE_FIELD, indexState == null ? "" : indexState);
            });
            byte[] model = ProtoNode.encode(output -> {
                output.writeByteArray(FULLSCREEN_MODEL_CONTENT_FIELD, content);
                output.writeByteArray(FULLSCREEN_MODEL_SIZE_FIELD, size);
                // The theme of the context is added by the theme binding when the element is shown.
                output.writeByteArray(FULLSCREEN_MODEL_CONTEXT_FIELD, context);
            });
            byte[] element = ProtoNode.encode(output -> {
                output.writeByteArray(ELEMENT_TEMPLATE_FIELD, ProtoNode.encodeMessage(ELEMENT_TEMPLATE_EXTENSION,
                        ProtoNode.encodeString(ELEMENT_TEMPLATE_URI_FIELD, uri)));
                output.writeByteArray(ELEMENT_MODEL_FIELD, ProtoNode.encodeMessage(FULLSCREEN_MODEL_EXTENSION, model));
                output.writeByteArray(ELEMENT_BINDINGS_FIELD, buildBindings(indexState));
            });
            byte[] showDialog = ProtoNode.encodeMessage(SHOW_DIALOG_EXTENSION, ProtoNode.encode(output -> {
                output.writeByteArray(SHOW_DIALOG_CONTENT_FIELD, ProtoNode.encodeMessage(SHOW_DIALOG_ELEMENTS_FIELD,
                        ProtoNode.encodeMessage(SHOW_DIALOG_ELEMENT_FIELD,
                                ProtoNode.encodeMessage(ELEMENT_EXTENSION, element))));
                output.writeBool(SHOW_DIALOG_FULLSCREEN_FIELD, true);
                output.writeBool(SHOW_DIALOG_CANCELABLE_FIELD, true);
                output.writeInt32(SHOW_DIALOG_ANIMATION_FIELD, 0);
                output.writeInt32(SHOW_DIALOG_STYLE_FIELD, 0);
                for (String theme : SHOW_DIALOG_THEMES) {
                    output.writeByteArray(SHOW_DIALOG_THEME_FIELD, ProtoNode.encode(dialogTheme -> {
                        dialogTheme.writeString(SHOW_DIALOG_THEME_NAME_FIELD, theme);
                        dialogTheme.writeInt32(SHOW_DIALOG_THEME_COLOR_FIELD, SHOW_DIALOG_THEME_COLOR);
                    }));
                }
            }));

            byte[] gestureStates = commandList(COMMAND_LIST_EXTENSION,
                    setState(DRAG_DISTANCE_STATE, vector(0, 0)),
                    setState(TOUCH_POSITION_STATE, vector(NO_TOUCH_POSITION, NO_TOUCH_POSITION)),
                    setState(ZOOMING_STATE, booleanValue()),
                    setState(DRAGGING_STATE, booleanValue()),
                    setState(DRAG_END_STATE, booleanValue()));
            byte[] show = commandList(COMMAND_SEQUENCE_EXTENSION, gestureStates, showDialog);
            if (indexState != null) {
                show = commandList(COMMAND_SEQUENCE_EXTENSION, setState(indexState,
                        ProtoNode.encode(output -> output.writeInt32(STATE_VALUE_INT_FIELD, index))), show);
            }
            return commandList(COMMAND_LIST_EXTENSION, show);
        }

        /**
         * @param indexState Key of the state of the shown image, or null if the post has one image.
         * @return The bindings of the theme, the capabilities, the shown image and the gesture states
         *         to the model, the same as the posts feed has.
         */
        private byte[] buildBindings(@Nullable String indexState) throws IOException {
            byte[] stateBinding = ProtoNode.encodeMessage(STATE_BINDING_EXTENSION, ProtoNode.encode(output -> {
                output.writeInt32(STATE_BINDING_MODEL_FIELD, FULLSCREEN_MODEL_EXTENSION);
                output.writeByteArray(STATE_BINDING_ITEM_FIELD, ProtoNode.encode(item -> {
                    item.writeByteArray(STATE_BINDING_KEY_FIELD, ProtoNode.encodeString(BINDING_KEY_FIELD, themeKey));
                    item.writeInt32(STATE_BINDING_PATH_FIELD, FULLSCREEN_MODEL_CONTEXT_FIELD);
                    item.writeInt32(STATE_BINDING_PATH_FIELD, THEME_FIELD);
                    item.writeInt32(STATE_BINDING_ENTITY_FIELD, THEME_STATE_ENTITY);
                    item.writeInt32(STATE_BINDING_TYPE_FIELD, THEME_STATE_TYPE);
                }));
                output.writeByteArray(STATE_BINDING_ITEM_FIELD, ProtoNode.encode(item -> {
                    item.writeString(STATE_BINDING_KEY_FIELD, capabilitiesKey);
                    item.writeInt32(STATE_BINDING_FIELD_FIELD, FULLSCREEN_MODEL_CAPABILITIES_FIELD);
                    item.writeInt32(STATE_BINDING_PATH_FIELD, FULLSCREEN_MODEL_CAPABILITIES_FIELD);
                    item.writeInt32(STATE_BINDING_TYPE_FIELD, CAPABILITIES_STATE_TYPE);
                }));
                writeStateBinding(output, indexState == null ? "" : indexState, INDEX_STATE_FIELD);
                writeStateBinding(output, TOUCH_POSITION_STATE, TOUCH_POSITION_STATE_FIELD);
                writeStateBinding(output, DRAGGING_STATE, DRAGGING_STATE_FIELD);
                writeStateBinding(output, WINDOW_INSETS_STATE, WINDOW_INSETS_STATE_FIELD);
                writeStateBinding(output, ORIENTATION_STATE, ORIENTATION_STATE_FIELD);
            }));
            byte[] themeBinding = ProtoNode.encodeMessage(THEME_BINDING_EXTENSION, ProtoNode.encode(output -> {
                output.writeInt32(BINDING_MODEL_FIELD, FULLSCREEN_MODEL_EXTENSION);
                output.writeByteArray(BINDING_ENTITY_FIELD, ProtoNode.encode(entity -> {
                    entity.writeByteArray(BINDING_KEY_FIELD, ProtoNode.encodeString(BINDING_KEY_FIELD, themeKey));
                    entity.writeInt32(THEME_BINDING_PATH_FIELD, FULLSCREEN_MODEL_CONTEXT_FIELD);
                    entity.writeInt32(THEME_BINDING_PATH_FIELD, THEME_FIELD);
                }));
            }));
            byte[] capabilitiesBinding = ProtoNode.encodeMessage(CAPABILITIES_BINDING_EXTENSION,
                    ProtoNode.encode(output -> {
                        output.writeInt32(BINDING_MODEL_FIELD, FULLSCREEN_MODEL_EXTENSION);
                        output.writeByteArray(BINDING_ENTITY_FIELD, ProtoNode.encode(entity -> {
                            entity.writeString(BINDING_KEY_FIELD, capabilitiesKey);
                            entity.writeInt32(CAPABILITIES_BINDING_PATH_FIELD, FULLSCREEN_MODEL_CAPABILITIES_FIELD);
                        }));
                    }));
            byte[] modelBinding = ProtoNode.encodeMessage(MODEL_BINDING_EXTENSION, ProtoNode.encode(output -> {
                output.writeInt32(MODEL_BINDING_MODEL_FIELD, FULLSCREEN_MODEL_EXTENSION);
                output.writeByteArray(MODEL_BINDING_FIELDS_FIELD, ProtoNode.encode(fields -> {
                    fields.writeInt32(MODEL_BINDING_FIELD_FIELD, FULLSCREEN_MODEL_CONTEXT_FIELD);
                    fields.writeInt32(MODEL_BINDING_FIELD_FIELD, TOUCH_POSITION_STATE_FIELD);
                }));
                output.writeInt32(MODEL_BINDING_TYPE_FIELD, MODEL_BINDING_TYPE);
            }));
            return ProtoNode.concat(ProtoNode.concat(stateBinding, themeBinding),
                    ProtoNode.concat(capabilitiesBinding, modelBinding));
        }

        private static void writeStateBinding(CodedOutputStream output, String key,
                                              final int field) throws IOException {
            output.writeByteArray(STATE_BINDING_ITEM_FIELD, ProtoNode.encode(item -> {
                item.writeString(STATE_BINDING_KEY_FIELD, key);
                item.writeInt32(STATE_BINDING_FIELD_FIELD, field);
                item.writeInt32(STATE_BINDING_PATH_FIELD, field);
            }));
        }

        private static byte[] setState(String key, byte[] value) throws IOException {
            return ProtoNode.encodeMessage(SET_STATE_EXTENSION, ProtoNode.concat(
                    ProtoNode.encodeString(SET_STATE_KEY_FIELD, key),
                    ProtoNode.encodeMessage(SET_STATE_VALUE_FIELD, value)));
        }

        private static byte[] vector(final float x, final float y) throws IOException {
            return ProtoNode.encode(output -> {
                output.writeFloat(STATE_VALUE_X_FIELD, x);
                output.writeFloat(STATE_VALUE_Y_FIELD, y);
            });
        }

        private static byte[] booleanValue() throws IOException {
            return ProtoNode.encode(output -> output.writeBool(STATE_VALUE_BOOLEAN_FIELD, false));
        }

        /**
         * @param type {@link #COMMAND_LIST_EXTENSION} or {@link #COMMAND_SEQUENCE_EXTENSION}.
         */
        private static byte[] commandList(final int type, byte[]... commands) throws IOException {
            return ProtoNode.encodeMessage(type, ProtoNode.encode(output -> {
                for (byte[] command : commands) {
                    output.writeByteArray(COMMANDS_FIELD, command);
                }
            }));
        }
    }
}
