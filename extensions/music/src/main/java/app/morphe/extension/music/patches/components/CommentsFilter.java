/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import android.view.View;
import android.widget.LinearLayout;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

@SuppressWarnings("unused")
public final class CommentsFilter extends Filter {

    private final StringFilterGroup commentComposer;
    private final StringFilterGroup emojiButton;

    public CommentsFilter() {
        commentComposer = new StringFilterGroup(
                null,
                "comment_composer.e"
        );

        final StringFilterGroup communityGuidelines = new StringFilterGroup(
                Settings.HIDE_COMMENTS_COMMUNITY_GUIDELINES,
                "community_guidelines.e"
        );

        final StringFilterGroup commentsContext = new StringFilterGroup(
                Settings.HIDE_COMMENTS_CONTEXT,
                "comment_filter_context.e"
        );

        emojiButton = new StringFilterGroup(
                Settings.HIDE_COMMENTS_EMOJI_BUTTON,
                "id.comment.quick_emoji.button"
        );

        final StringFilterGroup timestampButton = new StringFilterGroup(
                Settings.HIDE_COMMENTS_TIMESTAMP_BUTTON,
                "composer_timestamp_button.e"
        );

        addPathCallbacks(
                commentComposer,
                commentsContext,
                communityGuidelines,
                emojiButton,
                timestampButton
        );
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
        if (matchedGroup == commentComposer) {
            return emojiButton.check(accessibility).isFiltered();
        }

        return true;
    }

    /**
     * Injection point.
     */
    public static void hideCommentsInfoButton(View view) {
        if (Settings.HIDE_COMMENTS_INFO_BUTTON.get()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0);
            view.setLayoutParams(lp);
            view.setVisibility(View.GONE);
        }
    }
}
