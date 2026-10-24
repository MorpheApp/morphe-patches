/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3942
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Comments are filtered when they are loaded, so after a search the comments are reloaded,
 * as when the sort order is changed.
 * As the hidden comments do not fill the list, scrolling loads the next comments sooner.
 */
@SuppressWarnings("unused")
public final class CommentsSearchFilter extends Filter {

    /**
     * Added during patching to the controller of section lists, such as the list of the comments panel.
     */
    public interface SectionListAccess {
        View patch_getListView();

        /**
         * Reloads the list as when its sort order is changed, if the list can be reloaded.
         *
         * @return If the list is reloaded.
         */
        boolean patch_reload();
    }

    private static final String SEARCH_BUTTON_TAG = "morphe_search_comments_button";

    private static final int ID_CLOSE_BUTTON =
            ResourceUtils.getIdentifier(ResourceType.ID, "close_button");

    /**
     * Words and phrases of the search, all of which a comment must contain, or empty if not searching.
     */
    private static volatile List<String> searchTerms = Collections.emptyList();
    private static volatile String searchText = "";
    /**
     * The search applies only to the comments of the video it was made for.
     */
    private static volatile String searchVideoId = "";

    private static final String COMMENT_THREAD_PATH = "comment_thread.eml";

    /**
     * Shortest text of a buffer that is taken as an id, such as the id of a comment
     * or the token that loads its replies.
     */
    private static final int MIN_ID_LENGTH = 24;
    private static final int MAX_IDS_PER_COMMENT = 40;
    private static final int MAX_IDS = 4000;

    private static final Pattern SEARCH_TERM_PATTERN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    /**
     * The comment of each thread is laid out from its outermost component to its innermost.
     * Each component has a buffer of its own, and only the buffer of the outermost component
     * holds all of the comment, such as its author and its text. The comment is shown or hidden
     * as a whole by the outermost component, and its inner components are never hidden alone.
     * <p>
     * Litho lays out on more than one thread, so each thread keeps its own comment.
     */
    private static final ThreadLocal<CommentDecision> lastComment = new ThreadLocal<>();

