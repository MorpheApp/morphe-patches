package app.morphe.extension.shared.privacy;

import android.net.Uri;
import android.text.TextUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * Strips away specific parameters from links.
 */
public class LinkSanitizer {

    private static final Collection<String> parametersToRemove = List.of(
            "si",
            "is", // New (localized?) tracking parameter.
            "feature" // Old tracking parameter name, and may be obsolete.
    );
    private static final List<String> videoIdParameters =
            Arrays.asList("v", "w", "s");

    public static String returnSanitizedURLFromURI(String url) {
        Uri uri = Uri.parse(url);

        try {
            Uri.Builder builder = uri.buildUpon().clearQuery();

            if (!parametersToRemove.isEmpty()) {
                for (String paramName : uri.getQueryParameterNames()) {
                    if (!parametersToRemove.contains(paramName)) {
                        for (String value : uri.getQueryParameters(paramName)) {
                            builder.appendQueryParameter(paramName, value);
                        }
                    }
                }
            }

            // Convert invite link to a common video link
            String sanitizedURL =
                    builder.build().toString().replaceAll(
                            "sharing/invite/.+?\\?[a-zA-Z]=",
                            "watch?v="
                    );


            Logger.printInfo(() -> "Sanitized URL: " + uri + " to: " + sanitizedURL);

            return sanitizedURL;
        } catch (Exception ex) {
            Logger.printException(() -> "returnSanitizedURLFromURI failure: " + url, ex);
            return url;
        }
    }

    public static String replaceWithShortenedURL(String url) {
        try {
            if (url.contains("/sharing/invite/") || url.contains("/post/")) {
                return url;
            }
            Uri uri = Uri.parse(url);
            List<String> segments = uri.getPathSegments();
            int segmentsSize = segments.size();
            if (segmentsSize == 0) {
                return url;
            }
            String videoId = "";
            int currentGetVideoIDAttempts = 0;
            int maxGetVideoIDAttempts = 3;
            while (currentGetVideoIDAttempts <= maxGetVideoIDAttempts) {
                videoId = switch (currentGetVideoIDAttempts) {
                    case 0 -> uri.getQueryParameter(videoIdParameters.get(0));
                    case 1 -> uri.getQueryParameter(videoIdParameters.get(1));
                    case 2 -> uri.getQueryParameter(videoIdParameters.get(2));
                    default -> segments.size() > 1 ? segments.get(1) : "";
                };
                if (!TextUtils.isEmpty(videoId)) {
                    break;
                }
                currentGetVideoIDAttempts++;
            }
            Uri.Builder finalURL =
                    new Uri.Builder()
                            .scheme("https")
                            .authority("youtu.be")
                            .appendPath(videoId);
            if (currentGetVideoIDAttempts < maxGetVideoIDAttempts + 1) {
                for (String queryParameter : uri.getQueryParameterNames()) {
                    if (videoIdParameters.contains(queryParameter)) {
                        continue;
                    }

                    finalURL.appendQueryParameter(queryParameter, uri.getQueryParameter(queryParameter));
                }
                return finalURL.build().toString();
            }

            return url;
        } catch (Exception ex) {
            Logger.printException(() -> "replaceWithShortenedURL failure: " + url, ex);
            return url;
        }
    }
}
