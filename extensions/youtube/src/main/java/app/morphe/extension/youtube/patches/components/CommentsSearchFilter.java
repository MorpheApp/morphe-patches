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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Shows only the comments that contain the searched words, and displays a BookMyShow-style
 * top tag strip of the most-used words across loaded comments (RAM-only per video session).
 */
@SuppressWarnings("unused")
public final class CommentsSearchFilter extends Filter {

    public interface SectionListAccess {
        View patch_getListView();
        boolean patch_reload();
    }

    private static final class WordCount {
        final String word;
        final int count;

        WordCount(String word, int count) {
            this.word = word;
            this.count = count;
        }
    }

    private static final String SEARCH_BUTTON_TAG = "morphe_search_comments_button";
    private static final String WORD_TAG_BAR_TAG = "venom_comment_word_tag_bar";

    private static final String[] COMMENTS_PANEL_ID_PREFIXES = {
            "comment-item-section",
            "engagement-panel-comments-section",
            "post-comments-",
            "shorts-comments-panel"
    };

    private static final int ID_CLOSE_BUTTON =
            ResourceUtils.getIdentifier(ResourceType.ID, "close_button");

    private static volatile List<String> searchTerms = Collections.emptyList();
    private static volatile String searchText = "";
    private static volatile String searchVideoId = "";

    private static final String COMMENT_THREAD_PATH = "comment_thread.eml";

    private static final int MIN_ID_LENGTH = 24;
    private static final int MAX_IDS_PER_COMMENT = 40;
    private static final int MAX_IDS = 4000;

    private static final Pattern SEARCH_TERM_PATTERN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    // =========================================================================
    // Session-Only RAM Word Frequency State (Cleared on video switch or app exit)
    // =========================================================================
    private static volatile String trackedVideoId = "";
    private static volatile String selectedWordTag = "";
    private static final Set<String> countedCommentKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, Integer> sessionWordCounts = new ConcurrentHashMap<>();
    private static final AtomicBoolean uiRefreshScheduled = new AtomicBoolean(false);
    private static volatile WeakReference<View> activeCommentsPanelRef = new WeakReference<>(null);
    private static volatile WeakReference<View> activeSearchButtonRef = new WeakReference<>(null);

