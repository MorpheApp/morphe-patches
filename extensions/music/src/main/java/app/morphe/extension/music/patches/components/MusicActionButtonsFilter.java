/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.TreeNodeElementPatch.LithoGetBufferContainerInterface;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.settings.BooleanSetting;

@SuppressWarnings("unused")
public final class MusicActionButtonsFilter extends Filter {

    /**
     * Injected by the patch onto the obfuscated litho message class that owns the button's
     * serialized proto buffer, so extension code can read it without walking obfuscated
     * field / method names.
     */
    public interface ButtonProtoBufferInterface {
        // Method is added during patching.
        byte[] patch_getBuffer();
    }

    private enum ActionButton {
        LIKE_DISLIKE(Settings.HIDE_LIKE_DISLIKE_BUTTON),
        DOWNLOAD(Settings.HIDE_DOWNLOAD_BUTTON),
        COMMENTS(Settings.HIDE_COMMENTS_BUTTON),
        LYRICS(Settings.HIDE_LYRICS_BUTTON),
        SHARE(Settings.HIDE_SHARE_BUTTON),
        RADIO(Settings.HIDE_RADIO_BUTTON),
        SAVE(Settings.HIDE_SAVE_BUTTON);

        final BooleanSetting setting;

        ActionButton(BooleanSetting setting) {
            this.setting = setting;
        }
    }

    private static final String VIDEO_ACTION_BAR_PREFIX = "video_action_bar.e";
    private static final String VIDEO_ACTION_BUTTON_WRAPPER_PREFIX = "video_action_button_with_vm_input.e";

    // Base names of the litho components / endpoint identifiers used to identify buttons.
    // Path callbacks append `.e` to bound the match to the litho identifier suffix.
    private static final String LIKE_BUTTON_MARKER = "like_button";
    private static final String DISLIKE_BUTTON_MARKER = "dislike_button";
    private static final String SEGMENTED_LIKE_DISLIKE_MARKER = "segmented_like_dislike_button";
    private static final String DOWNLOAD_MARKER = "download_button";
    private static final String MUSIC_DOWNLOAD_MARKER = "music_download_button";
    // Unique marker for the Download button in the parsed proto buffer - the litho EML
    // name `music_download_button` is a render-time artifact and is not present in the raw
    // proto stream that reaches the tree-node hook. Music's offline endpoint identifier
    // (`offlinelist`) is the only download-specific string that survives into the buffer.
    private static final String DOWNLOAD_PROTO_MARKER = "offlinelist";
    private static final String COMMENTS_MARKER = "music-comment-panel";
    private static final String LYRICS_MARKER = "music_watch_lyrics_panel";
    private static final String SHARE_MARKER = "timestamp_share_switch_button_entity_key";
    private static final String RADIO_MARKER = "RDAMVM";

    // Icon markers used as a fallback when a button is disabled for the current track and
    // therefore has no endpoint id in its proto buffer. Every button still carries its own
    // icon as data, and the icon lives in the first ~500 bytes of the buffer, well before
    // the shared icon catalogue that trails at the end.
    private static final String COMMENTS_ICON = "_text_bubble_";
    private static final String LYRICS_ICON = "_quote_";
    private static final String THUMB_UP_ICON = "_thumb_up_";
    private static final String THUMB_DOWN_ICON = "_thumb_down_";
    private static final int ICON_SCAN_HEAD_LIMIT = 500;

    private final StringFilterGroup actionBar;
    private final StringFilterGroup genericActionButton;

    public MusicActionButtonsFilter() {
        actionBar = new StringFilterGroup(
                Settings.HIDE_ACTION_BAR,
                VIDEO_ACTION_BAR_PREFIX
        );
        addIdentifierCallbacks(actionBar);

        addPathCallbacks(
                new StringFilterGroup(
                        Settings.HIDE_LIKE_DISLIKE_BUTTON,
                        LIKE_BUTTON_MARKER + ".e",
                        DISLIKE_BUTTON_MARKER + ".e",
                        SEGMENTED_LIKE_DISLIKE_MARKER + ".e"
                ),
                new StringFilterGroup(
                        Settings.HIDE_DOWNLOAD_BUTTON,
                        DOWNLOAD_MARKER + ".e",
                        MUSIC_DOWNLOAD_MARKER + ".e"
                )
        );

        // Comments, Lyrics, Share, Save and Radio all render as generic `button.e` inside
        // `video_action_button_with_vm_input.e` - identity is dispatched inside isFiltered
        // from the button's ascii-string buffer.
        genericActionButton = new StringFilterGroup(
                null,
                VIDEO_ACTION_BUTTON_WRAPPER_PREFIX
        );
        addPathCallbacks(genericActionButton);
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              String path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        if (matchedGroup == actionBar) {
            return true;
        }
        if (matchedGroup == genericActionButton) {
            if (!path.contains(VIDEO_ACTION_BAR_PREFIX)) {
                return false;
            }
            // Wrapper- and inner-level renders embed sibling data in their proto buffer, so
            // a single marker would match the whole action bar. Only fire on the leaf-button
            // render - path contains `|button.eml-fe|` but not the `button_inner` descendant.
            if (!path.contains("|button.eml-fe|") || path.contains("button_inner")) {
                return false;
            }
            // Dispatch by endpoint marker. Each of the four known buttons has a unique
            // endpoint string in its buffer. The Save button has no unique endpoint of its
            // own - its icon (`_playlist_add_`) also appears in every button's shared icon
            // catalogue - so it is resolved by elimination as the fall-through branch.
            String strings = asciiStrings.getStrings();
            if (strings.contains(COMMENTS_MARKER)) {
                return Settings.HIDE_COMMENTS_BUTTON.get();
            }
            if (strings.contains(LYRICS_MARKER)) {
                return Settings.HIDE_LYRICS_BUTTON.get();
            }
            if (strings.contains(SHARE_MARKER)) {
                return Settings.HIDE_SHARE_BUTTON.get();
            }
            if (strings.contains(RADIO_MARKER)) {
                return Settings.HIDE_RADIO_BUTTON.get();
            }
            return Settings.HIDE_SAVE_BUTTON.get();
        }
        return path.contains(VIDEO_ACTION_BAR_PREFIX);
    }

