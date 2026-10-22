/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;

/**
 * Shows only the comments that contain the searched words.
 * <p>
 * YouTube has no search of comments, so only the comments that are loaded are searched.
 * Comments are filtered when they are loaded, so a search applies to the comments loaded after it,
 * such as after changing the sort order or opening the comments again.
 * As the hidden comments do not fill the list, scrolling loads the next comments sooner.
 */
@SuppressWarnings("unused")
public final class CommentsSearchFilter extends Filter {

    private static final String SEARCH_BUTTON_TAG = "morphe_search_comments_button";

    private static final int ID_CLOSE_BUTTON =
            ResourceUtils.getIdentifier(ResourceType.ID, "close_button");

    /**
     * Words of the search, all of which a comment must contain, or empty if not searching.
     */
    private static volatile List<String> searchWords = Collections.emptyList();
    private static volatile String searchText = "";
    /**
     * The search applies only to the comments of the video it was made for.
     */
    private static volatile String searchVideoId = "";

    /**
     * The buffer of a comment is the same for each of its components,
     * so the result of the last buffer searched by each thread is reused.
     */
    private static final ThreadLocal<SearchResult> lastResult = new ThreadLocal<>();

    private record SearchResult(byte[] buffer, List<String> words, boolean matches) {
    }

    private final StringFilterGroup commentThread = new StringFilterGroup(
            Settings.SEARCH_COMMENTS,
            "comment_thread.eml"
    );

    public CommentsSearchFilter() {
        addPathCallbacks(commentThread);
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              CharSequence path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        List<String> words = searchWords;
        if (words.isEmpty() || buffer.length == 0 || !isSearchForCurrentVideo()) {
            return false;
        }

        SearchResult result = lastResult.get();
        if (result == null || result.buffer != buffer || result.words != words) {
            // The buffer is not text, but the text of the comment is in it as UTF-8.
            String text = new String(buffer, StandardCharsets.UTF_8).toLowerCase(Locale.getDefault());
            result = new SearchResult(buffer, words, containsAllWords(text, words));
            lastResult.set(result);
        }

        return !result.matches;
    }

