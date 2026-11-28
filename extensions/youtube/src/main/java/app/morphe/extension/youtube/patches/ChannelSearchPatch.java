/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 * https://github.com/MorpheApp/morphe-patches/pull/3298
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.sf;
import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.utils.SearchDialog;
import app.morphe.extension.youtube.patches.utils.SearchResultsSheet;
import app.morphe.extension.youtube.patches.utils.requests.ChannelPostsSearchRequest;
import app.morphe.extension.youtube.patches.utils.requests.ChannelPostsSearchRequest.ChannelPostResult;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest;
import app.morphe.extension.youtube.patches.utils.requests.ChannelUploadsRequest;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest.ChannelSearchResponse;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest.ChannelSearchResult;
import app.morphe.extension.youtube.patches.utils.requests.VideoDetailsRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class ChannelSearchPatch {

    private enum SortOption {
        RELEVANCE("morphe_channel_search_sort_relevance"),
        NEWEST("morphe_channel_search_sort_newest"),
        OLDEST("morphe_channel_search_sort_oldest"),
        MOST_VIEWED("morphe_channel_search_sort_most_viewed"),
        LEAST_VIEWED("morphe_channel_search_sort_least_viewed"),
        SHORTEST("morphe_channel_search_sort_shortest"),
        LONGEST("morphe_channel_search_sort_longest");

        final String sortName;

        SortOption(String sortName) {
            this.sortName = sf(sortName).toString();
        }

        /**
         * @param uploads Uploads of the channel, which tell the age of most results exactly.
         */
        public List<ChannelSearchResult> sort(List<ChannelSearchResult> results,
                                              ChannelUploadsRequest.Uploads uploads) {
            if (this == RELEVANCE) {
                results.sort(Comparator.comparingInt(a -> a.originalIndex));
                return results;
            }

            // Each result gets its key once, so the order stays consistent while details arrive.
            final long now = System.currentTimeMillis() / 1000;
            Map<String, Long> keys = new HashMap<>(2 * results.size());
            for (ChannelSearchResult result : results) {
                keys.put(result.videoId, sortKey(result, uploads, now));
            }

            final boolean ascending = this == NEWEST || this == LEAST_VIEWED || this == SHORTEST;
            results.sort((a, b) -> {
                Long keyA = keys.get(a.videoId);
                Long keyB = keys.get(b.videoId);
                // Results with an unknown value go last, in any sort.
                if (keyA == null || keyB == null) {
                    if (keyA != keyB) return keyA == null ? 1 : -1;
                } else {
                    final int cmp = ascending ? Long.compare(keyA, keyB) : Long.compare(keyB, keyA);
                    if (cmp != 0) return cmp;
                }
                final int cmp = Integer.compare(a.originalIndex, b.originalIndex);
                return cmp != 0 ? cmp : a.videoId.compareTo(b.videoId);
            });
            return results;
        }

        /**
         * @return The value to sort by, or null if it is not known. The age of an upload is
         *         its place in the uploads, and uploads that are not listed are older than
         *         all listed ones, so they come after them by the age shown in the search.
         */
        @Nullable
        private Long sortKey(ChannelSearchResult result, ChannelUploadsRequest.Uploads uploads, long now) {
            switch (this) {
                case NEWEST, OLDEST -> {
                    Integer position = uploads.positions.get(result.videoId);
                    if (position != null) return (long) position;

                    VideoDetailsRequest.VideoDetails details = VideoDetailsRequest.get(result.videoId);
                    final long ageSeconds = details != null
                            ? now - details.publishTimeSeconds
                            : result.publishedTimeSeconds;
                    if (ageSeconds == Long.MAX_VALUE) return null;
                    return uploads.positions.size() + Math.max(0, ageSeconds);
                }
                case MOST_VIEWED, LEAST_VIEWED -> {
                    VideoDetailsRequest.VideoDetails details = VideoDetailsRequest.get(result.videoId);
                    if (details != null) return details.viewCount;
                    if (result.viewCount >= 0) return result.viewCount;
                    // The exact view count can be missing, such as when too many were fetched.
                    return uploads.roundedViewCounts.get(result.videoId);
                }
                case SHORTEST, LONGEST -> {
                    // Live streams, premieres and Shorts in the search results show no length.
                    if (result.lengthSeconds > 0) return result.lengthSeconds;
                    VideoDetailsRequest.VideoDetails details = VideoDetailsRequest.get(result.videoId);
                    return details != null && details.lengthSeconds > 0 ? details.lengthSeconds : null;
                }
            }
            return null;
        }
    }

    /**
     * What the results list, which the user picks above them.
     */
    private enum ResultMode {
        VIDEOS("morphe_videos"),
        POSTS("morphe_channel_search_posts");

        /**
         * This class is loaded when the app starts, before strings can be read,
         * so the name is read only when it is shown.
         */
        private final String modeNameKey;

        ResultMode(String modeNameKey) {
            this.modeNameKey = modeNameKey;
        }

        String getModeName() {
            return str(modeNameKey);
        }
    }

    /**
     * The posts tab lists posts newest first, and tells their age only rounded.
     */
    private enum PostSortOption {
        NEWEST("morphe_channel_search_sort_newest"),
        OLDEST("morphe_channel_search_sort_oldest");

        private final String sortNameKey;

        PostSortOption(String sortNameKey) {
            this.sortNameKey = sortNameKey;
        }

        String getSortName() {
            return str(sortNameKey);
        }

        List<ChannelPostResult> sort(List<ChannelPostResult> posts) {
            if (this == OLDEST) {
                Collections.reverse(posts);
            }
            return posts;
        }
    }

    /**
     * Kept for the next search, so that searching posts again needs no extra tap.
     */
    private static ResultMode lastResultMode = ResultMode.VIDEOS;

    private static final int CHANNEL_ID_LENGTH = 24;

    private static final int POST_THUMBNAIL_SIZE = Dim.dp(68);

    /**
     * Text of a post shown before the first match, if the match is further in the text.
     */
    private static final int POST_SNIPPET_LEAD_CHARACTERS = 40;

    private static final int TOOLBAR_ID = ResourceUtils.getIdIdentifier("toolbar");

    /**
     * Browse id of the page the user is on. Channel pages use the channel id as their browse id.
     */
    private static String currentBrowseId = "";

    private static WeakReference<View> searchButtonParentRef = new WeakReference<>(null);
    private static WeakReference<ImageView> searchButtonViewRef = new WeakReference<>(null);
    private static WeakReference<View> channelSearchButtonRef = new WeakReference<>(null);

    /**
     * Injection point.
     * Called on main thread.
     */
    public static void setBrowseId(@Nullable String browseId) {
        currentBrowseId = browseId == null ? "" : browseId;
        updateChannelSearchButton();
    }

    /**
     * Injection point.
     * The search feed is not a browse page, and returning to it from a channel sets no browse id.
     */
    public static void clearBrowseId() {
        currentBrowseId = "";
        updateChannelSearchButton();
    }

    /** Injection point. Adds the dedicated channel-search button beside YouTube's global search. */
    public static void setSearchButtonView(String enumName, View parentView, ImageView imageView) {
        if (!"SEARCH".equals(enumName) && !"SEARCH_BOLD".equals(enumName)
                && !"SEARCH_CAIRO".equals(enumName)) {
            return;
        }
        searchButtonParentRef = new WeakReference<>(parentView);
        searchButtonViewRef = new WeakReference<>(imageView);
        updateChannelSearchButton();
        parentView.post(ChannelSearchPatch::updateChannelSearchButton);
    }

    private static void updateChannelSearchButton() {
        try {
            View button = channelSearchButtonRef.get();
            View originalParent = searchButtonParentRef.get();
            ImageView originalIcon = searchButtonViewRef.get();
            final boolean visible = Settings.CHANNEL_SEARCH.get() && isChannelId(currentBrowseId);

            View toolbarItem = originalParent;
            ViewGroup toolbar = null;
            if (originalParent != null) {
                View current = originalParent;
                while (current.getParent() instanceof ViewGroup parent) {
                    if (current.getId() != View.NO_ID) {
                        String entryName = current.getResources().getResourceEntryName(current.getId());
                        if (entryName.startsWith("menu_item_")) {
                            toolbar = parent;
                            toolbarItem = current;
                            break;
                        }
                    }
                    if (TOOLBAR_ID != 0 && parent.getId() == TOOLBAR_ID) {
                        toolbar = parent;
                        toolbarItem = current;
                        break;
                    }
                    current = parent;
                }
            }

            if (button != null) {
                button.setVisibility(visible ? View.VISIBLE : View.GONE);
                if (button.getParent() == toolbar) return;
                if (button.getParent() instanceof ViewGroup oldParent) oldParent.removeView(button);
                channelSearchButtonRef = new WeakReference<>(null);
            }
            if (!visible || originalParent == null || originalIcon == null || toolbar == null) return;

            ViewGroup.LayoutParams buttonParams = copyLayoutParams(toolbarItem.getLayoutParams());
            if (buttonParams instanceof LinearLayout.LayoutParams linearParams) {
                linearParams.width = Dim.dp48;
                linearParams.weight = 0;
            }
            FrameLayout channelButton = new FrameLayout(toolbarItem.getContext());
            channelButton.setLayoutParams(buttonParams);
            channelButton.setContentDescription(str("morphe_channel_search_hint"));
            channelButton.setFocusable(true);
            channelButton.setClickable(true);

            ImageView icon = new ImageView(originalParent.getContext());
            Drawable searchIcon = ResourceUtils.getDrawable("morphe_channel_search_button");
            if (searchIcon == null) {
                Logger.printException(() -> "updateChannelSearchButton drawable not found");
                return;
            }
            searchIcon.setTint(ThemeUtils.getAppForegroundColor());
            icon.setImageDrawable(searchIcon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            channelButton.addView(icon, new FrameLayout.LayoutParams(Dim.dp24, Dim.dp24, Gravity.CENTER));
            channelButton.setOnClickListener(view -> {
                String channelId = currentBrowseId;
                Activity activity = Utils.getActivity();
                if (activity != null && isChannelId(channelId)) {
                    SearchDialog.show(activity, str("morphe_channel_search_hint"),
                            query -> searchInChannel(activity, channelId, query));
                }
            });

            final int index = toolbar.indexOfChild(toolbarItem);
            toolbar.addView(channelButton, Math.max(index, 0));
            channelSearchButtonRef = new WeakReference<>(channelButton);
        } catch (Exception ex) {
            Logger.printException(() -> "updateChannelSearchButton failure", ex);
        }
    }

    private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams original) {
        if (original == null) {
            return new ViewGroup.LayoutParams(Dim.dp48, Dim.dp48);
        }
        if (original instanceof LinearLayout.LayoutParams linear) {
            return new LinearLayout.LayoutParams(linear);
        }
        if (original instanceof FrameLayout.LayoutParams frame) {
            return new FrameLayout.LayoutParams(frame);
        }
        if (original instanceof ViewGroup.MarginLayoutParams margins) {
            return new ViewGroup.MarginLayoutParams(margins);
        }
        return new ViewGroup.LayoutParams(original);
    }

    private static final class SearchController implements ChannelSearchRequest.ChannelSearchCallback {
        private final Activity activity;
        private final String channelId;
        private final String query;
        private final SearchResultsSheet sheet;
        /** Shown while a sort other than relevance still completes its results. */
        private final ProgressBar sortLoadingView;
        @Nullable
        private ChannelSearchResponse lastResponse;
        private boolean isSearchComplete;
        private SortOption activeSort = SortOption.RELEVANCE;
        private ResultMode resultMode = lastResultMode;
        private PostSortOption activePostSort = PostSortOption.NEWEST;
        private volatile boolean isCancelled;
        private boolean isSearchFailed;
        @Nullable
        private TextView sortButton;

        /**
         * Posts of the posts tab that have the search, newest first.
         */
        private List<ChannelPostResult> postsTabMatches = new ArrayList<>();
        private boolean postsTabRequested;
        private boolean postsTabComplete;

        /**
         * Uploads of the channel, whose matches are the results of every sort but relevance.
         * The search itself leaves out different videos every time.
         */
        private ChannelUploadsRequest.Uploads uploads = new ChannelUploadsRequest.Uploads();
        private boolean uploadsRequested;
        private boolean uploadsComplete;
        private int pendingDetailFetches;

        /**
         * Shows the results right away, with a loading animation until the search finds any.
         */
        private SearchController(Activity activity, String channelId, String query) {
            this.activity = activity;
            this.channelId = channelId;
            this.query = query;

            sortLoadingView = SearchResultsSheet.createLoadingView(activity);
            sortLoadingView.setVisibility(View.INVISIBLE);
            sheet = new SearchResultsSheet(activity, query, str("morphe_channel_search_results"),
                    createSortBar(activity, this, sortLoadingView), this::cancel);

            if (resultMode == ResultMode.POSTS) {
                requestPostsTab();
            }
        }

        @Override
        public boolean isCancelled() {
            return isCancelled || !sheet.isShowing();
        }

        public void cancel() {
            isCancelled = true;
        }

        @Override
        public void onResponsePage(@Nullable ChannelSearchResponse response, boolean isComplete) {
            Utils.runOnMainThreadNowOrLater(() -> {
                if (isCancelled()) {
                    return;
                }
                if (response != null && !response.results.isEmpty()) {
                    sheet.setScopeName(response.channelName);
                    lastResponse = response;
                } else if (!isComplete) {
                    return;
                } else if (lastResponse == null) {
                    isSearchComplete = true;
                    isSearchFailed = response == null;
                    if (resultMode == ResultMode.POSTS) {
                        refreshResults();
                    } else {
                        sheet.showState(str(isSearchFailed
                                ? "morphe_channel_search_failed"
                                : "morphe_channel_search_no_results"));
                    }
                    return;
                }
                // A later page that failed keeps the results of the pages before it.
                isSearchComplete = isComplete;
                completeResults();
                refreshResults();
            });
        }

        /**
         * Sorts other than relevance need every matching upload, and the view count sorts
         * their exact view count. Called on main thread.
         */
        private void completeResults() {
            if (resultMode != ResultMode.VIDEOS || activeSort == SortOption.RELEVANCE
                    || lastResponse == null || isCancelled()) {
                return;
            }

            if (!uploadsRequested) {
                uploadsRequested = true;
                ChannelUploadsRequest.fetch(channelId, query, this::isCancelled,
                        (fetchedUploads, isComplete) -> Utils.runOnMainThread(() -> {
                            if (isCancelled()) return;
                            uploads = fetchedUploads;
                            uploadsComplete = isComplete;
                            completeResults();
                            refreshResults();
                        })
                );
            }

            // Each video needs a request of its own, so these are only made for the search
            // results whose description is searched, and whose exact view count or length is not known.
            final boolean searchesDescriptions = uploadsComplete && titleMatches().isEmpty();
            List<String> videoIds = new ArrayList<>();
            for (ChannelSearchResult result : searchesDescriptions ? lastResponse.results : allResults()) {
                // The view count sorts need the exact view count of every result,
                // and the length sorts the length of every result.
                if (searchesDescriptions
                        || ((activeSort == SortOption.MOST_VIEWED || activeSort == SortOption.LEAST_VIEWED)
                                && result.viewCount < 0)
                        || ((activeSort == SortOption.SHORTEST || activeSort == SortOption.LONGEST)
                                && result.lengthSeconds <= 0)) {
                    videoIds.add(result.videoId);
                }
            }
            if (videoIds.isEmpty()) {
                return;
            }

            pendingDetailFetches++;
            VideoDetailsRequest.fetch(videoIds, this::isCancelled, isComplete ->
                    Utils.runOnMainThread(() -> {
                        if (isComplete) pendingDetailFetches--;
                        refreshResults();
                    })
            );
        }

        /**
         * The search finds a different part of the matching videos every time, and pads them
         * with other videos of the channel. So sorts other than relevance show the uploads whose
         * title has the search, and only fall back to the search results whose description
         * or tags have it if no title does.
         *
         * @return The results to sort.
         */
        private List<ChannelSearchResult> allResults() {
            if (activeSort == SortOption.RELEVANCE) {
                assert lastResponse != null;
                return new ArrayList<>(lastResponse.results);
            }

            List<ChannelSearchResult> results = titleMatches();
            if (results.isEmpty() && uploadsComplete) {
                assert lastResponse != null;
                for (ChannelSearchResult result : lastResponse.results) {
                    if (descriptionMatches(result)) {
                        results.add(result);
                    }
                }
            }
            return results;
        }

        private List<ChannelSearchResult> titleMatches() {
            List<ChannelSearchResult> results = new ArrayList<>(uploads.matches);
            Set<String> videoIds = new HashSet<>(2 * results.size());
            for (ChannelSearchResult result : results) {
                videoIds.add(result.videoId);
            }
            // The uploads of the largest channels are only partly listed.
            assert lastResponse != null;
            for (ChannelSearchResult result : lastResponse.results) {
                if (ChannelUploadsRequest.matches(result.title, query) && videoIds.add(result.videoId)) {
                    results.add(result);
                }
            }
            return results;
        }

        private boolean descriptionMatches(ChannelSearchResult result) {
            VideoDetailsRequest.VideoDetails details = VideoDetailsRequest.get(result.videoId);
            if (details == null) {
                // Kept until the details tell, unless they could not be fetched.
                return pendingDetailFetches > 0;
            }
            return ChannelUploadsRequest.matches(details.text, query);
        }

        /**
         * @return Whether the results of the active sort can still change.
         */
        private boolean isLoading() {
            return !isSearchComplete || (activeSort != SortOption.RELEVANCE
                    && (!uploadsComplete || pendingDetailFetches > 0));
        }

        private void refreshResults() {
            if (resultMode == ResultMode.POSTS) {
                refreshPosts();
                return;
            }
            if (lastResponse == null) {
                if (isSearchComplete) {
                    sortLoadingView.setVisibility(View.INVISIBLE);
                    sheet.showState(str(isSearchFailed
                            ? "morphe_channel_search_failed"
                            : "morphe_channel_search_no_results"));
                }
                return;
            }

            final boolean loading = isLoading();
            sortLoadingView.setVisibility(loading && activeSort != SortOption.RELEVANCE
                    ? View.VISIBLE : View.INVISIBLE);

            List<ChannelSearchResult> sorted = activeSort.sort(allResults(), uploads);
            if (sorted.isEmpty()) {
                sheet.showState(loading ? null : str("morphe_channel_search_no_results"));
                return;
            }

            sheet.showVideos(sorted, null, false);
        }

        private void setResultMode(ResultMode mode) {
            if (resultMode == mode) {
                return;
            }
            resultMode = mode;
            lastResultMode = mode;
            updateSortButtonText();
            sheet.clear();
            if (mode == ResultMode.POSTS) {
                requestPostsTab();
            } else {
                completeResults();
            }
            if (lastResponse == null && !isSearchComplete && mode == ResultMode.VIDEOS) {
                sheet.showState(null);
            }
            refreshResults();
        }

        /**
         * The posts tab is searched only once posts are shown, as it takes a request for every page.
         */
        private void requestPostsTab() {
            if (postsTabRequested) {
                return;
            }
            postsTabRequested = true;
            Logger.printDebug(() -> "Searching posts tab of channel: " + channelId + " for: " + query);
            ChannelPostsSearchRequest.fetchPostsTab(channelId, query, this::isCancelled,
                    (posts, channelName, isComplete) -> Utils.runOnMainThreadNowOrLater(() -> {
                        if (isCancelled()) return;
                        sheet.setScopeName(channelName);
                        postsTabMatches = posts;
                        postsTabComplete = isComplete;
                        if (resultMode == ResultMode.POSTS) {
                            refreshPosts();
                        }
                    })
            );
        }

        private void refreshPosts() {
            final boolean loading = !postsTabComplete;
            sortLoadingView.setVisibility(loading ? View.VISIBLE : View.INVISIBLE);

            if (postsTabMatches.isEmpty()) {
                sheet.showState(loading ? null : str("morphe_channel_search_no_posts"));
                return;
            }

            List<ChannelPostResult> posts = activePostSort.sort(new ArrayList<>(postsTabMatches));
            sheet.showRows(createPostRows(activity, sheet, posts, query));
        }

        @SuppressLint("SetTextI18n")
        private void updateSortButtonText() {
            if (sortButton == null) return;
            String sortName = resultMode == ResultMode.POSTS
                    ? activePostSort.getSortName()
                    : activeSort.sortName;
            sortButton.setText(sortName + "  ▼");
        }

        /**
         * Sorts of the results the active mode lists.
         */
        private void showSortMenu() {
            if (sortButton == null) return;
            PopupMenu popupMenu = new PopupMenu(activity, sortButton);
            Menu menu = popupMenu.getMenu();
            final boolean isPosts = resultMode == ResultMode.POSTS;
            final int length = isPosts ? PostSortOption.values().length : SortOption.values().length;
            for (int i = 0; i < length; i++) {
                menu.add(0, i, i, isPosts
                        ? PostSortOption.values()[i].getSortName()
                        : SortOption.values()[i].sortName);
            }

            popupMenu.setOnMenuItemClickListener(item -> {
                final int index = item.getItemId();
                if (index < 0 || index >= length) {
                    return false;
                }
                if (isPosts) {
                    PostSortOption selected = PostSortOption.values()[index];
                    if (selected == activePostSort) return true;
                    activePostSort = selected;
                } else {
                    SortOption selected = SortOption.values()[index];
                    if (selected == activeSort) return true;
                    activeSort = selected;
                    completeResults();
                }
                sheet.scrollToTop();
                refreshResults();
                updateSortButtonText();
                return true;
            });
            popupMenu.show();
        }
    }

    private static void searchInChannel(Activity activity, String channelId, String query) {
        try {
            Logger.printDebug(() -> "Searching channel: " + channelId + " for: " + query);

            // The results show right away, and fill in as the search finds them.
            SearchController controller = new SearchController(activity, channelId, query);
            ChannelSearchRequest.fetchProgressive(channelId, query, controller);
        } catch (Exception ex) {
            Logger.printException(() -> "searchInChannel failure", ex);
        }
    }

    private static View createSortBar(Activity activity, SearchController controller, View loadingView) {
        LinearLayout sortBarLayout = new LinearLayout(activity);
        sortBarLayout.setOrientation(LinearLayout.HORIZONTAL);
        sortBarLayout.setGravity(Gravity.CENTER_VERTICAL);
        sortBarLayout.setPadding(Dim.dp16, Dim.dp4, Dim.dp16, Dim.dp8);

        final int fgColor = ThemeUtils.getAppForegroundColor();
        final int buttonBgColor = (fgColor & 0x00FFFFFF) | 0x1A000000;

        List<TextView> modeChips = new ArrayList<>();
        for (ResultMode mode : ResultMode.values()) {
            TextView chip = new TextView(activity);
            chip.setText(mode.getModeName());
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setSingleLine();
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Dim.dp12, Dim.dp6, Dim.dp12, Dim.dp6);
            chip.setTag(mode);
            chip.setOnClickListener(view -> {
                controller.setResultMode(mode);
                styleModeChips(modeChips, controller.resultMode, fgColor, buttonBgColor);
            });
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.setMarginEnd(Dim.dp8);
            sortBarLayout.addView(chip, chipParams);
            modeChips.add(chip);
        }
        styleModeChips(modeChips, controller.resultMode, fgColor, buttonBgColor);

        TextView sortButton = new TextView(activity);
        sortButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sortButton.setSingleLine();
        sortButton.setGravity(Gravity.CENTER);
        sortButton.setPadding(Dim.dp12, Dim.dp6, Dim.dp12, Dim.dp6);
        sortButton.setTextColor(fgColor);

        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(16), null, null));
        background.getPaint().setColor(buttonBgColor);
        sortButton.setBackground(background);
        sortButton.setOnClickListener(view -> controller.showSortMenu());
        sortBarLayout.addView(sortButton);
        controller.sortButton = sortButton;
        controller.updateSortButtonText();

        LinearLayout.LayoutParams loadingParams = new LinearLayout.LayoutParams(Dim.dp20, Dim.dp20);
        loadingParams.setMarginStart(Dim.dp4);
        sortBarLayout.addView(loadingView, loadingParams);

        return sortBarLayout;
    }

    /**
     * The picked mode is filled with the text color, as the chips of YouTube are.
     */
    private static void styleModeChips(List<TextView> chips, ResultMode selected,
                                       int fgColor, int buttonBgColor) {
        final int bgColor = ThemeUtils.getDialogBackgroundColor();
        for (TextView chip : chips) {
            final boolean isSelected = chip.getTag() == selected;
            ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                    Dim.roundedCorners(16), null, null));
            background.getPaint().setColor(isSelected ? fgColor : buttonBgColor);
            chip.setBackground(background);
            chip.setTextColor(isSelected ? bgColor : fgColor);
            chip.setTypeface(isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            chip.setSelected(isSelected);
        }
    }

    private static List<View> createPostRows(Activity activity, SearchResultsSheet sheet,
                                             List<ChannelPostResult> posts, String query) {
        Map<String, View> existingRows = sheet.getRows();
        List<View> rows = new ArrayList<>(posts.size());

        for (ChannelPostResult post : posts) {
            String tag = "post:" + post.postId;
            View row = existingRows.remove(tag);
            if (row == null) {
                row = createPostRow(activity, post, query);
                row.setTag(tag);
                String url = "https://www.youtube.com/post/" + post.postId;
                row.setOnClickListener(view -> sheet.open(url));
            }
            rows.add(row);
        }
        return rows;
    }

    private static View createPostRow(Activity activity, ChannelPostResult post, String query) {
        LinearLayout row = SearchResultsSheet.createRowContainer(activity);
        row.setGravity(Gravity.TOP);

        if (!post.thumbnailUrl.isEmpty()) {
            FrameLayout thumbnailFrame = new FrameLayout(activity);
            LinearLayout.LayoutParams frameParams =
                    new LinearLayout.LayoutParams(POST_THUMBNAIL_SIZE, POST_THUMBNAIL_SIZE);
            frameParams.setMarginEnd(Dim.dp12);
            frameParams.topMargin = Dim.dp2;
            thumbnailFrame.setLayoutParams(frameParams);

            ImageView thumbnail = SearchResultsSheet.createThumbnailView(activity);
            thumbnailFrame.addView(thumbnail, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            SearchResultsSheet.loadThumbnail(thumbnail, post.thumbnailUrl);

            if (post.imageCount > 1) {
                TextView badge = new TextView(activity);
                badge.setText(String.valueOf(post.imageCount));
                badge.setTextColor(0xFFFFFFFF);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                badge.setPadding(Dim.dp6, Dim.dp1, Dim.dp6, Dim.dp1);
                ShapeDrawable badgeBackground = new ShapeDrawable(new RoundRectShape(
                        Dim.roundedCorners(4), null, null));
                badgeBackground.getPaint().setColor(0xA6000000);
                badge.setBackground(badgeBackground);
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.END);
                badgeParams.setMargins(0, 0, Dim.dp4, Dim.dp4);
                thumbnailFrame.addView(badge, badgeParams);
            }
            row.addView(thumbnailFrame);
        }

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView body = new TextView(activity);
        body.setText(postSnippet(post.text, query));
        body.setTextColor(ThemeUtils.getAppForegroundColor());
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(body);

        StringBuilder metadataText = new StringBuilder();
        if (!post.publishedTime.isEmpty()) {
            metadataText.append(post.publishedTime);
        }
        if (!post.likes.isEmpty()) {
            if (Utils.isNotEmpty(metadataText)) metadataText.append("  •  ");
            metadataText.append(post.likes);
        }
        if (Utils.isNotEmpty(metadataText)) {
            TextView metadata = new TextView(activity);
            metadata.setText(metadataText);
            metadata.setTextColor(ThemeUtils.getAppForegroundColor());
            metadata.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            metadata.setAlpha(0.7f);
            metadata.setSingleLine();
            metadata.setPadding(0, Dim.dp2, 0, 0);
            text.addView(metadata);
        }

        row.addView(text);
        return row;
    }

    /**
     * Text of the post from shortly before the first word of the search, with the words of
     * the search in bold, so that a match deep in a long post is still shown.
     */
    private static CharSequence postSnippet(String text, String query) {
        if (text.isEmpty()) {
            return text;
        }

        String foldedText = foldForHighlight(text);
        List<String> words = new ArrayList<>();
        for (String word : foldForHighlight(query).split("\\s+")) {
            if (!word.isEmpty()) words.add(word);
        }

        int start = getStart(text, words, foldedText);

        final String prefix = start > 0 ? "…" : "";
        SpannableString snippet = new SpannableString(prefix + text.substring(start));
        String foldedSnippet = foldedText.substring(start);
        for (String word : words) {
            int index = foldedSnippet.indexOf(word);
            while (index >= 0) {
                final int spanStart = prefix.length() + index;
                snippet.setSpan(new StyleSpan(Typeface.BOLD), spanStart, spanStart + word.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                index = foldedSnippet.indexOf(word, index + word.length());
            }
        }
        return snippet;
    }

    private static int getStart(String text, List<String> words, String foldedText) {
        int firstMatch = -1;
        for (String word : words) {
            final int index = foldedText.indexOf(word);
            if (index >= 0 && (firstMatch < 0 || index < firstMatch)) {
                firstMatch = index;
            }
        }

        int start = 0;
        if (firstMatch > POST_SNIPPET_LEAD_CHARACTERS) {
            start = firstMatch - POST_SNIPPET_LEAD_CHARACTERS;
            // Starts at a word, or at a line.
            final int lineStart = text.lastIndexOf('\n', firstMatch - 1);
            if (lineStart >= start) {
                start = lineStart + 1;
            } else {
                final int space = text.indexOf(' ', start);
                if (space >= 0 && space < firstMatch) start = space + 1;
            }
        }
        return start;
    }

    /**
     * Lowercase with each character replaced by one character, so positions in the text stay the same.
     */
    private static String foldForHighlight(String text) {
        String folded = ChannelPostsSearchRequest.foldTurkishI(text);
        StringBuilder builder = new StringBuilder(folded.length());
        for (int i = 0, length = folded.length(); i < length; i++) {
            builder.append(Character.toLowerCase(folded.charAt(i)));
        }
        return builder.toString();
    }

    private static boolean isChannelId(String browseId) {
        return browseId.length() == CHANNEL_ID_LENGTH && browseId.startsWith("UC");
    }
}
