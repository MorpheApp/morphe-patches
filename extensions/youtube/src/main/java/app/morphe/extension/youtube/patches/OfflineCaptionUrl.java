/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4450
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

/**
 * Caption urls of saved videos.
 * <p>
 * The caption urls of some download clients, such as visionOS, have no format.
 * The server then answers with the legacy format, whose text is escaped twice,
 * so the saved captions show entities such as {@code &#39;} instead of an apostrophe.
 * The app's own caption urls ask for srv3, which is escaped once.
 */
final class OfflineCaptionUrl {
    private static final String FORMAT_PARAMETER = "fmt";
    private static final String SRV3_FORMAT = FORMAT_PARAMETER + "=srv3";

    private OfflineCaptionUrl() {
    }

    /**
     * The format is not part of the signed parameters, so the url stays valid.
     *
     * @return The url with its format set to srv3, replacing any other format.
     */
    static String withSrv3Format(String url) {
        if (url.isEmpty()) {
            return url;
        }

        final int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return url + '?' + SRV3_FORMAT;
        }

        StringBuilder builder = new StringBuilder(url.length() + SRV3_FORMAT.length() + 1)
                .append(url, 0, queryStart + 1);
        for (String parameter : url.substring(queryStart + 1).split("&")) {
            if (parameter.isEmpty()
                    || parameter.equals(FORMAT_PARAMETER)
                    || parameter.startsWith(FORMAT_PARAMETER + '=')) {
                continue;
            }
            builder.append(parameter).append('&');
        }
        return builder.append(SRV3_FORMAT).toString();
    }
}
