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
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
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
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.dearrow.DeArrowPatch;
import app.morphe.extension.youtube.patches.originaltitles.RestoreOriginalTitlesPatch;
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
        VIDEOS("morphe_channel_search_videos"),
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

    private static final int THUMBNAIL_WIDTH = Dim.dp(120);
    private static final int THUMBNAIL_HEIGHT = Dim.dp(68);
    private static final int THUMBNAIL_CORNER_RADIUS = Dim.dp(8);
    private static final int THUMBNAIL_TIMEOUT_MILLISECONDS = 10 * 1000;

    private static final int TOOLBAR_ID = ResourceUtils.getIdIdentifier("toolbar");

    private static final int DIALOG_ANIMATION_DURATION_MILLISECONDS = 300;

    /**
     * Share of the screen height the results take up at most.
     */
    private static final int LIST_MAX_HEIGHT_PERCENT = 60;

    /**
     * The app runs a submit through more than one code path, and only the first one counts.
     */
    private static final long DUPLICATE_SUBMIT_MILLISECONDS = 1000;

    /**
     * Bitmaps are large, so they are kept only while a result list is open.
     */
    private static final Map<String, Bitmap> thumbnailCache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(40));

    /**
     * Browse id of the page the user is on. Channel pages use the channel id as their browse id.
     */
    private static String currentBrowseId = "";

    /** Channel selected by the dedicated toolbar button, retained while search opens. */
    private static volatile String pendingChannelSearchBrowseId = "";

    private static WeakReference<View> searchButtonParentRef = new WeakReference<>(null);
    private static WeakReference<ImageView> searchButtonViewRef = new WeakReference<>(null);
    private static WeakReference<View> channelSearchButtonRef = new WeakReference<>(null);

    private static String lastQuery = "";
    private static long lastQueryTime;

    private static WeakReference<SearchController> activeControllerRef = new WeakReference<>(null);

    /**
     * Injection point.
     * Called on main thread.
     */
    public static void setBrowseId(@Nullable String browseId) {
        String nextBrowseId = browseId == null ? "" : browseId;
        if (!nextBrowseId.equals(currentBrowseId)) {
            pendingChannelSearchBrowseId = "";
        }
        currentBrowseId = nextBrowseId;
        updateChannelSearchButton();
    }

    /**
     * Injection point.
     * The search feed is not a browse page, and returning to it from a channel sets no browse id.
     * The channel is left too, so searching from the search feed again searches all of YouTube.
     */
    public static void clearBrowseId() {
        currentBrowseId = "";
        pendingChannelSearchBrowseId = "";
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
        //noinspection ClickableViewAccessibility
        imageView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // YouTube's original search icon always starts a global search.
                pendingChannelSearchBrowseId = "";
            }
            return false;
        });
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
                pendingChannelSearchBrowseId = currentBrowseId;
                ImageView searchButton = searchButtonViewRef.get();
                if (searchButton != null) searchButton.callOnClick();
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

    /**
     * Injection point.
     * Called only for the default hint, not for the hint of Shorts or playlist search.
     */
    public static String getSearchHint(String original) {
        try {
            if (Settings.CHANNEL_SEARCH.get() && isChannelId(pendingChannelSearchBrowseId)) {
                return str("morphe_channel_search_hint");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getSearchHint failure", ex);
        }

        return original;
    }

    private static final class SearchController implements ChannelSearchRequest.ChannelSearchCallback {
        private final Activity activity;
        private final String channelId;
        private final String query;
        private final SheetBottomDialog.SlideDialog dialog;
        private final TextView scopeView;
        private final LinearLayout listContainer;
        private final ScrollView scrollView;
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
        private boolean isScopeSet;
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

            hideKeyboard(activity);

            SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                    .createMainLayout(activity, null);
            scopeView = new TextView(activity);
            mainLayout.addView(createHeader(activity, query, scopeView));
            mainLayout.addView(createDivider(activity));

            sortLoadingView = createLoadingView(activity);
            sortLoadingView.setVisibility(View.INVISIBLE);
            mainLayout.addView(createSortBar(activity, this, sortLoadingView));

            listContainer = new LinearLayout(activity);
            listContainer.setOrientation(LinearLayout.VERTICAL);
            listContainer.setPadding(0, 0, 0, Dim.dp16);

            scrollView = SheetBottomDialog.createCappedScrollView(activity, LIST_MAX_HEIGHT_PERCENT);
            scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
            scrollView.addView(listContainer);
            mainLayout.addView(scrollView);
            showState(null);

            dialog = SheetBottomDialog.createSlideDialog(activity, mainLayout, DIALOG_ANIMATION_DURATION_MILLISECONDS);
            dialog.setOnDismissListener(dismissed -> {
                cancel();
                thumbnailCache.clear();
            });
            dialog.show();

            if (resultMode == ResultMode.POSTS) {
                requestPostsTab();
            }
        }

        @Override
        public boolean isCancelled() {
            return isCancelled || !dialog.isShowing();
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
                    setScope(response.channelName);
                    lastResponse = response;
                } else if (!isComplete) {
                    return;
                } else if (lastResponse == null) {
                    isSearchComplete = true;
                    isSearchFailed = response == null;
                    if (resultMode == ResultMode.POSTS) {
                        refreshResults();
                    } else {
                        showState(str(isSearchFailed
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
                return new ArrayList<>(lastResponse.results);
            }

            List<ChannelSearchResult> results = titleMatches();
            if (results.isEmpty() && uploadsComplete) {
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
                    showState(str(isSearchFailed
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
                showState(loading ? null : str("morphe_channel_search_no_results"));
                return;
            }

            final int scrollY = scrollView.getScrollY();
            populateList(activity, sorted, listContainer, dialog);
            if (scrollY > 0) {
                scrollView.scrollTo(0, scrollY);
            }
        }

        private void setScope(String channelName) {
            if (isScopeSet || channelName.isEmpty()) {
                return;
            }
            isScopeSet = true;
            scopeView.setText(str("morphe_channel_search_results") + "  •  " + channelName);
        }

        private void setResultMode(ResultMode mode) {
            if (resultMode == mode) {
                return;
            }
            resultMode = mode;
            lastResultMode = mode;
            updateSortButtonText();
            scrollView.scrollTo(0, 0);
            listContainer.removeAllViews();
            if (mode == ResultMode.POSTS) {
                requestPostsTab();
            } else {
                completeResults();
            }
            if (lastResponse == null && !isSearchComplete && mode == ResultMode.VIDEOS) {
                showState(null);
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
                        setScope(channelName);
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
                showState(loading ? null : str("morphe_channel_search_no_posts"));
                return;
            }

            final int scrollY = scrollView.getScrollY();
            List<ChannelPostResult> posts = activePostSort.sort(new ArrayList<>(postsTabMatches));
            populatePostList(activity, posts, query, listContainer, dialog);
            if (scrollY > 0) {
                scrollView.scrollTo(0, scrollY);
            }
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
                scrollView.scrollTo(0, 0);
                refreshResults();
                updateSortButtonText();
                return true;
            });
            popupMenu.show();
        }

        /**
         * Replaces the results with a loading animation, or with a message.
         *
         * @param message Null to show that the results are loading.
         */
        private void showState(@Nullable String message) {
            listContainer.removeAllViews();

            if (message == null) {
                // Takes up the room of the results, so the dialog does not grow when they show.
                FrameLayout loading = new FrameLayout(activity);
                loading.addView(createLoadingView(activity), new FrameLayout.LayoutParams(
                        Dim.dp36, Dim.dp36, Gravity.CENTER));
                listContainer.addView(loading, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Dim.pctHeight(LIST_MAX_HEIGHT_PERCENT)));
                return;
            }

            TextView messageView = new TextView(activity);
            messageView.setText(message);
            messageView.setTextColor(ThemeUtils.getAppForegroundColor());
            messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            messageView.setAlpha(0.7f);
            messageView.setGravity(Gravity.CENTER);
            messageView.setPadding(Dim.dp32, Dim.dp36, Dim.dp32, Dim.dp28);
            listContainer.addView(messageView);
        }
    }

    /**
     * Injection point.
     * Called on main thread.
     *
     * @return Whether the global search was replaced with a search inside the current channel.
     */
    public static boolean searchInChannel(@Nullable String query) {
        try {
            if (!Settings.CHANNEL_SEARCH.get() || query == null || query.isEmpty()) {
                return false;
            }

            String channelId = pendingChannelSearchBrowseId;
            if (!isChannelId(channelId)) {
                return false;
            }

            Activity activity = Utils.getActivity();
            if (activity == null) {
                return false;
            }

            final long now = System.currentTimeMillis();
            if (query.equals(lastQuery) && now - lastQueryTime < DUPLICATE_SUBMIT_MILLISECONDS) {
                return true;
            }
            lastQuery = query;
            lastQueryTime = now;

            Logger.printDebug(() -> "Searching channel: " + channelId + " for: " + query);

            SearchController previousController = activeControllerRef.get();
            if (previousController != null) {
                previousController.cancel();
                if (previousController.dialog.isShowing()) {
                    previousController.dialog.dismiss();
                }
            }

            // The results show right away, and fill in as the search finds them.
            SearchController controller = new SearchController(activity, channelId, query);
            activeControllerRef = new WeakReference<>(controller);
            ChannelSearchRequest.fetchProgressive(channelId, query, controller);

            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "searchInChannel failure", ex);
        }

        return false;
    }

    private static ProgressBar createLoadingView(Activity activity) {
        ProgressBar loadingView = new ProgressBar(activity);
        loadingView.setIndeterminate(true);
        loadingView.setIndeterminateTintList(ColorStateList.valueOf(ThemeUtils.getAppForegroundColor()));
        return loadingView;
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

    private static void populatePostList(Activity activity, List<ChannelPostResult> posts, String query,
                                         LinearLayout listContainer, SheetBottomDialog.SlideDialog dialog) {
        final int count = listContainer.getChildCount();
        Map<String, View> existingViews = new HashMap<>(2 * count);
        for (int i = 0; i < count; i++) {
            View child = listContainer.getChildAt(i);
            if (child.getTag() instanceof String tag && tag.startsWith("post:")) {
                existingViews.put(tag, child);
            }
        }

        listContainer.removeAllViews();

        for (ChannelPostResult post : posts) {
            String tag = "post:" + post.postId;
            View row = existingViews.get(tag);
            if (row == null) {
                row = createPostRow(activity, post, query);
                row.setTag(tag);
                String postId = post.postId;
                row.setOnClickListener(view -> {
                    dialog.dismiss();
                    Utils.runOnMainThreadDelayed(() -> {
                        closeSearch(activity);
                        LoadVideoPatch.openVideoIntent("https://www.youtube.com/post/" + postId, false);
                    }, DIALOG_ANIMATION_DURATION_MILLISECONDS);
                });
            }
            listContainer.addView(row);
        }
    }

    private static View createPostRow(Activity activity, ChannelPostResult post, String query) {
        LinearLayout row = createResultRowContainer(activity);
        row.setGravity(Gravity.TOP);

        if (!post.thumbnailUrl.isEmpty()) {
            FrameLayout thumbnailFrame = new FrameLayout(activity);
            LinearLayout.LayoutParams frameParams =
                    new LinearLayout.LayoutParams(POST_THUMBNAIL_SIZE, POST_THUMBNAIL_SIZE);
            frameParams.setMarginEnd(Dim.dp12);
            frameParams.topMargin = Dim.dp2;
            thumbnailFrame.setLayoutParams(frameParams);

            ImageView thumbnail = new ImageView(activity);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setClipToOutline(true);
            thumbnail.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            THUMBNAIL_CORNER_RADIUS);
                }
            });
            thumbnailFrame.addView(thumbnail, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            loadThumbnail(thumbnail, post.thumbnailUrl);

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
            //noinspection SizeReplaceableByIsEmpty
            if (metadataText.length() > 0) metadataText.append("  •  ");
            metadataText.append(post.likes);
        }
        //noinspection SizeReplaceableByIsEmpty
        if (metadataText.length() > 0) {
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

    private static void populateList(Activity activity, List<ChannelSearchResult> results,
                                     LinearLayout listContainer, SheetBottomDialog.SlideDialog dialog) {
        final int count = listContainer.getChildCount();
        Map<String, View> existingViews = new HashMap<>(2 * count);
        for (int i = 0; i < count; i++) {
            View child = listContainer.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String videoId && !videoId.startsWith("post:")) {
                existingViews.put(videoId, child);
            }
        }

        listContainer.removeAllViews();

        for (ChannelSearchResult result : results) {
            View row = existingViews.get(result.videoId);
            if (row == null) {
                row = createResultRow(activity, result);
                row.setTag(result.videoId);
                String videoId = result.videoId;
                row.setOnClickListener(view -> {
                    dialog.dismiss();
                    Utils.runOnMainThreadDelayed(() -> {
                        closeSearch(activity);
                        LoadVideoPatch.openVideoIntent("https://www.youtube.com/watch?v=" + videoId, false);
                    }, DIALOG_ANIMATION_DURATION_MILLISECONDS);
                });
            }
            listContainer.addView(row);
        }
    }

    /**
     * The query alone reads like a global search, so the channel it was scoped to is shown under it.
     *
     * @param scopeView Shows the scope of the search, and later the name of the channel.
     */
    private static View createHeader(Activity activity, String query, TextView scopeView) {
        final int foregroundColor = ThemeUtils.getAppForegroundColor();

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp12);

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(BaseSearchViewController.getSearchIconDrawable());
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Dim.dp24, Dim.dp24);
        iconParams.setMarginEnd(Dim.dp16);
        icon.setLayoutParams(iconParams);
        header.addView(icon);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView queryView = new TextView(activity);
        queryView.setText(query);
        queryView.setTextColor(foregroundColor);
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        queryView.setTypeface(Typeface.DEFAULT_BOLD);
        queryView.setSingleLine();
        queryView.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(queryView);

        scopeView.setText(str("morphe_channel_search_results"));
        scopeView.setTextColor(foregroundColor);
        scopeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        scopeView.setAlpha(0.7f);
        scopeView.setSingleLine();
        scopeView.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(scopeView);

        header.addView(text);
        return header;
    }

    private static View createDivider(Activity activity) {
        View divider = new View(activity);
        divider.setBackgroundColor(ThemeUtils.getAppForegroundColor());
        divider.setAlpha(0.12f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp1);
        params.setMargins(0, 0, 0, Dim.dp4);
        divider.setLayoutParams(params);
        return divider;
    }

    private static View createResultRow(Activity activity, ChannelSearchResult result) {
        LinearLayout row = createResultRowContainer(activity);
        row.addView(createResultThumbnail(activity, result));
        row.addView(createResultText(activity, result));
        return row;
    }

    private static LinearLayout createResultRowContainer(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp8);
        row.setClickable(true);
        row.setFocusable(true);

        row.setBackgroundResource(ThemeUtils.getThemeResourceId(
                activity, android.R.attr.selectableItemBackground));

        return row;
    }

    private static View createResultThumbnail(Activity activity, ChannelSearchResult result) {
        ImageView thumbnail = new ImageView(activity);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setClipToOutline(true);
        thumbnail.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        THUMBNAIL_CORNER_RADIUS);
            }
        });
        LinearLayout.LayoutParams thumbnailParams =
                new LinearLayout.LayoutParams(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        thumbnailParams.setMarginEnd(Dim.dp12);
        thumbnail.setLayoutParams(thumbnailParams);
        loadThumbnail(thumbnail, result.thumbnailUrl);

        return thumbnail;
    }

    private static View createResultText(Activity activity, ChannelSearchResult result) {
        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView title = new TextView(activity);
        title.setText(result.title);
        RestoreOriginalTitlesPatch.restoreSearchResultTitle(title, result.videoId);
        title.setTextColor(ThemeUtils.getAppForegroundColor());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setMaxLines(2);
        text.addView(title);

        if (!result.metadata.isEmpty()) {
            TextView metadata = new TextView(activity);
            metadata.setText(result.metadata);
            metadata.setTextColor(ThemeUtils.getAppForegroundColor());
            metadata.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            metadata.setAlpha(0.7f);
            metadata.setSingleLine();
            text.addView(metadata);
        }

        return text;
    }

    private static void loadThumbnail(ImageView view, String url) {
        if (url.isEmpty()) {
            return;
        }

        Bitmap cached = thumbnailCache.get(url);
        if (cached != null) {
            view.setAlpha(1.0f);
            view.setImageBitmap(cached);
            return;
        }

        WeakReference<ImageView> viewRef = new WeakReference<>(view);
        Utils.runOnBackgroundThread(() -> {
            try {
                // The thumbnail can be replaced by DeArrow, which can wait for DeArrow off the main thread.
                String replacementUrl = DeArrowPatch.getSearchResultThumbnailURL(url);
                Bitmap downloaded = downloadThumbnail(replacementUrl);
                if (downloaded == null && !replacementUrl.equals(url)) {
                    // The replacement can be not available, such as a still capture of a live stream.
                    downloaded = downloadThumbnail(url);
                }
                if (downloaded == null) {
                    return;
                }
                Bitmap bitmap = downloaded;

                thumbnailCache.put(url, bitmap);
                Utils.runOnMainThread(() -> {
                    ImageView target = viewRef.get();
                    if (target != null) {
                        target.setAlpha(0.0f);
                        target.setImageBitmap(bitmap);
                        target.animate().alpha(1.0f).setDuration(200).start();
                    }
                });
            } catch (Exception ex) {
                Logger.printException(() -> "loadThumbnail failure", ex);
            }
        });
    }

    /**
     * @return The thumbnail, or null if it could not be loaded.
     */
    @Nullable
    private static Bitmap downloadThumbnail(String url) {
        try {
            HttpURLConnection connection = Requester.openConnection(url);
            connection.setConnectTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);
            // A response without an image, such as no content, is not decoded.
            if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            try (InputStream stream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(stream);
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not load thumbnail: " + url, ex);
            return null;
        }
    }

    /**
     * The search box keeps focus, since to submit it would have handled was taken over here.
     */
    private static void hideKeyboard(Activity activity) {
        View focused = activity.getCurrentFocus();
        if (focused == null) {
            return;
        }

        InputMethodManager manager = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    /**
     * Leaves the search the result was picked from, the same way the user would. Hiding the
     * keyboard is not enough, because the search box takes focus back and raises it again.
     */
    @SuppressWarnings("deprecation")
    private static void closeSearch(Activity activity) {
        try {
            activity.onBackPressed();
        } catch (Exception ex) {
            Logger.printException(() -> "closeSearch failure", ex);
        }
    }

    private static boolean isChannelId(String browseId) {
        return browseId.length() == CHANNEL_ID_LENGTH && browseId.startsWith("UC");
    }
}