    /**
     * Comments of the ids found in their buffers. The button that shows the replies of a comment
     * is not inside the comment, and it's found by the ids it shares with its comment,
     * as it can be laid out apart from its comment, such as when scrolling back to it.
     */
    private static final Map<String, CommentDecision> commentIds = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(MAX_IDS));

    /**
     * Section lists by their list view, to reload the comments of the panel that is searched.
     */
    private static final Map<View, WeakReference<SectionListAccess>> sectionLists =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Marks an id found in comments that are shown and in comments that are hidden,
     * such as an id of the layout, which tells nothing of the comment.
     */
    private static final CommentDecision AMBIGUOUS_ID = new CommentDecision("", Collections.emptyList(), false);

    private static final class CommentDecision {
        final String path;
        final List<String> terms;
        final boolean hidden;
        /**
         * If the replies of the hidden comment, which are laid out after it, are not yet hidden.
         */
        boolean hideReplies;

        CommentDecision(String path, List<String> terms, boolean hidden) {
            this.path = path;
            this.terms = terms;
            this.hidden = hidden;
            this.hideReplies = hidden;
        }
    }

    private final StringFilterGroup commentThread = new StringFilterGroup(
            Settings.SEARCH_COMMENTS,
            COMMENT_THREAD_PATH
    );

    /**
     * The button that shows the replies of a comment is not inside the comment.
     */
    private final StringFilterGroup commentReplies = new StringFilterGroup(
            Settings.SEARCH_COMMENTS,
            "repl"
    );

    public CommentsSearchFilter() {
        addPathCallbacks(commentThread, commentReplies);
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
        List<String> terms = searchTerms;
        if (terms.isEmpty() || !isSearchForCurrentVideo()) {
            return false;
        }

        CommentDecision last = lastComment.get();

        if (matchedGroup == commentReplies) {
            if (Utils.contains(path, COMMENT_THREAD_PATH)) {
                return false; // Part of a comment.
            }
            return isRepliesHidden(path, buffer, terms, last);
        }

        if (last != null && last.terms == terms
                && path.length() > last.path.length() && Utils.startsWith(path, last.path)) {
            // Inner component of the comment that was already searched.
            return last.hidden;
        }

        if (buffer.length == 0) {
            return false; // Nothing to search, so the comment is left for its inner components.
        }

        // The buffer is not text, but the text of the comment is in it as UTF-8.
        String text = foldCase(new String(buffer, StandardCharsets.UTF_8));
        final boolean hidden = !containsAllTerms(text, terms);
        String commentPath = path.toString();
        CommentDecision decision = new CommentDecision(commentPath, terms, hidden);
        lastComment.set(decision);
        rememberIds(buffer, decision);
        Logger.printDebug(() -> (hidden ? "Hiding comment: " : "Showing comment: ") + commentPath);
        return hidden;
    }

    private static boolean isRepliesHidden(CharSequence path, byte[] buffer,
                                           List<String> terms, @Nullable CommentDecision last) {
        CommentDecision comment = findCommentOfReplies(buffer, terms);
        if (comment == null) {
            // No id is shared with a comment, so the replies are of the comment laid out before them.
            if (last == null || last.terms != terms || !last.hideReplies) {
                return false;
            }
            comment = last;
        } else if (!comment.hidden) {
            return false;
        }

        comment.hideReplies = false;
        String repliesPath = path.toString();
        Logger.printDebug(() -> "Hiding replies of hidden comment: " + repliesPath);
        return true;
    }

    /**
     * @return The comment that shares an id with the replies, preferring a shown comment
     *         so the replies of a shown comment are never hidden, or null if none is found.
     */
    @Nullable
    private static CommentDecision findCommentOfReplies(byte[] buffer, List<String> terms) {
        CommentDecision hiddenComment = null;
        for (String id : findIds(buffer)) {
            CommentDecision decision = commentIds.get(id);
            if (decision == null || decision == AMBIGUOUS_ID || decision.terms != terms) {
                continue;
            }
            if (!decision.hidden) {
                return decision;
            }
            hiddenComment = decision;
        }
        return hiddenComment;
    }

    private static void rememberIds(byte[] buffer, CommentDecision decision) {
        List<String> ids = findIds(buffer);
        synchronized (commentIds) {
            for (String id : ids) {
                CommentDecision existing = commentIds.get(id);
                if (existing == AMBIGUOUS_ID) {
                    continue; // Stays ambiguous until the search changes.
                }
                if (existing == null || existing.terms != decision.terms
                        // The same comment, such as when scrolled back to, or a comment of the same result.
                        || existing.hidden == decision.hidden) {
                    commentIds.put(id, decision);
                } else {
                    commentIds.put(id, AMBIGUOUS_ID);
                }
            }
        }
    }

    /**
     * @return Texts of the buffer that look like ids, such as the id of the comment.
     */
    private static List<String> findIds(byte[] buffer) {
        List<String> ids = new ArrayList<>();
        int start = -1;
        for (int i = 0, length = buffer.length; i <= length; i++) {
            if (i < length && isIdCharacter(buffer[i])) {
                if (start < 0) start = i;
                continue;
            }
            if (start >= 0) {
                if (i - start >= MIN_ID_LENGTH) {
                    ids.add(new String(buffer, start, i - start, StandardCharsets.US_ASCII));
                    if (ids.size() >= MAX_IDS_PER_COMMENT) break;
                }
                start = -1;
            }
        }
        return ids;
    }

    private static boolean isIdCharacter(byte b) {
        return (b >= 'A' && b <= 'Z')
                || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9')
                || b == '-' || b == '_';
    }

    private static boolean containsAllTerms(String text, List<String> terms) {
        for (String term : terms) {
            if (!text.contains(term)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ignores the case, and the dotted and dotless i of Turkish, whatever the language of the device.
     * Otherwise, with a Turkish device "IPHONE" is not found when searching "iphone".
     */
    private static String foldCase(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace("̇", "") // Combining dot above, left by lowercasing the dotted capital I.
                .replace('ı', 'i'); // Dotless small i.
    }

    /**
     * @return Words and quoted phrases of the search, all of which a comment must contain.
     */
    private static List<String> parseSearch(String text) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = SEARCH_TERM_PATTERN.matcher(foldCase(text));
        while (matcher.find()) {
            String phrase = matcher.group(1);
            String term = phrase != null
                    ? phrase.trim().replaceAll("\\s+", " ")
                    // An unclosed quote is ignored.
                    : matcher.group(2).replace("\"", "");
            if (!term.isEmpty()) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static boolean isSearchForCurrentVideo() {
        return searchVideoId.equals(VideoInformation.getVideoId());
    }

    private static boolean isSearching() {
        return !searchTerms.isEmpty() && isSearchForCurrentVideo();
    }

    private static void setSearch(String text) {
        text = text.trim();
        List<String> terms = parseSearch(text);

        // Set the video first, so a filter thread does not apply the terms to another video.
        searchVideoId = VideoInformation.getVideoId();
        searchText = text;
        searchTerms = Collections.unmodifiableList(terms);
        commentIds.clear();
        Logger.printDebug(() -> "Comments search: " + terms);
    }

    /**
     * Injection point.
     * Called when the controller of a section list is created.
     */
    public static void onSectionListCreated(SectionListAccess sectionList) {
        if (!Settings.SEARCH_COMMENTS.get()) {
            return;
        }

        try {
            View listView = sectionList.patch_getListView();
            if (listView != null) {
                sectionLists.put(listView, new WeakReference<>(sectionList));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSectionListCreated failure", ex);
        }
    }

    /**
     * Reloads the list of the panel of the search button, as when its sort order is changed,
     * so the search applies to all of its comments.
     *
     * @return If the list is reloaded.
     */
    private static boolean reloadPanelList(View searchButton) {
        SectionListAccess sectionList = findPanelList(searchButton);
        if (sectionList == null) {
            Logger.printDebug(() -> "Panel list not found");
            return false;
        }
        return sectionList.patch_reload();
    }

    /**
     * @return The shown section list closest to the view, which is the list of the panel of the view.
     */
    @Nullable
    private static SectionListAccess findPanelList(View view) {
        List<ViewParent> ancestors = new ArrayList<>();
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            ancestors.add(parent);
        }

        SectionListAccess closestList = null;
        int closestDistance = Integer.MAX_VALUE;
        synchronized (sectionLists) {
            for (Map.Entry<View, WeakReference<SectionListAccess>> entry : sectionLists.entrySet()) {
                View listView = entry.getKey();
                SectionListAccess sectionList = entry.getValue().get();
                if (sectionList == null || !listView.isShown()) {
                    continue;
                }
                // The distance to the first ancestor of the view that is also an ancestor of the list.
                for (ViewParent parent = listView.getParent(); parent != null; parent = parent.getParent()) {
                    final int distance = ancestors.indexOf(parent);
                    if (distance >= 0) {
                        if (distance < closestDistance) {
                            closestDistance = distance;
                            closestList = sectionList;
                        }
                        break;
                    }
                }
            }
        }
        return closestList;
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
                        if (!reloadPanelList(searchButton)) {
                            // Such as only quotes, which are not a search.
                            Utils.showToastLong(str(!isSearching()
                                    ? "morphe_search_comments_cleared"
                                    : "morphe_search_comments_applied"));
                        }
                    },
                    () -> {},
                    str("morphe_search_comments_clear"),
                    () -> {
                        setSearch("");
                        updateSearchButtonIcon(searchButton);
                        if (!reloadPanelList(searchButton)) {
                            Utils.showToastLong(str("morphe_search_comments_cleared"));
                        }
                    },
                    true
            );
            Dialog dialog = dialogPair.first;
            Window window = dialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
            editText.requestFocus();
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showSearchDialog failure", ex);
        }
    }
}
