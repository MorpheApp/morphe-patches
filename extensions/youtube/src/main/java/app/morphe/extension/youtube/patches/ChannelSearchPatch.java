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

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest.ChannelSearchResponse;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest.ChannelSearchResult;
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

        public List<ChannelSearchResult> sort(List<ChannelSearchResult> results) {
            switch (this) {
                case RELEVANCE -> results.sort(Comparator.comparingInt(a -> a.originalIndex));
                case NEWEST -> results.sort((a, b) -> {
                    if (a.publishedTimeSeconds == Long.MAX_VALUE && b.publishedTimeSeconds == Long.MAX_VALUE) {
                        return Integer.compare(a.originalIndex, b.originalIndex);
                    }
                    if (a.publishedTimeSeconds == Long.MAX_VALUE) return 1;
                    if (b.publishedTimeSeconds == Long.MAX_VALUE) return -1;
                    final int cmp = Long.compare(a.publishedTimeSeconds, b.publishedTimeSeconds);
                    return cmp != 0 ? cmp : Integer.compare(a.originalIndex, b.originalIndex);
                });
                case OLDEST -> results.sort((a, b) -> {
                    if (a.publishedTimeSeconds == Long.MAX_VALUE && b.publishedTimeSeconds == Long.MAX_VALUE) {
                        return Integer.compare(a.originalIndex, b.originalIndex);
                    }
                    if (a.publishedTimeSeconds == Long.MAX_VALUE) return 1;
                    if (b.publishedTimeSeconds == Long.MAX_VALUE) return -1;
                    final int cmp = Long.compare(b.publishedTimeSeconds, a.publishedTimeSeconds);
                    return cmp != 0 ? cmp : Integer.compare(a.originalIndex, b.originalIndex);
                });
                case MOST_VIEWED -> results.sort((a, b) -> {
                    final int cmp = Long.compare(b.viewCount, a.viewCount);
                    return cmp != 0 ? cmp : Integer.compare(a.originalIndex, b.originalIndex);
                });
                case LEAST_VIEWED -> results.sort((a, b) -> {
                    final int cmp = Long.compare(a.viewCount, b.viewCount);
                    return cmp != 0 ? cmp : Integer.compare(a.originalIndex, b.originalIndex);
                });
                case SHORTEST -> results.sort((a, b) -> {
                    if (a.lengthSeconds == 0 && b.lengthSeconds == 0) {
                        return Integer.compare(a.originalIndex, b.originalIndex);
                    }
                    if (a.lengthSeconds == 0) return 1;
                    if (b.lengthSeconds == 0) return -1;
                    final int cmp = Long.compare(a.lengthSeconds, b.lengthSeconds);
                    return cmp != 0 ? cmp : Integer.compare(a.originalIndex, b.originalIndex);
                });
                case LONGEST -> results.sort((a, b) -> {
                    if (a.lengthSeconds == 0 && b.lengthSeconds == 0) {
                        return Integer.compare(a.originalIndex, b.originalIndex);
                    }
                    if (a.lengthSeconds == 0) return 1;
                    if (b.lengthSeconds == 0) return -1;
                    final int cmp = Long.compare(b.lengthSeconds, a.lengthSeconds);
                    return cmp != 0 ? cmp : Integer.compare(a.originalIndex, b.originalIndex);
                });
            }
            return results;
        }
    }

    private static final int CHANNEL_ID_LENGTH = 24;

    private static final int THUMBNAIL_WIDTH = Dim.dp(120);
    private static final int THUMBNAIL_HEIGHT = Dim.dp(68);
    private static final int THUMBNAIL_CORNER_RADIUS = Dim.dp(8);
    private static final int THUMBNAIL_TIMEOUT_MILLISECONDS = 10 * 1000;

    private static final int DIALOG_ANIMATION_DURATION_MILLISECONDS = 300;

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

    private static String lastQuery = "";
    private static long lastQueryTime;

    /**
     * Injection point.
     * Called on main thread.
     */
    public static void setBrowseId(@Nullable String browseId) {
        currentBrowseId = browseId == null ? "" : browseId;
    }

    /**
     * Injection point.
     * The search feed is not a browse page, and returning to it from a channel sets no browse id.
     */
    public static void clearBrowseId() {
        currentBrowseId = "";
    }

    /**
     * Injection point.
     * Called only for the default hint, not for the hint of Shorts or playlist search.
     */
    public static String getSearchHint(String original) {
        try {
            if (Settings.CHANNEL_SEARCH.get() && isChannelId(currentBrowseId)) {
                return str("morphe_channel_search_hint");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getSearchHint failure", ex);
        }

        return original;
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

            String channelId = currentBrowseId;
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

            Utils.runOnBackgroundThread(() -> {
                ChannelSearchResponse response = ChannelSearchRequest
                        .fetchRequestIfNeeded(channelId, query)
                        .getResponse();

                Utils.runOnMainThread(() -> {
                    if (response == null) {
                        Utils.showToastShort(str("morphe_channel_search_failed"));
                    } else if (response.results.isEmpty()) {
                        Utils.showToastShort(str("morphe_channel_search_no_results"));
                    } else {
                        showResults(activity, query, response);
                    }
                });
            });

            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "searchInChannel failure", ex);
        }

        return false;
    }

    private static void showResults(Activity activity, String query, ChannelSearchResponse response) {
        try {
            hideKeyboard(activity);

            SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                    .createMainLayout(activity, null);
            mainLayout.addView(createHeader(activity, query, response.channelName));
            mainLayout.addView(createDivider(activity));

            LinearLayout listContainer = new LinearLayout(activity);
            listContainer.setOrientation(LinearLayout.VERTICAL);
            listContainer.setPadding(0, 0, 0, Dim.dp16);

            ScrollView scrollView = SheetBottomDialog.createCappedScrollView(activity, 60);
            scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
            scrollView.addView(listContainer);

            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog
                    .createSlideDialog(activity, mainLayout, DIALOG_ANIMATION_DURATION_MILLISECONDS);

            View sortBar = createSortBar(activity, response.results, listContainer, scrollView, dialog);
            mainLayout.addView(sortBar);

            populateList(activity, response.results, listContainer, dialog);

            mainLayout.addView(scrollView);

            dialog.setOnDismissListener(dismissed -> thumbnailCache.clear());
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showResults failure", ex);
        }
    }

    private static View createSortBar(Activity activity, List<ChannelSearchResult> results,
                                      LinearLayout listContainer, ScrollView scrollView,
                                      SheetBottomDialog.SlideDialog dialog) {
        LinearLayout sortBarLayout = new LinearLayout(activity);
        sortBarLayout.setOrientation(LinearLayout.HORIZONTAL);
        sortBarLayout.setGravity(Gravity.CENTER_VERTICAL);
        sortBarLayout.setPadding(Dim.dp16, Dim.dp4, Dim.dp16, Dim.dp8);

        final int fgColor = ThemeUtils.getAppForegroundColor();
        final int buttonBgColor = (fgColor & 0x00FFFFFF) | 0x1A000000;

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
        sortButton.setOnClickListener(new View.OnClickListener() {
            private SortOption activeSort = SortOption.RELEVANCE;
            {
                updateButtonText();
            }

            private void updateButtonText() {
                sortButton.setText(activeSort.sortName + "  ▼");
            }

            @Override
            public void onClick(View v) {
                PopupMenu popupMenu = new PopupMenu(activity, sortButton);
                SortOption[] options = SortOption.values();
                Menu menu = popupMenu.getMenu();
                for (int i = 0, length = options.length; i < length; i++) {
                    menu.add(0, i, i, options[i].sortName);
                }

                popupMenu.setOnMenuItemClickListener(item -> {
                    int index = item.getItemId();
                    if (index < 0 || index >= options.length) {
                        return false;
                    }
                    SortOption selectedOption = options[index];
                    if (activeSort == selectedOption) {
                        return true;
                    }
                    activeSort = selectedOption;
                    updateButtonText();

                    sortAndRefreshList(results, selectedOption, activity, listContainer, scrollView, dialog);
                    return true;
                });
                popupMenu.show();
            }
        });
        sortBarLayout.addView(sortButton);

        return sortBarLayout;
    }

    private static void sortAndRefreshList(List<ChannelSearchResult> results, SortOption sortOption,
                                           Activity activity, LinearLayout listContainer,
                                           ScrollView scrollView, SheetBottomDialog.SlideDialog dialog) {
        List<ChannelSearchResult> sorted = sortOption.sort(new ArrayList<>(results));
        populateList(activity, sorted, listContainer, dialog);
        scrollView.scrollTo(0, 0);
    }

    private static void populateList(Activity activity, List<ChannelSearchResult> results,
                                     LinearLayout listContainer, SheetBottomDialog.SlideDialog dialog) {
        listContainer.removeAllViews();
        for (ChannelSearchResult result : results) {
            View row = createResultRow(activity, result);
            row.setOnClickListener(view -> {
                dialog.dismiss();
                Utils.runOnMainThreadDelayed(() -> {
                    closeSearch(activity);
                    LoadVideoPatch.openVideoIntent("https://www.youtube.com/watch?v="
                            + result.videoId, false);
                }, DIALOG_ANIMATION_DURATION_MILLISECONDS);
            });
            listContainer.addView(row);
        }
    }

    /**
     * The query alone reads like a global search, so the channel it was scoped to is shown under it.
     */
    private static View createHeader(Activity activity, String query, String channelName) {
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

        String scope = str("morphe_channel_search_results");
        if (!channelName.isEmpty()) {
            scope += "  •  " + channelName;
        }

        TextView scopeView = new TextView(activity);
        scopeView.setText(scope);
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

        TypedValue ripple = new TypedValue();
        if (activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }

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
            view.setImageBitmap(cached);
            return;
        }

        WeakReference<ImageView> viewRef = new WeakReference<>(view);
        Utils.runOnBackgroundThread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);

                Bitmap bitmap;
                try (InputStream stream = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(stream);
                }
                if (bitmap == null) {
                    return;
                }

                thumbnailCache.put(url, bitmap);
                Utils.runOnMainThread(() -> {
                    ImageView target = viewRef.get();
                    if (target != null) {
                        target.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not load thumbnail: " + url, ex);
            }
        });
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
