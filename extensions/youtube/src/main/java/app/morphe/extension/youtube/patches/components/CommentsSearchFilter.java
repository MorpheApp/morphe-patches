/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3942
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.SearchDialog;
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

    private static final String SEARCH_BUTTON_TAG = "morphe_comment_search_button";

    /**
     * Start of the ids of the comments panels of videos, posts and Shorts. Newer app versions use
     * the first id for videos, and the id of the panel of a post ends with the id of the post.
     * Other panels with "comment" in their id are not searched, such as the replies of a comment,
     * which open in a panel of their own.
     */
    private static final String[] COMMENTS_PANEL_ID_PREFIXES = {
            "comment-item-section",
            "engagement-panel-comments-section",
            "post-comments-",
            "shorts-comments-panel"
    };

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
     * The last comment searched, whose replies can be laid out after it outside the comment.
     * <p>
     * Litho lays out on more than one thread, so each thread keeps its own comment.
     */
    private static final ThreadLocal<CommentDecision> lastComment = new ThreadLocal<>();

    /**
     * Comments of the ids found in their buffers. On some app versions the button that shows the replies
     * of a comment is not inside the comment, and it's found by the ids it shares with its comment,
     * as it can be laid out apart from its comment, such as when scrolling back to it.
     */
    private static final Map<String, CommentDecision> commentIds = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(MAX_IDS));

    /**
     * If a button that shows the replies of a comment was laid out outside the comments. Until then,
     * the ids of the comments are not needed, and they are found only for the comments already searched
     * when the first button is laid out, as finding them takes most of the time of a search of a comment.
     */
    private static volatile boolean repliesOutsideComments;

    private static final int MAX_SEARCHED_COMMENTS = 50;

    /**
     * Comments already searched, by the hash of their buffer. Litho lays out a comment again with a new buffer
     * of the same content, such as on the main thread when the layout made on another thread is not ready,
     * or when the comment is scrolled back to.
     */
    private static final Map<Integer, SearchedComment> searchedComments = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(MAX_SEARCHED_COMMENTS));

    private static final class SearchedComment {
        final byte[] buffer;
        final List<String> terms;
        final boolean hidden;
        /**
         * Found when they are first needed, see {@link #repliesOutsideComments}.
         */
        @Nullable
        volatile List<String> ids;
        /**
         * Decision of the last layout of the comment.
         */
        volatile CommentDecision decision;

        SearchedComment(byte[] buffer, List<String> terms, boolean hidden) {
            this.buffer = buffer;
            this.terms = terms;
            this.hidden = hidden;
        }
    }

    /**
     * Section lists by their list view, to reload the comments of the panel that is searched.
     */
    private static final Map<View, WeakReference<SectionListAccess>> sectionLists =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Marks an id found in comments that are shown and in comments that are hidden,
     * such as an id of the layout, which tells nothing of the comment.
     */
    private static final CommentDecision AMBIGUOUS_ID = new CommentDecision(Collections.emptyList(), false);

    private static final class CommentDecision {
        final List<String> terms;
        final boolean hidden;
        /**
         * If the replies of the hidden comment, which are laid out after it, are not yet hidden.
         */
        boolean hideReplies;

        CommentDecision(List<String> terms, boolean hidden) {
            this.terms = terms;
            this.hidden = hidden;
            this.hideReplies = hidden;
        }
    }

    /**
     * The button that shows the replies of a comment, which is not inside the comment on some app versions.
     */
    private final StringFilterGroup commentReplies = new StringFilterGroup(
            Settings.COMMENT_SEARCH,
            "repl"
    );

    public CommentsSearchFilter() {
        StringFilterGroup commentThread = new StringFilterGroup(
                Settings.COMMENT_SEARCH,
                COMMENT_THREAD_PATH
        );
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
            if (!repliesOutsideComments) {
                repliesOutsideComments = true;
                synchronized (searchedComments) {
                    for (SearchedComment searched : searchedComments.values()) {
                        CommentDecision decision = searched.decision;
                        if (decision != null) {
                            rememberIds(searched, decision);
                        }
                    }
                }
            }
            return isRepliesHidden(path, buffer, terms, last);
        }

        // Only the outermost component of the comment, the component right inside the thread such as
        // 'comment_thread.eml|ContainerType|', holds the whole comment, such as its author and its text,
        // and it shows or hides the comment as a whole. The inner components are never hidden: Litho also
        // lays them out apart from their comment, such as the like button when its count changes or the
        // button that shows the replies, and their buffer holds only their own part of the comment.
        // As all comments are laid out from the same components, their paths do not tell which comment
        // an inner component is of, but the inner components of a hidden comment are never shown anyway.
        String commentPath = path.toString();
        final int identifierStart = commentPath.lastIndexOf('|', commentPath.length() - 2)
                - identifier.length();
        if (identifierStart < 0 || !commentPath.startsWith(identifier, identifierStart)
                || (identifierStart > 0 && commentPath.charAt(identifierStart - 1) != '|')) {
            return false;
        }

        if (buffer.length == 0) {
            return false; // Nothing to search.
        }

        final int contentHash = Arrays.hashCode(buffer);
        SearchedComment searched = searchedComments.get(contentHash);
        if (searched == null || searched.terms != terms || !Arrays.equals(searched.buffer, buffer)) {
            // The buffer is not text, but the text of the comment is in it as UTF-8.
            String text = foldCase(new String(buffer, StandardCharsets.UTF_8));
            searched = new SearchedComment(buffer, terms, !containsAllTerms(text, terms));
            searchedComments.put(contentHash, searched);
        }
        final boolean hidden = searched.hidden;
        CommentDecision decision = new CommentDecision(terms, hidden);
        lastComment.set(decision);
        searched.decision = decision;
        if (repliesOutsideComments) {
            rememberIds(searched, decision);
        }
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

    private static void rememberIds(SearchedComment searched, CommentDecision decision) {
        List<String> ids = searched.ids;
        if (ids == null) {
            ids = findIds(searched.buffer);
            searched.ids = ids;
        }
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
                    : Objects.requireNonNull(matcher.group(2)).replace("\"", "");
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
        searchedComments.clear();
        Logger.printDebug(() -> "Comments search: " + terms);
    }

    /**
     * Injection point.
     * Called when the controller of a section list is created.
     */
    public static void onSectionListCreated(SectionListAccess sectionList) {
        if (!Settings.COMMENT_SEARCH.get()) {
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
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
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
     * Called on the main thread when the header of an engagement panel is set up.
     * Panels reuse their header, so the search button is shown or hidden for every panel,
     * also for the panels with no subheader, such as the comments of Shorts and the description.
     *
     * @param panel The layout of the panel, which holds its header.
     */
    public static void onPanelHeaderSetUp(View panel) {
        if (panel == null || !Settings.COMMENT_SEARCH.get() || ID_CLOSE_BUTTON == 0) {
            return;
        }

        // The panel is opened after its header is set up.
        panel.post(() -> {
            try {
                updateSearchButton(panel);
            } catch (Exception ex) {
                Logger.printException(() -> "updateSearchButton failure", ex);
            }
        });
    }

    private static void updateSearchButton(View panel) {
        View closeButton = panel.findViewById(ID_CLOSE_BUTTON);
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

        final boolean isCommentsPanel = Utils.startsWithAny(EngagementPanel.getLastOpenedPanelId(), COMMENTS_PANEL_ID_PREFIXES);
        searchButton.setVisibility(isCommentsPanel ? View.VISIBLE : View.GONE);
        if (isCommentsPanel) {
            updateSearchButtonIcon(searchButton);
        }
    }

    @Nullable
    private static View createSearchButton(ViewGroup header, View closeButton) {
        Context context = header.getContext();
        Drawable icon = ResourceUtils.getDrawable("morphe_comment_search_button");
        if (icon == null) {
            Logger.printException(() -> "Comment search drawable not found");
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
        button.setContentDescription(str("morphe_comment_search_title"));
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
                iconView.setImageLevel(isSearching() ? 1 : 0);
            }
        }
    }

    private static void showSearchDialog(Context context, View searchButton) {
        SearchDialog.show(
                context,
                str("morphe_comment_search_title"),
                str("morphe_comment_search_hint"),
                isSearching() ? searchText : "",
                newText -> {
                    setSearch(newText);
                    updateSearchButtonIcon(searchButton);
                    if (!reloadPanelList(searchButton)) {
                        // Such as only quotes, which are not a search.
                        Utils.showSnackbarLong(str(!isSearching()
                                ? "morphe_comment_search_cleared"
                                : "morphe_comment_search_applied"));
                    }
                },
                str("morphe_settings_clear"),
                () -> {
                    setSearch("");
                    updateSearchButtonIcon(searchButton);
                    if (!reloadPanelList(searchButton)) {
                        Utils.showSnackbarLong(str("morphe_comment_search_cleared"));
                    }
                }
        );
    }
}