    private static final Pattern PROTO_STRING_CHUNK_PATTERN = Pattern.compile("[\\u0000-\\u001F\\uFFFD]+");
    private static final Pattern PURE_WORD_PATTERN = Pattern.compile("\\b[a-zA-Z]{4,18}\\b");

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "that", "this", "with", "from", "your", "have", "more", "will", "just", "like",
            "what", "when", "they", "them", "then", "some", "into", "only", "know", "time",
            "very", "much", "also", "even", "were", "been", "here", "there", "where", "which",
            "their", "about", "would", "could", "should", "because", "really", "people",
            "video", "videos", "youtube", "channel", "watch", "watching", "watched", "comment",
            "comments", "anyone", "everyone", "someone", "something", "nothing", "everything",
            "first", "second", "still", "after", "before", "other", "others", "every", "always",
            "never", "think", "thought", "make", "made", "making", "going", "being", "those",
            "these", "days", "hours", "minutes", "seconds", "weeks", "months", "years", "edited",
            "reply", "replies", "read", "show", "less", "translated", "original", "pinned", "liked"
    ));

    private static final ThreadLocal<CommentDecision> lastComment = new ThreadLocal<>();

    private static final Map<String, CommentDecision> commentIds = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(MAX_IDS));

    private static final Map<View, WeakReference<SectionListAccess>> sectionLists =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final CommentDecision AMBIGUOUS_ID = new CommentDecision("", Collections.emptyList(), false);

    private static final class CommentDecision {
        final String path;
        final List<String> terms;
        final boolean hidden;
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
        if (matchedGroup == commentReplies) {
            List<String> terms = searchTerms;
            if (terms.isEmpty() || !isSearchForCurrentVideo()) {
                return false;
            }
            if (Utils.contains(path, COMMENT_THREAD_PATH)) {
                return false;
            }
            return isRepliesHidden(path, buffer, terms, lastComment.get());
        }

        if (buffer.length > 0) {
            recordCommentWordsInSession(path, buffer);
        }

        List<String> terms = searchTerms;
        if (terms.isEmpty() || !isSearchForCurrentVideo()) {
            return false;
        }

        CommentDecision last = lastComment.get();
        if (last != null && last.terms == terms
                && path.length() > last.path.length() && Utils.startsWith(path, last.path)) {
            return last.hidden;
        }

        if (buffer.length == 0) {
            return false;
        }

        String text = foldCase(new String(buffer, StandardCharsets.UTF_8));
        final boolean hidden = !containsAllTerms(text, terms);
        String commentPath = path.toString();
        CommentDecision decision = new CommentDecision(commentPath, terms, hidden);
        lastComment.set(decision);
        rememberIds(buffer, decision);
        Logger.printDebug(() -> (hidden ? "Hiding comment: " : "Showing comment: ") + commentPath);
        return hidden;
    }

    // =========================================================================
    // Session Word Counting & BookMyShow Top Tag Strip Logic
    // =========================================================================

    private static void ensureCurrentVideoSession() {
        String currentVideoId = VideoInformation.getVideoId();
        if (currentVideoId != null && !currentVideoId.isEmpty() && !currentVideoId.equals(trackedVideoId)) {
            trackedVideoId = currentVideoId;
            selectedWordTag = "";
            countedCommentKeys.clear();
            sessionWordCounts.clear();
        }
    }

    private static void recordCommentWordsInSession(CharSequence path, byte[] buffer) {
        try {
            ensureCurrentVideoSession();

            List<String> ids = findIds(buffer);
            String dedupeKey = !ids.isEmpty() ? ids.get(0) : path.toString();
            if (!countedCommentKeys.add(dedupeKey)) {
                return;
            }

            String rawUtf8 = new String(buffer, StandardCharsets.UTF_8);
            String[] chunks = PROTO_STRING_CHUNK_PATTERN.split(rawUtf8);
            Set<String> wordsInThisComment = new HashSet<>();

            for (String chunk : chunks) {
                String trimmed = chunk.trim();
                if (trimmed.length() < 4 || trimmed.indexOf(' ') < 0) {
                    continue;
                }
                if (trimmed.contains("http://") || trimmed.contains("https://") || trimmed.contains(".eml")) {
                    continue;
                }

                Matcher wordMatcher = PURE_WORD_PATTERN.matcher(foldCase(trimmed));
                while (wordMatcher.find()) {
                    String word = wordMatcher.group();
                    if (!STOP_WORDS.contains(word)) {
                        wordsInThisComment.add(word);
                    }
                }
            }

            if (!wordsInThisComment.isEmpty()) {
                for (String w : wordsInThisComment) {
                    Integer existing = sessionWordCounts.get(w);
                    sessionWordCounts.put(w, existing == null ? 1 : existing + 1);
                }
                scheduleTopTagBarRefresh();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Word tag count error: " + ex.getMessage());
        }
    }

    private static void scheduleTopTagBarRefresh() {
        if (!uiRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
        Utils.runOnMainThreadDelayed(() -> {
            uiRefreshScheduled.set(false);
            View panel = activeCommentsPanelRef.get();
            View searchBtn = activeSearchButtonRef.get();
            if (panel != null && panel.isShown()) {
                renderTopWordTagStrip(panel, searchBtn);
            }
        }, 350L);
    }

    private static WordCount[] getTopUsedWords(int maxCount) {
        if (sessionWordCounts.isEmpty()) {
            return new WordCount[0];
        }
        List<WordCount> entries = new ArrayList<>();
        for (String key : sessionWordCounts.keySet()) {
            Integer val = sessionWordCounts.get(key);
            if (val != null && val >= 2) {
                entries.add(new WordCount(key, val));
            }
        }
        entries.sort((a, b) -> Integer.compare(b.count, a.count));

        int limit = Math.min(maxCount, entries.size());
        WordCount[] top = new WordCount[limit];
        for (int i = 0; i < limit; i++) {
            top[i] = entries.get(i);
        }
        return top;
    }

    private static void renderTopWordTagStrip(View panel, @Nullable View searchButton) {
        try {
            View closeButton = panel.findViewById(ID_CLOSE_BUTTON);
            if (closeButton == null || !(closeButton.getParent() instanceof ViewGroup header)) {
                return;
            }

            ViewParent headerParentObj = header.getParent();
            if (!(headerParentObj instanceof ViewGroup headerParent)) {
                return;
            }

            LinearLayout verticalContainer;
            if (headerParent instanceof LinearLayout ll && ll.getOrientation() == LinearLayout.VERTICAL) {
                verticalContainer = ll;
            } else {
                int headerIndex = headerParent.indexOfChild(header);
                ViewGroup.LayoutParams headerLp = header.getLayoutParams();
                headerParent.removeView(header);

                verticalContainer = new LinearLayout(header.getContext());
                verticalContainer.setOrientation(LinearLayout.VERTICAL);
                verticalContainer.addView(header, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                headerParent.addView(verticalContainer, headerIndex, headerLp);
            }

            HorizontalScrollView tagBar = verticalContainer.findViewWithTag(WORD_TAG_BAR_TAG);
            final boolean isCommentsPanel = Utils.startsWithAny(
                    EngagementPanel.getLastOpenedPanelId(), COMMENTS_PANEL_ID_PREFIXES);

            if (!isCommentsPanel) {
                if (tagBar != null) {
                    tagBar.setVisibility(View.GONE);
                }
                return;
            }

            WordCount[] topWords = getTopUsedWords(15);
            if (topWords.length == 0) {
                if (tagBar != null) {
                    tagBar.setVisibility(View.GONE);
                }
                return;
            }

            Context context = verticalContainer.getContext();
            if (tagBar == null) {
                tagBar = new HorizontalScrollView(context);
                tagBar.setTag(WORD_TAG_BAR_TAG);
                tagBar.setHorizontalScrollBarEnabled(false);
                int headerIdx = verticalContainer.indexOfChild(header);
                verticalContainer.addView(tagBar, headerIdx + 1, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            tagBar.setVisibility(View.VISIBLE);
            tagBar.removeAllViews();

            LinearLayout pillsRow = new LinearLayout(context);
            pillsRow.setOrientation(LinearLayout.HORIZONTAL);
            int padH = dp(context, 12);
            int padV = dp(context, 6);
            pillsRow.setPadding(padH, padV, padH, padV);

            boolean isDarkMode = isDarkTheme();

            for (WordCount entry : topWords) {
                final String word = entry.word;
                final int count = entry.count;
                final boolean isSelected = word.equalsIgnoreCase(selectedWordTag) && isSearching();

                pillsRow.addView(createBookMyShowWordPill(context, word, count, isSelected, isDarkMode, v -> {
                    if (word.equalsIgnoreCase(selectedWordTag) && isSearching()) {
                        selectedWordTag = "";
                        setSearch("");
                    } else {
                        selectedWordTag = word;
                        setSearch(word);
                    }
                    if (searchButton != null) {
                        updateSearchButtonIcon(searchButton);
                        if (!reloadPanelList(searchButton)) {
                            Utils.showSnackbarLong(str(!isSearching()
                                    ? "morphe_search_comments_cleared"
                                    : "morphe_search_comments_applied"));
                        }
                    }
                    renderTopWordTagStrip(panel, searchButton);
                }));
            }

            tagBar.addView(pillsRow);
        } catch (Exception ex) {
            Logger.printDebug(() -> "renderTopWordTagStrip failure: " + ex.getMessage());
        }
    }

    private static View createBookMyShowWordPill(Context context,
                                                 String word,
                                                 int count,
                                                 boolean isSelected,
                                                 boolean isDarkMode,
                                                 View.OnClickListener onClick) {
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(context, 12), dp(context, 5), dp(context, 10), dp(context, 5));

        final int accentRed = Color.parseColor("#F84464");
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(context, 20));
        if (isSelected) {
            pillBg.setColor(accentRed);
            pillBg.setStroke(dp(context, 1), accentRed);
        } else {
            pillBg.setColor(Color.TRANSPARENT);
            pillBg.setStroke(dp(context, 1), isDarkMode ? Color.parseColor("#555555") : Color.parseColor("#CCCCCC"));
        }
        pill.setBackground(pillBg);

        String formattedTag = "#" + Character.toUpperCase(word.charAt(0)) + word.substring(1);
        TextView wordView = new TextView(context);
        wordView.setText(formattedTag);
        wordView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        wordView.setTypeface(Typeface.DEFAULT_BOLD);
        wordView.setTextColor(isSelected ? Color.WHITE : accentRed);
        pill.addView(wordView);

        TextView countView = new TextView(context);
        countView.setText(String.valueOf(count));
        countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        countView.setTextColor(isSelected ? Color.WHITE : (isDarkMode ? Color.parseColor("#DDDDDD") : Color.parseColor("#555555")));
        countView.setPadding(dp(context, 6), dp(context, 1), dp(context, 6), dp(context, 1));

        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(context, 6));
        badgeBg.setColor(isSelected
                ? Color.parseColor("#D62D4B")
                : (isDarkMode ? Color.parseColor("#2A2A2A") : Color.parseColor("#EFEFEF")));
        countView.setBackground(badgeBg);

        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.setMarginStart(dp(context, 6));
        countView.setLayoutParams(badgeLp);
        pill.addView(countView);

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.setMarginEnd(dp(context, 8));
        pill.setLayoutParams(pillLp);
        pill.setOnClickListener(onClick);
        return pill;
    }

    private static boolean isDarkTheme() {
        int fg = ThemeUtils.getAppForegroundColor();
        int brightness = Color.red(fg) + Color.green(fg) + Color.blue(fg);
        return brightness > 382;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    // =========================================================================
    // Original Morphe SearchCommentsFilter Methods
    // =========================================================================

    private static boolean isRepliesHidden(CharSequence path, byte[] buffer,
                                           List<String> terms, @Nullable CommentDecision last) {
        CommentDecision comment = findCommentOfReplies(buffer, terms);
        if (comment == null) {
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
                    continue;
                }
                if (existing == null || existing.terms != decision.terms
                        || existing.hidden == decision.hidden) {
                    commentIds.put(id, decision);
                } else {
                    commentIds.put(id, AMBIGUOUS_ID);
                }
            }
        }
    }

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

    private static String foldCase(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace("̇", "")
                .replace('ı', 'i');
    }

    private static List<String> parseSearch(String text) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = SEARCH_TERM_PATTERN.matcher(foldCase(text));
        while (matcher.find()) {
            String phrase = matcher.group(1);
            String term = phrase != null
                    ? phrase.trim().replaceAll("\\s+", " ")
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

        searchVideoId = VideoInformation.getVideoId();
        searchText = text;
        searchTerms = Collections.unmodifiableList(terms);
        commentIds.clear();
        Logger.printDebug(() -> "Comments search: " + terms);
    }

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

    private static boolean reloadPanelList(View searchButton) {
        SectionListAccess sectionList = findPanelList(searchButton);
        if (sectionList == null) {
            Logger.printDebug(() -> "Panel list not found");
            return false;
        }
        return sectionList.patch_reload();
    }

    @Nullable
    private static SectionListAccess findPanelList(View view) {
        List<ViewParent> ancestors = new ArrayList<>();
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            ancestors.add(parent);
        }

        SectionListAccess closestList = null;
        int closestDistance = Integer.MAX_VALUE;
        synchronized (sectionLists) {
            for (View listView : sectionLists.keySet()) {
                WeakReference<SectionListAccess> ref = sectionLists.get(listView);
                SectionListAccess sectionList = ref != null ? ref.get() : null;
                if (sectionList == null || !listView.isShown()) {
                    continue;
                }
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

    public static void onPanelHeaderSetUp(View panel) {
        if (panel == null || !Settings.SEARCH_COMMENTS.get() || ID_CLOSE_BUTTON == 0) {
            return;
        }

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
            ensureCurrentVideoSession();
            activeCommentsPanelRef = new WeakReference<>(panel);
            activeSearchButtonRef = new WeakReference<>(searchButton);
            updateSearchButtonIcon(searchButton);
            renderTopWordTagStrip(panel, searchButton);
        } else {
            renderTopWordTagStrip(panel, null);
        }
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
                        selectedWordTag = newText.trim();
                        setSearch(newText);
                        updateSearchButtonIcon(searchButton);
                        View panel = activeCommentsPanelRef.get();
                        if (panel != null) {
                            renderTopWordTagStrip(panel, searchButton);
                        }
                        if (!reloadPanelList(searchButton)) {
                            Utils.showSnackbarLong(str(!isSearching()
                                    ? "morphe_search_comments_cleared"
                                    : "morphe_search_comments_applied"));
                        }
                    },
                    () -> {},
                    str("morphe_search_comments_clear"),
                    () -> {
                        selectedWordTag = "";
                        setSearch("");
                        updateSearchButtonIcon(searchButton);
                        View panel = activeCommentsPanelRef.get();
                        if (panel != null) {
                            renderTopWordTagStrip(panel, searchButton);
                        }
                        if (!reloadPanelList(searchButton)) {
                            Utils.showSnackbarLong(str("morphe_search_comments_cleared"));
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