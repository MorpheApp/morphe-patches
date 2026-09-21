/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
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
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.utils.requests.PlaylistSearchRequest;
import app.morphe.extension.youtube.patches.utils.requests.PlaylistSearchRequest.PlaylistSearchResponse;
import app.morphe.extension.youtube.patches.utils.requests.PlaylistSearchRequest.PlaylistSearchResult;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class PlaylistSearchPatch {

    private static final int THUMBNAIL_WIDTH = Dim.dp(120);
    private static final int THUMBNAIL_HEIGHT = Dim.dp(68);
    private static final int THUMBNAIL_CORNER_RADIUS = Dim.dp(8);
    private static final int THUMBNAIL_TIMEOUT_MILLISECONDS = 10 * 1000;
    private static final int DIALOG_ANIMATION_DURATION_MILLISECONDS = 300;
    private static final long DUPLICATE_SUBMIT_MILLISECONDS = 1000;

    private static final Map<String, Bitmap> thumbnailCache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(40));

    private static WeakReference<Activity> mainActivityRef = new WeakReference<>(null);
    private static String currentBrowseId = "";
    private static String lastQuery = "";
    private static long lastQueryTime;

    public static void setMainActivity(Activity activity) {
        mainActivityRef = new WeakReference<>(activity);
    }

    public static void setBrowseId(@Nullable String browseId) {
        currentBrowseId = browseId == null ? "" : browseId;
    }

    public static void clearBrowseId() {
        currentBrowseId = "";
    }

    public static boolean searchInPlaylist(@Nullable String query) {
        try {
            if (!Settings.PLAYLIST_SEARCH.get() || query == null || query.isEmpty()) {
                return false;
            }

            final String browseId = currentBrowseId;
            if (!isPlaylistBrowseId(browseId)) {
                return false;
            }

            final String playlistId = browseId.substring(2);
            Activity activity = mainActivityRef.get();
            if (activity == null) return false;

            final long now = System.currentTimeMillis();
            if (query.equals(lastQuery)
                    && now - lastQueryTime < DUPLICATE_SUBMIT_MILLISECONDS) {
                return true;
            }
            lastQuery = query;
            lastQueryTime = now;

            Logger.printDebug(() -> "Searching playlist " + playlistId + " for: " + query);

            Utils.runOnBackgroundThread(() -> {
                PlaylistSearchResponse response = PlaylistSearchRequest
                        .fetchRequestIfNeeded(
                                playlistId,
                                query,
                                app.morphe.extension.shared.innertube.utils.AuthUtils.getRequestHeader()
                        )
                        .getResponse();

                Utils.runOnMainThread(() -> {
                    if (response == null) {
                        Utils.showToastShort(str("morphe_playlist_search_failed"));
                    } else if (response.results.isEmpty()) {
                        Utils.showToastShort(str("morphe_playlist_search_no_results"));
                    } else {
                        showResults(activity, query, playlistId, response);
                    }
                });
            });

            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "searchInPlaylist failure", ex);
        }
        return false;
    }

    private static void showResults(
            Activity activity,
            String query,
            String playlistId,
            PlaylistSearchResponse response
    ) {
        try {
            hideKeyboard(activity);

            SheetBottomDialog.DraggableLinearLayout mainLayout =
                    SheetBottomDialog.createMainLayout(activity, null);
            mainLayout.addView(createHeader(activity, query));
            mainLayout.addView(createDivider(activity));

            LinearLayout listContainer = new LinearLayout(activity);
            listContainer.setOrientation(LinearLayout.VERTICAL);

            ScrollView scrollView = SheetBottomDialog.createCappedScrollView(activity);
            scrollView.addView(listContainer);
            mainLayout.addView(scrollView);

            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(
                    activity,
                    mainLayout,
                    DIALOG_ANIMATION_DURATION_MILLISECONDS
            );

            for (PlaylistSearchResult result : response.results) {
                View row = createResultRow(activity, result);
                row.setOnClickListener(view -> {
                    dialog.dismiss();
                    Utils.runOnMainThreadDelayed(() -> {
                        closeSearch(activity);
                        LoadVideoPatch.openVideoIntent(
                                "https://www.youtube.com/watch?v="
                                        + result.videoId + "&list=" + playlistId,
                                false
                        );
                    }, DIALOG_ANIMATION_DURATION_MILLISECONDS);
                });
                listContainer.addView(row);
            }

            dialog.setOnDismissListener(dismissed -> thumbnailCache.clear());
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showResults failure", ex);
        }
    }

    private static View createHeader(Activity activity, String query) {
        final int foregroundColor = ThemeUtils.getAppForegroundColor();

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp12);

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(BaseSearchViewController.getSearchIconDrawable());
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(Dim.dp24, Dim.dp24);
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

        TextView scopeView = new TextView(activity);
        scopeView.setText(str("morphe_playlist_search_results"));
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

    private static View createResultRow(
            Activity activity,
            PlaylistSearchResult result
    ) {
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

        ImageView thumbnail = new ImageView(activity);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setClipToOutline(true);
        thumbnail.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(
                        0, 0, view.getWidth(), view.getHeight(), THUMBNAIL_CORNER_RADIUS);
            }
        });
        LinearLayout.LayoutParams thumbnailParams =
                new LinearLayout.LayoutParams(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        thumbnailParams.setMarginEnd(Dim.dp12);
        thumbnail.setLayoutParams(thumbnailParams);
        loadThumbnail(thumbnail, result.thumbnailUrl);
        row.addView(thumbnail);

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

        row.addView(text);
        return row;
    }

    private static void loadThumbnail(ImageView view, String url) {
        if (url.isEmpty()) return;

        Bitmap cached = thumbnailCache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        WeakReference<ImageView> viewRef = new WeakReference<>(view);
        Utils.runOnBackgroundThread(() -> {
            try {
                HttpURLConnection connection =
                        (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);

                Bitmap bitmap;
                try (InputStream stream = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(stream);
                }
                if (bitmap == null) return;

                thumbnailCache.put(url, bitmap);
                Utils.runOnMainThread(() -> {
                    ImageView target = viewRef.get();
                    if (target != null) target.setImageBitmap(bitmap);
                });
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not load thumbnail: " + url, ex);
            }
        });
    }

    private static void hideKeyboard(Activity activity) {
        View focused = activity.getCurrentFocus();
        if (focused == null) return;

        InputMethodManager manager =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    @SuppressWarnings("deprecation")
    private static void closeSearch(Activity activity) {
        try {
            activity.onBackPressed();
        } catch (Exception ex) {
            Logger.printException(() -> "closeSearch failure", ex);
        }
    }

    private static boolean isPlaylistBrowseId(String browseId) {
        return browseId.startsWith("VL") && browseId.length() > 2;
    }
}
