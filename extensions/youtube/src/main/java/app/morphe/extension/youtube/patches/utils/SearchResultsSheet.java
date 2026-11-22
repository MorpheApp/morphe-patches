/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5200
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.LoadVideoPatch;
import app.morphe.extension.youtube.patches.dearrow.DeArrowPatch;
import app.morphe.extension.youtube.patches.originaltitles.RestoreOriginalTitlesPatch;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest.ChannelSearchResult;

/**
 * Sheet with the results of a search inside a page, such as a channel or a playlist.
 * It is shown right away, with a loading animation until the search finds any results.
 */
public final class SearchResultsSheet {

    public static final int THUMBNAIL_CORNER_RADIUS = Dim.dp(8);

    private static final int THUMBNAIL_WIDTH = Dim.dp(120);
    private static final int THUMBNAIL_HEIGHT = Dim.dp(68);
    private static final int THUMBNAIL_TIMEOUT_MILLISECONDS = 10 * 1000;

    private static final int DIALOG_ANIMATION_DURATION_MILLISECONDS = 300;

    /**
     * Share of the screen height the results take up at most.
     */
    private static final int LIST_MAX_HEIGHT_PERCENT = 60;

    /**
     * Bitmaps are large, so they are kept only while a result list is open.
     */
    private static final Map<String, Bitmap> thumbnailCache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(40));

    private final Activity activity;
    private final String scopeText;
    private final SheetBottomDialog.SlideDialog dialog;
    private final TextView scopeView;
    private final LinearLayout listContainer;
    private final ScrollView scrollView;
    private boolean isScopeNameSet;

    /**
     * Shows the sheet.
     *
     * @param scopeText Tells the scope of the search under the words searched, such as
     *                  "Channel search results". The name of the scope is added after it once known.
     * @param bar       Shown between the header and the results, such as chips to sort the results.
     * @param onDismiss Called when the sheet is closed.
     */
    public SearchResultsSheet(Activity activity, String query, String scopeText,
                              @Nullable View bar, Runnable onDismiss) {
        this.activity = activity;
        this.scopeText = scopeText;

        SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog
                .createMainLayout(activity, null);
        scopeView = new TextView(activity);
        mainLayout.addView(createHeader(query));
        mainLayout.addView(createDivider());
        if (bar != null) {
            mainLayout.addView(bar);
        }

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
            onDismiss.run();
            thumbnailCache.clear();
        });
        dialog.show();
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public void dismiss() {
        dialog.dismiss();
    }

    /**
     * Adds the name of the scope, such as the name of the channel, after the scope text.
     * Only the first name counts.
     */
    @SuppressLint("SetTextI18n")
    public void setScopeName(String name) {
        if (isScopeNameSet || name.isEmpty()) {
            return;
        }
        isScopeNameSet = true;
        scopeView.setText(scopeText + "  •  " + name);
    }

    /**
     * Replaces the results with a loading animation, or with a message.
     *
     * @param message Null to show that the results are loading.
     */
    public void showState(@Nullable String message) {
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

    /**
     * Scrolls to the first result, such as when the results are sorted differently.
     */
    public void scrollToTop() {
        scrollView.scrollTo(0, 0);
    }

    /**
     * Removes the results and scrolls to the top, such as when other results are shown.
     */
    public void clear() {
        scrollView.scrollTo(0, 0);
        listContainer.removeAllViews();
    }

    /**
     * Shows rows instead of the results that are shown, at the same scroll position.
     * Rows of the same item are reused, so their thumbnail does not load again.
     *
     * @param rows Each row is tagged with the id of its item.
     */
    public void showRows(List<View> rows) {
        final int scrollY = scrollView.getScrollY();
        listContainer.removeAllViews();
        for (View row : rows) {
            listContainer.addView(row);
        }
        if (scrollY > 0) {
            scrollView.scrollTo(0, scrollY);
        }
    }

    /**
     * @return The rows that are shown, by the tag of their item.
     */
    public Map<String, View> getRows() {
        final int count = listContainer.getChildCount();
        Map<String, View> rows = new HashMap<>(2 * count);
        for (int i = 0; i < count; i++) {
            View child = listContainer.getChildAt(i);
            if (child.getTag() instanceof String tag) {
                rows.put(tag, child);
            }
        }
        return rows;
    }

    /**
     * Shows the videos instead of the results that are shown.
     *
     * @param playlistId    Playlist the picked video plays in, or null to play the video alone.
     * @param isLoadingMore Whether more results are still searched for, which shows a loading
     *                      animation after the results.
     */
    public void showVideos(List<ChannelSearchResult> results, @Nullable String playlistId, boolean isLoadingMore) {
        final String listParameter = playlistId == null ? "" : "&list=" + playlistId;
        Map<String, View> existingRows = getRows();
        List<View> rows = new ArrayList<>(results.size() + 1);

        for (ChannelSearchResult result : results) {
            View row = existingRows.get(result.videoId);
            if (row == null) {
                row = createVideoRow(result);
                row.setTag(result.videoId);
                String url = "https://www.youtube.com/watch?v=" + result.videoId + listParameter;
                row.setOnClickListener(view -> open(url));
            }
            rows.add(row);
        }

        if (isLoadingMore) {
            FrameLayout loading = new FrameLayout(activity);
            loading.setPadding(0, Dim.dp8, 0, Dim.dp8);
            loading.addView(createLoadingView(activity),
                    new FrameLayout.LayoutParams(Dim.dp24, Dim.dp24, Gravity.CENTER));
            rows.add(loading);
        }
        showRows(rows);
    }

    /**
     * Closes the sheet and opens a link of YouTube, such as a picked video.
     */
    public void open(String url) {
        dialog.dismiss();
        Utils.runOnMainThreadDelayed(() -> LoadVideoPatch.openVideoIntent(url, false),
                DIALOG_ANIMATION_DURATION_MILLISECONDS);
    }

    /**
     * The query alone reads like a global search, so the scope of the search is shown under it.
     */
    private View createHeader(String query) {
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

        scopeView.setText(scopeText);
        scopeView.setTextColor(foregroundColor);
        scopeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        scopeView.setAlpha(0.7f);
        scopeView.setSingleLine();
        scopeView.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(scopeView);

        header.addView(text);
        return header;
    }

    private View createDivider() {
        View divider = new View(activity);
        divider.setBackgroundColor(ThemeUtils.getAppForegroundColor());
        divider.setAlpha(0.12f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp1);
        params.setMargins(0, 0, 0, Dim.dp4);
        divider.setLayoutParams(params);
        return divider;
    }

    private View createVideoRow(ChannelSearchResult result) {
        LinearLayout row = createRowContainer(activity);
        row.addView(createVideoThumbnail(result));
        row.addView(createVideoText(result));
        return row;
    }

    private View createVideoThumbnail(ChannelSearchResult result) {
        ImageView thumbnail = createThumbnailView(activity);
        LinearLayout.LayoutParams thumbnailParams =
                new LinearLayout.LayoutParams(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        thumbnailParams.setMarginEnd(Dim.dp12);
        thumbnail.setLayoutParams(thumbnailParams);
        loadThumbnail(thumbnail, result.thumbnailUrl);

        return thumbnail;
    }

    private View createVideoText(ChannelSearchResult result) {
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

    public static ProgressBar createLoadingView(Activity activity) {
        ProgressBar loadingView = new ProgressBar(activity);
        loadingView.setIndeterminate(true);
        loadingView.setIndeterminateTintList(ColorStateList.valueOf(ThemeUtils.getAppForegroundColor()));
        return loadingView;
    }

    /**
     * Row of a result, which highlights when tapped.
     */
    public static LinearLayout createRowContainer(Activity activity) {
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

    /**
     * Image view of a thumbnail with rounded corners, to load with {@link #loadThumbnail}.
     */
    public static ImageView createThumbnailView(Activity activity) {
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
        return thumbnail;
    }

    public static void loadThumbnail(ImageView view, String url) {
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
}