    private static boolean containsAllWords(String text, List<String> words) {
        for (String word : words) {
            if (!text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSearchForCurrentVideo() {
        return searchVideoId.equals(VideoInformation.getVideoId());
    }

    private static boolean isSearching() {
        return !searchWords.isEmpty() && isSearchForCurrentVideo();
    }

    private static void setSearch(String text) {
        text = text.trim();
        List<String> words = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.getDefault()).split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }

        // Set the video first, so a filter thread does not apply the words to another video.
        searchVideoId = VideoInformation.getVideoId();
        searchText = text;
        searchWords = Collections.unmodifiableList(words);
        Logger.printDebug(() -> "Comments search: " + words);
    }

    /**
     * Injection point.
     * Called on the main thread when the header of an engagement panel is shown.
     */
    public static void onPanelSubheaderShown(View subheader) {
        if (subheader == null || !Settings.SEARCH_COMMENTS.get() || ID_CLOSE_BUTTON == 0) {
            return;
        }

        // The panel is opened after its header is set up.
        subheader.post(() -> {
            try {
                updateSearchButton(subheader);
            } catch (Exception ex) {
                Logger.printException(() -> "updateSearchButton failure", ex);
            }
        });
    }

    private static void updateSearchButton(View subheader) {
        View closeButton = findCloseButton(subheader);
        if (closeButton == null || !(closeButton.getParent() instanceof ViewGroup header)) {
            Logger.printDebug(() -> "Panel close button not found");
            return;
        }

        View searchButton = header.findViewWithTag(SEARCH_BUTTON_TAG);
        if (searchButton == null) {
            searchButton = createSearchButton(header, closeButton);
            if (searchButton == null) {
                return;
            }
        }

        final boolean isCommentsPanel = isCommentsPanelOpen();
        searchButton.setVisibility(isCommentsPanel ? View.VISIBLE : View.GONE);
        if (isCommentsPanel) {
            updateSearchButtonIcon(searchButton);
        }
    }

    /**
     * The header of the panel holds the close button, and the subheader is below it.
     */
    @Nullable
    private static View findCloseButton(View subheader) {
        ViewParent parent = subheader.getParent();
        while (parent instanceof ViewGroup group) {
            View closeButton = group.findViewById(ID_CLOSE_BUTTON);
            if (closeButton != null) {
                return closeButton;
            }
            parent = group.getParent();
        }
        return null;
    }

    private static boolean isCommentsPanelOpen() {
        String panelId = EngagementPanel.getLastOpenedPanelId();
        // The comments of videos and Shorts, and the replies of a comment.
        return panelId != null && panelId.contains("comment");
    }

    @Nullable
    private static View createSearchButton(ViewGroup header, View closeButton) {
        Context context = header.getContext();
        Drawable icon = ResourceUtils.getDrawable("morphe_search_comments_button");
        if (icon == null) {
            Logger.printException(() -> "Search comments drawable not found");
            return null;
        }

        ViewGroup.LayoutParams params;
        if (header instanceof LinearLayout) {
            LinearLayout.LayoutParams linearParams = new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48);
            linearParams.gravity = Gravity.CENTER_VERTICAL;
            params = linearParams;
        } else if (header instanceof RelativeLayout && closeButton.getId() != View.NO_ID) {
            RelativeLayout.LayoutParams relativeParams = new RelativeLayout.LayoutParams(Dim.dp48, Dim.dp48);
            relativeParams.addRule(RelativeLayout.START_OF, closeButton.getId());
            relativeParams.addRule(RelativeLayout.CENTER_VERTICAL);
            params = relativeParams;
        } else {
            Logger.printDebug(() -> "Unsupported panel header layout: " + header.getClass().getName());
            return null;
        }

        FrameLayout button = new FrameLayout(context);
        button.setTag(SEARCH_BUTTON_TAG);
        button.setContentDescription(str("morphe_search_comments_dialog_title"));
        button.setClickable(true);
        button.setFocusable(true);

        ImageView iconView = new ImageView(context);
        iconView.setImageDrawable(icon.mutate());
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.addView(iconView, new FrameLayout.LayoutParams(Dim.dp24, Dim.dp24, Gravity.CENTER));
        button.setOnClickListener(view -> showSearchDialog(view.getContext(), view));

        header.addView(button, Math.max(header.indexOfChild(closeButton), 0), params);
        return button;
    }

    /**
     * The icon is filled while searching, so it's clear why comments are missing.
     */
    private static void updateSearchButtonIcon(View searchButton) {
        if (searchButton instanceof ViewGroup group && group.getChildAt(0) instanceof ImageView iconView) {
            Drawable icon = iconView.getDrawable();
            if (icon != null) {
                icon.setTint(ThemeUtils.getAppForegroundColor());
                iconView.setAlpha(isSearching() ? 1.0f : 0.6f);
            }
        }
    }

    private static void showSearchDialog(Context context, View searchButton) {
        try {
            EditText editText = CustomDialog.createEditText(context);
            editText.setHint(str("morphe_search_comments_hint"));
            editText.setSingleLine(true);
            editText.setInputType(InputType.TYPE_CLASS_TEXT);
            String text = isSearching() ? searchText : "";
            editText.setText(text);
            editText.setSelection(text.length());

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_search_comments_dialog_title"),
                    null,
                    editText,
                    str("morphe_search_comments_search"),
                    () -> {
                        String newText = editText.getText().toString();
                        setSearch(newText);
                        updateSearchButtonIcon(searchButton);
                        Utils.showToastLong(str(newText.isBlank()
                                ? "morphe_search_comments_cleared"
                                : "morphe_search_comments_applied"));
                    },
                    () -> {},
                    str("morphe_search_comments_clear"),
                    () -> {
                        setSearch("");
                        updateSearchButtonIcon(searchButton);
                        Utils.showToastLong(str("morphe_search_comments_cleared"));
                    },
                    true
            );
            dialogPair.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showSearchDialog failure", ex);
        }
    }
}
