/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5482
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Share target for plain text. Opens the first YouTube link of the shared text
 * with the activity that opens YouTube links from other apps.
 */
@SuppressWarnings("unused")
public class OpenSharedLinksActivity extends Activity {

    /**
     * Activity alias of the app that opens YouTube links of other apps.
     */
    private static final String URL_ACTIVITY_CLASS_NAME = "com.google.android.youtube.UrlActivity";

    /**
     * Hosts the intent filter of {@link #URL_ACTIVITY_CLASS_NAME} accepts.
     */
    private static final List<String> YOUTUBE_HOSTS = Arrays.asList(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "youtu.be"
    );

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    /**
     * Punctuation that ends a sentence or encloses a link, and is not part of a YouTube link.
     */
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}>'\"";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            // Shared text can be styled text instead of a String.
            CharSequence text = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
            Uri uri = findYouTubeUri(text == null ? null : text.toString());
            if (uri == null) {
                Logger.printDebug(() -> "No YouTube link in shared text");
                Utils.showToastShort(str("morphe_open_shared_links_no_link"));
            } else {
                Logger.printDebug(() -> "Opening shared link: " + uri);
                startActivity(new Intent(Intent.ACTION_VIEW, uri)
                        .setClassName(this, URL_ACTIVITY_CLASS_NAME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onCreate failure", ex);
        }

        finish();
    }

    @Nullable
    private static Uri findYouTubeUri(@Nullable String text) {
        if (text == null) return null;

        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String url = matcher.group();
            int end = url.length();
            while (end > 0 && TRAILING_PUNCTUATION.indexOf(url.charAt(end - 1)) >= 0) {
                end--;
            }

            Uri uri = Uri.parse(url.substring(0, end));
            String host = uri.getHost();
            if (host != null && YOUTUBE_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
                return uri;
            }
        }

        return null;
    }
}
