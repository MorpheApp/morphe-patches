/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4963
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.BooleanState;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.CapabilitiesMapping;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.CapabilitiesSubscriptionConfig;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.Command;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.CommandList;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.ComponentType;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.DialogContent;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.DialogTheme;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.Element;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.ElementModel;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.ElementType;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.EnvironmentFields;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.EnvironmentSubscriptionConfig;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.IntState;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.LightboxCloseButton;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.LightboxContent;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.LightboxModel;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.LockupModel;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.SetStateCommand;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.ShowDialogCommand;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.StateMapping;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.StateSubscriptionConfig;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.SubscriptionConfig;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.TemplateConfig;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.ThemeMapping;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.ThemeSubscriptionConfig;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.UriTemplateConfig;
import app.morphe.extension.youtube.innertube.PostImageLightboxOuterClass.VectorState;
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
            if (replaceCardImageCommands(bytes, root)) {
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
     * @param bytes The element, of which {@code root} is parsed.
     * @return If a command was replaced.
     */
    private static boolean replaceCardImageCommands(byte[] bytes, List<ProtoNode> root) throws IOException {
        List<ProtoNode> images = ProtoNode.findMessages(root, CARD_IMAGE_FIELD);
        if (images.isEmpty()) {
            return false;
        }
        ComponentType card = Element.parseFrom(bytes).getType().getComponentType();
        LockupModel model = card.getModel().getLockupModel();
        SubscriptionConfig subscriptions = card.getSubscriptionConfig();
        ThemeMapping theme = subscriptions.getThemeSubscriptionConfig().getMapping();
        CapabilitiesMapping capabilities = subscriptions.getCapabilitiesSubscriptionConfig().getMapping();
        if (!model.hasContext() || !model.hasEnvironment()
                || !theme.hasIdentifier() || !capabilities.hasIdentifier()) {
            Logger.printDebug(() -> "Post card has no theme or environment");
            return false;
        }
        String uri = Lightbox.TEMPLATE.getUri();
        if (uri == null) {
            // The images still open the posts feed.
            Logger.printDebug(() -> "Fullscreen post image template is not cached");
            return false;
        }

        Lightbox lightbox = new Lightbox(uri, model, theme.getIdentifier(), capabilities.getIdentifier(),
                str("morphe_settings_close"));

        boolean replaced = false;
        for (Map.Entry<ProtoNode, List<ProtoNode>> post : getPostImages(images).entrySet()) {
            String indexState = null;
            if (post.getKey().getFieldNumber() == MULTI_IMAGE_FIELD) {
                ProtoNode stateKey = ProtoNode.field(post.getKey().children, MULTI_IMAGE_STATE_KEY_FIELD);
                if (stateKey == null) {
                    continue;
                }
                indexState = stateKey.decodeText() + FULLSCREEN_INDEX_STATE_SUFFIX;
            }

            List<ProtoNode> postImages = post.getValue();
            // The fullscreen view shows the images without their command.
            List<ByteString> imageBytes = new ArrayList<>(postImages.size());
            for (ProtoNode image : postImages) {
                List<ProtoNode> fields = new ArrayList<>(image.children);
                fields.removeIf(field -> field.getFieldNumber() == CARD_IMAGE_COMMAND_FIELD);
                imageBytes.add(ByteString.copyFrom(ProtoNode.write(fields)));
            }
            Command show = lightbox.show(imageBytes, indexState);
            for (int index = 0; index < postImages.size(); index++) {
                ProtoNode command = ProtoNode.field(postImages.get(index).children, CARD_IMAGE_COMMAND_FIELD);
                if (command != null && opensPostsFeed(command)) {
                    command.setPayload(Lightbox.open(show, index, indexState).toByteArray());
                    replaced = true;
                }
            }
        }
        return replaced;
    }

    /**
     * @return The images of each post, in the order of the post, by the images of a post with many
     *         images or by the image of a post with one image.
     */
    private static Map<ProtoNode, List<ProtoNode>> getPostImages(List<ProtoNode> images) {
        Map<ProtoNode, List<ProtoNode>> posts = new HashMap<>();
        for (ProtoNode image : images) {
            if (image.children == null) {
                continue;
            }
            ProtoNode item = image.getParent();
            ProtoNode multiImage = item == null ? null : item.getParent();
            ProtoNode post = multiImage != null && multiImage.getFieldNumber() == MULTI_IMAGE_FIELD
                    && item.getFieldNumber() == MULTI_IMAGE_ITEM_FIELD
                    ? multiImage
                    : image;
            posts.computeIfAbsent(post, key -> new ArrayList<>()).add(image);
        }
        return posts;
    }

    /**
         * The fullscreen view of the images of a post (post_image_lightbox.eml), shown in a dialog
         * the same as the posts feed shows it, with the context of the model of the card.
         */
        private record Lightbox(String uri, LockupModel cardModel, ByteString themeIdentifier,
                                String capabilitiesIdentifier, String closeLabel) {
            /**
             * Fields of the model that the subscriptions write: the capabilities of the app, the index
             * of the shown image, the theme in the context, and the states of the gestures.
             */
            private static final int CAPABILITIES_FIELD = 4;
            private static final int INDEX_STATE_FIELD = 5;
            private static final int TOUCH_POSITION_STATE_FIELD = 8;
            private static final int DRAGGING_STATE_FIELD = 9;
            private static final int WINDOW_INSETS_STATE_FIELD = 11;
            private static final int ORIENTATION_STATE_FIELD = 12;
            private static final int CONTEXT_THEME_FIELD = 352047819;

            private static final int THEME_STATE_ENTITY = 1;
            private static final int THEME_STATE_TYPE = 1;
            private static final int CAPABILITIES_STATE_TYPE = 2;
            private static final int ENVIRONMENT_SUBSCRIPTION_TYPE = 2;
            private static final int CLOSE_BUTTON_TYPE = 1;
            private static final int DIALOG_THEME_COLOR = 0x1A000000;
            private static final String[] DIALOG_THEMES = {"USER_INTERFACE_THEME_LIGHT", "USER_INTERFACE_THEME_DARK"};

            /**
             * States of the gestures, which are reset before the view is shown.
             */
            private static final String DRAG_DISTANCE_STATE = "post_image_lightbox_drag_distance";
            private static final String TOUCH_POSITION_STATE = "post_image_lightbox_touch_position";
            private static final String ZOOMING_STATE = "post_image_lightbox_is_zooming";
            private static final String DRAGGING_STATE = "post_image_lightbox_is_dragging";
            private static final String DRAG_END_STATE = "post_image_lightbox_is_drag_end";
            private static final String WINDOW_INSETS_STATE = "post_image_lightbox_window_insets";
            private static final String ORIENTATION_STATE = "post_image_lightbox_orientation";
            private static final float NO_TOUCH_POSITION = -1;

            /**
             * Uri of the template, with the hash of the version the app has.
             */
            static final FeedCardLayoutPatch.VideoTemplate TEMPLATE = new FeedCardLayoutPatch.VideoTemplate(
                    "post_image_lightbox.eml", null, ElementModel.LIGHTBOX_FIELD_NUMBER,
                    LightboxModel.CONTEXT_FIELD_NUMBER, CAPABILITIES_FIELD);

            private static final Command RESET_GESTURE_STATES = list(
                    setState(DRAG_DISTANCE_STATE, VectorState.newBuilder().setX(0).setY(0).build().toByteString()),
                    setState(TOUCH_POSITION_STATE, VectorState.newBuilder()
                            .setX(NO_TOUCH_POSITION).setY(NO_TOUCH_POSITION).build().toByteString()),
                    setState(ZOOMING_STATE, BooleanState.newBuilder().setValue(false).build().toByteString()),
                    setState(DRAGGING_STATE, BooleanState.newBuilder().setValue(false).build().toByteString()),
                    setState(DRAG_END_STATE, BooleanState.newBuilder().setValue(false).build().toByteString()));

        /**
             * @param indexState Key of the state of the shown image, or null if the post has one image.
             * @return The command that shows the images in fullscreen.
             */
            Command show(List<ByteString> images, @Nullable String indexState) {
                String indexStateKey = indexState == null ? "" : indexState;
                LightboxModel model = LightboxModel.newBuilder()
                        .setContent(LightboxContent.newBuilder()
                                .addAllImage(images)
                                .setIndexStateKey(indexStateKey)
                                .setCloseButton(LightboxCloseButton.newBuilder()
                                        .setLabel(closeLabel)
                                        .setType(CLOSE_BUTTON_TYPE)))
                        .setEnvironment(cardModel.getEnvironment())
                        // The theme subscription adds the theme to the context when the element is shown.
                        .setContext(cardModel.getContext())
                        .build();
                ComponentType lightbox = ComponentType.newBuilder()
                        .setTemplateConfig(TemplateConfig.newBuilder()
                                .setUriTemplateConfig(UriTemplateConfig.newBuilder().setUri(uri)))
                        .setModel(ElementModel.newBuilder().setLightbox(model))
                        .setSubscriptionConfig(subscriptions(indexStateKey))
                        .build();

                ShowDialogCommand.Builder dialog = ShowDialogCommand.newBuilder()
                        .setContent(DialogContent.newBuilder().setElement(Element.newBuilder()
                                .setType(ElementType.newBuilder().setComponentType(lightbox))))
                        .setFullscreen(true)
                        .setCancelable(true)
                        .setAnimation(0)
                        .setStyle(0);
                for (String theme : DIALOG_THEMES) {
                    dialog.addTheme(DialogTheme.newBuilder().setTheme(theme).setColor(DIALOG_THEME_COLOR));
                }
                return sequence(RESET_GESTURE_STATES, Command.newBuilder().setShowDialog(dialog).build());
            }

            /**
             * @param show       Command of {@link #show}.
             * @param indexState Key of the state of the shown image, or null if the post has one image.
             * @return The command that shows the images in fullscreen, with the image at the index shown.
             */
            static Command open(Command show, int index, @Nullable String indexState) {
                if (indexState != null) {
                    show = sequence(setState(indexState, IntState.newBuilder().setValue(index).build().toByteString()), show);
                }
                return list(show);
            }

            /**
             * @return The subscriptions of the theme, the capabilities, the shown image and the gesture
             * states, the same as the posts feed has.
             */
            private SubscriptionConfig subscriptions(String indexStateKey) {
                final int modelField = ElementModel.LIGHTBOX_FIELD_NUMBER;
                final int contextField = LightboxModel.CONTEXT_FIELD_NUMBER;
                return SubscriptionConfig.newBuilder()
                        .setStateSubscriptionConfig(StateSubscriptionConfig.newBuilder()
                                .setModelField(modelField)
                                .addMapping(StateMapping.newBuilder()
                                        .setKey(themeIdentifier)
                                        .addPath(contextField)
                                        .addPath(CONTEXT_THEME_FIELD)
                                        .setEntity(THEME_STATE_ENTITY)
                                        .setType(THEME_STATE_TYPE))
                                .addMapping(stateMapping(capabilitiesIdentifier, CAPABILITIES_FIELD)
                                        .setType(CAPABILITIES_STATE_TYPE))
                                .addMapping(stateMapping(indexStateKey, INDEX_STATE_FIELD))
                                .addMapping(stateMapping(TOUCH_POSITION_STATE, TOUCH_POSITION_STATE_FIELD))
                                .addMapping(stateMapping(DRAGGING_STATE, DRAGGING_STATE_FIELD))
                                .addMapping(stateMapping(WINDOW_INSETS_STATE, WINDOW_INSETS_STATE_FIELD))
                                .addMapping(stateMapping(ORIENTATION_STATE, ORIENTATION_STATE_FIELD)))
                        .setThemeSubscriptionConfig(ThemeSubscriptionConfig.newBuilder()
                                .setModelField(modelField)
                                .setMapping(ThemeMapping.newBuilder()
                                        .setIdentifier(themeIdentifier)
                                        .addPath(contextField)
                                        .addPath(CONTEXT_THEME_FIELD)))
                        .setCapabilitiesSubscriptionConfig(CapabilitiesSubscriptionConfig.newBuilder()
                                .setModelField(modelField)
                                .setMapping(CapabilitiesMapping.newBuilder()
                                        .setIdentifier(capabilitiesIdentifier)
                                        .setResultField(CAPABILITIES_FIELD)))
                        .setEnvironmentSubscriptionConfig(EnvironmentSubscriptionConfig.newBuilder()
                                .setModelField(modelField)
                                .setFields(EnvironmentFields.newBuilder()
                                        .addField(contextField)
                                        .addField(TOUCH_POSITION_STATE_FIELD))
                                .setType(ENVIRONMENT_SUBSCRIPTION_TYPE))
                        .build();
            }

            /**
             * @return Mapping of the state to the field of the model with the same field number.
             */
            private static StateMapping.Builder stateMapping(String key, int field) {
                return StateMapping.newBuilder()
                        .setKey(ByteString.copyFromUtf8(key))
                        .setField(field)
                        .addPath(field);
            }

            /**
             * @param value Serialized state. Not a MessageLite parameter, because the app has its own
             *              MessageLite interface without toByteString().
             */
            private static Command setState(String key, ByteString value) {
                return Command.newBuilder().setSetState(SetStateCommand.newBuilder()
                        .setKey(key)
                        .setValue(value)).build();
            }

            private static Command list(Command... commands) {
                return Command.newBuilder().setCommandList(CommandList.newBuilder()
                        .addAllCommands(Arrays.asList(commands))).build();
            }

            private static Command sequence(Command... commands) {
                return Command.newBuilder().setCommandSequence(CommandList.newBuilder()
                        .addAllCommands(Arrays.asList(commands))).build();
            }
        }
}
