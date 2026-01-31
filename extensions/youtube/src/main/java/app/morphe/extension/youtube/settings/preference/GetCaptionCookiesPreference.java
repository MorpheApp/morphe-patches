package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation"})
public class GetCaptionCookiesPreference extends Preference implements Preference.OnPreferenceClickListener {
    {
        setSelectable(true);
        setOnPreferenceClickListener(this);
        setEnabled(Settings.SET_CAPTION_COOKIES.get());
    }

    private final List<String> COOKIES_HEADER_KEYS = Arrays.asList(
            "YSC",
            "VISITOR_INFO1_LIVE",
            "VISITOR_PRIVACY_METADATA",
            "__Secure-ROLLOUT_TOKEN"
    );
    private final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private final String YOUTUBE_SERVICE_WORKER_URL =
            "https://www.youtube.com/sw.js";
    private final String YOUTUBE_URL =
            "https://www.youtube.com/";

    public GetCaptionCookiesPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public GetCaptionCookiesPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public GetCaptionCookiesPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GetCaptionCookiesPreference(Context context) {
        super(context);
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String newValue = fetchCookieString();

        if (Utils.isNotEmpty(newValue)) {
            String cookieString = Settings.CAPTION_COOKIES.get();
            if (!newValue.equals(cookieString)) {
                Logger.printDebug(() -> "new Cookies loaded: " + newValue);

                // For some reason, using 'Settings.CAPTION_COOKIES.save(newValue)' resets the settings.
                // TODO: Find the cause of the issue and replace it with 'Settings.CAPTION_COOKIES.save(newValue)'
                AbstractPreferenceFragment.settingImportInProgress = true;
                Setting.privateSetValueFromString(Settings.CAPTION_COOKIES, newValue);
                Settings.CAPTION_COOKIES.saveToPreferences();
                AbstractPreferenceFragment.settingImportInProgress = false;
                AbstractPreferenceFragment.showRestartDialog(getContext());

                Utils.showToastShort(str("morphe_get_caption_cookies_success"));
            } else {
                Utils.showToastShort(str("morphe_get_caption_cookies_duplicate"));
            }
        } else {
            Utils.showToastShort(str("morphe_get_caption_cookies_failed"));
        }

        return true;
    }

    @SuppressWarnings("ExtractMethodRecommender")
    private String fetchCookieString() {
        List<String> setCookies = null;

        try {
            final long start = System.currentTimeMillis();
            setCookies = Utils.submitOnBackgroundThread(() -> {
                final int connectionTimeoutMillis = 5000;
                URL url = new URL(YOUTUBE_SERVICE_WORKER_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Referer", YOUTUBE_URL);
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Language", "en-US, en;q=0.9");
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(connectionTimeoutMillis);
                connection.setReadTimeout(connectionTimeoutMillis);
                final int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    List<String> cookies = connection.getHeaderFields().get("Set-Cookie");
                    connection.disconnect();
                    return cookies;
                }
                connection.disconnect();
                return null;
            }).get();
            Logger.printDebug(() -> "Fetch took " + (System.currentTimeMillis() - start) + "ms");
        } catch (ExecutionException | InterruptedException ex) {
            Logger.printException(() -> "Could not fetch cookie string", ex);
        }

        return parseSetCookieToCookieString(setCookies);
    }

    private String parseSetCookieToCookieString(List<String> setCookies) {
        if (setCookies != null && !setCookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();

            for (String setCookie : setCookies) {
                String entry = setCookie.split(";")[0].trim();
                int equalIndex = entry.indexOf("=");

                if (equalIndex > 0) {
                    String key = entry.substring(0, equalIndex).trim();

                    if (COOKIES_HEADER_KEYS.contains(key)) {
                        if (sb.length() > 0) {
                            sb.append("; ");
                        }
                        sb.append(entry);
                    }
                }
            }

            return sb.toString();
        }
        return "";
    }

}