    /**
     * Injection point.
     * Physically removes entries from the action-bar tree-node list so Litho never allocates
     * their cells. Without this the litho identifier / path filters above only skip content
     * and leave empty gaps where hidden buttons used to be.
     * <p>
     * The whole-bar toggle clears the list outright; per-button hides pull each item's proto
     * payload via {@link #extractButtonProto} (patch-injected interface reached through a
     * reflection walk) and dispatch on the same endpoint markers that {@link #isFiltered}
     * uses for visual hiding.
     */
    public static void onLazilyConvertedElementLoaded(String identifier, List<Object> treeNodeResultList) {
        try {
            if (!identifier.startsWith(VIDEO_ACTION_BAR_PREFIX)) {
                return;
            }
            if (Settings.HIDE_ACTION_BAR.get()) {
                treeNodeResultList.clear();
                return;
            }
            for (int i = treeNodeResultList.size() - 1; i >= 0; i--) {
                byte[] buttonProto = extractButtonProto(treeNodeResultList.get(i));
                if (buttonProto == null) continue;
                ActionButton button = classify(buttonProto);
                if (!button.setting.get()) continue;
                // Music bakes the row's leading padding into item[0], so physically removing
                // the first entry collapses the whole row against the screen edge. Keep it in
                // the list and let isFiltered() do a visual-only hide instead - the empty cell
                // preserves the padding.
                if (i == 0 && button == ActionButton.LIKE_DISLIKE) continue;
                treeNodeResultList.remove(i);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLazilyConvertedElementLoaded failure", ex);
        }
    }

    @Nullable
    private static byte[] extractButtonProto(@Nullable Object item) {
        if (item instanceof ButtonProtoBufferInterface holder) {
            return holder.patch_getBuffer();
        }
        if (item instanceof LithoGetBufferContainerInterface bufferInterface) {
            return extractButtonProto(bufferInterface.patch_getContainer());
        }

        return null;
    }

    private static ActionButton classify(byte[] buffer) {
        String contents = new String(buffer, StandardCharsets.ISO_8859_1);
        // Order matters - the more-specific `segmented_like_dislike_button` and
        // `music_download_button` come before `like_button` / `download_button` so the
        // substring `like_button` inside `segmented_like_dislike_button` doesn't win.
        if (contents.contains(SEGMENTED_LIKE_DISLIKE_MARKER)
                || contents.contains(LIKE_BUTTON_MARKER)
                || contents.contains(DISLIKE_BUTTON_MARKER)) {
            return ActionButton.LIKE_DISLIKE;
        }
        if (contents.contains(MUSIC_DOWNLOAD_MARKER)
                || contents.contains(DOWNLOAD_MARKER)
                || contents.contains(DOWNLOAD_PROTO_MARKER)) {
            return ActionButton.DOWNLOAD;
        }
        if (contents.contains(COMMENTS_MARKER)) return ActionButton.COMMENTS;
        if (contents.contains(LYRICS_MARKER)) return ActionButton.LYRICS;
        if (contents.contains(SHARE_MARKER)) return ActionButton.SHARE;
        if (contents.contains(RADIO_MARKER)) return ActionButton.RADIO;
        // Fallback for buttons that are disabled for this track: no endpoint id is present,
        // so classify by the button's own icon which sits in the buffer head, before the
        // shared icon catalogue.
        String head = contents.length() > ICON_SCAN_HEAD_LIMIT
                ? contents.substring(0, ICON_SCAN_HEAD_LIMIT) : contents;
        if (head.contains(THUMB_UP_ICON) || head.contains(THUMB_DOWN_ICON)) {
            return ActionButton.LIKE_DISLIKE;
        }
        if (head.contains(COMMENTS_ICON)) return ActionButton.COMMENTS;
        if (head.contains(LYRICS_ICON)) return ActionButton.LYRICS;
        // Save button has no unique endpoint marker of its own; it's the fall-through.
        return ActionButton.SAVE;
    }
}
