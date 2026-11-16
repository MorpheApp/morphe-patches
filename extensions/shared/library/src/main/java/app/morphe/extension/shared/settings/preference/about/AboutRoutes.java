package app.morphe.extension.shared.settings.preference.about;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

class AboutRoutes {
    /**
     * Links to use if fetch links api call fails.
     */
    private static final List<MorpheAboutPreference.WebLink> NO_CONNECTION_STATIC_LINKS = List.of(
            new MorpheAboutPreference.WebLink("Website", "https://morphe.software")
    );

    private static final String API_URL = "https://api.morphe.software/v2";
    private static final Route.CompiledRoute API_ROUTE_ABOUT = new Route(Route.Method.GET, "/about").compile();
    private static final Route.CompiledRoute API_ROUTE_PEOPLE = new Route(Route.Method.GET, "/people").compile();

    private static final String GITHUB_URL = "https://raw.githubusercontent.com";
    private static final Route.CompiledRoute GITHUB_ROUTE_PATCHES = new Route(Route.Method.GET,
            (Utils.isPreReleasePatches()
                    ? "/MorpheApp/morphe-patches/refs/heads/dev/patches-bundle.json"
                    : "/MorpheApp/morphe-patches/refs/heads/main/patches-bundle.json")
    ).compile();

    @Nullable
    private static volatile String latestPatchesVersion;
    private static volatile long latestPatchesVersionLastCheckedTime;

    static boolean hasFetchedPatchersVersion() {
        final long updateCheckFrequency = 5 * 60 * 1000; // 5 minutes.
        final long now = System.currentTimeMillis();

        return latestPatchesVersion != null && (now - latestPatchesVersionLastCheckedTime) < updateCheckFrequency;
    }

    @Nullable
    static String getLatestPatchesVersion() {
        String version = latestPatchesVersion;
        if (version != null) return version;

        if (!Utils.isNetworkConnected()) return null;

        try {
            HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(
                    GITHUB_URL, GITHUB_ROUTE_PATCHES);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            Logger.printDebug(() -> "Fetching latest patches version links from: " + connection.getURL());

            // Do not show an exception toast if the server is down
            final int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                Logger.printDebug(() -> "Failed to get patches bundle. Response code: " + responseCode);
                return null;
            }

            JSONObject json = Requester.parseJSONObjectAndDisconnect(connection);
            version = json.getString("version");
            if (version.startsWith("v")) {
                version = version.substring(1);
            }
            latestPatchesVersion = version;
            latestPatchesVersionLastCheckedTime = System.currentTimeMillis();

            return version;
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Could not fetch patches version", ex); // No toast.
        } catch (JSONException ex) {
            Logger.printException(() -> "Could not parse about information", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to get patches version", ex);
        }

        return null;
    }

    @Nullable
    private static volatile List<MorpheAboutPreference.WebLink> fetchedLinks;

    static boolean hasFetchedLinks() {
        return fetchedLinks != null;
    }

    static List<MorpheAboutPreference.WebLink> fetchAboutLinks() {
        try {
            if (hasFetchedLinks()) return fetchedLinks;

            // Check if there is no internet connection.
            if (!Utils.isNetworkConnected()) return NO_CONNECTION_STATIC_LINKS;

            HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(API_URL, API_ROUTE_ABOUT);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            Logger.printDebug(() -> "Fetching social links from: " + connection.getURL());


            // Do not show an exception toast if the server is down
            final int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                Logger.printDebug(() -> "Failed to get about information. Response code: " + responseCode);
                return NO_CONNECTION_STATIC_LINKS;
            }

            JSONObject json = Requester.parseJSONObjectAndDisconnect(connection);

            List<MorpheAboutPreference.WebLink> links = new ArrayList<>();

            JSONArray donations = json.getJSONObject("donations").getJSONArray("links");
            for (int i = 0, length = donations.length(); i < length; i++) {
                MorpheAboutPreference.WebLink link = new MorpheAboutPreference.WebLink(donations.getJSONObject(i), true);
                if (link.preferred) {
                    links.add(link);
                }
            }

            JSONArray socials = json.getJSONArray("socials");
            for (int i = 0, length = socials.length(); i < length; i++) {
                MorpheAboutPreference.WebLink link = new MorpheAboutPreference.WebLink(socials.getJSONObject(i), false);

                links.add(link);
            }

            Logger.printDebug(() -> "links: " + links);

            return fetchedLinks = links;

        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Could not fetch about information", ex); // No toast.
        } catch (JSONException ex) {
            Logger.printException(() -> "Could not parse about information", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to get about information", ex);
        }

        return NO_CONNECTION_STATIC_LINKS;
    }

    /**
     * Someone behind Morphe, a contributor or a supporter.
     *
     * @param avatar Null for someone without a picture of their own, who goes by an initial.
     * @param mega   Whether a supporter is a Mega Supporter. Always false for a contributor.
     */
    record Person(String name, String url, @Nullable String avatar, boolean mega) {
    }

    /**
     * @param supportersTotal Every backer, the ones too many or too anonymous to list included.
     */
    record People(List<Person> contributors, List<Person> supporters, int supportersTotal) {
    }

    @Nullable
    private static volatile People fetchedPeople;

    private static List<Person> parsePeople(JSONArray array) throws JSONException {
        List<Person> people = new ArrayList<>(array.length());
        for (int i = 0, length = array.length(); i < length; i++) {
            JSONObject json = array.getJSONObject(i);
            String url = json.getString("url");
            // The dialogs hand these to a browser, which should never be asked for anything else.
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue;

            people.add(new Person(
                    json.getString("name"),
                    url,
                    json.isNull("avatar") ? null : json.getString("avatar"),
                    json.optBoolean("mega")
            ));
        }
        return people;
    }

    /**
     * Who committed to Morphe over the last year and who backs it, as put together by the API.
     * Must not be called from the main thread, unless the lists were fetched before.
     *
     * @return null if the lists could not be fetched.
     */
    @Nullable
    static People fetchPeople() {
        People people = fetchedPeople;
        if (people != null) return people;

        if (!Utils.isNetworkConnected()) return null;

        try {
            HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(API_URL, API_ROUTE_PEOPLE);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            Logger.printDebug(() -> "Fetching people from: " + connection.getURL());

            // Do not show an exception toast if the server is down
            final int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                Logger.printDebug(() -> "Failed to get people. Response code: " + responseCode);
                return null;
            }

            JSONObject json = Requester.parseJSONObjectAndDisconnect(connection);
            return fetchedPeople = new People(
                    parsePeople(json.getJSONArray("contributors")),
                    parsePeople(json.getJSONArray("supporters")),
                    json.optInt("supporters_total")
            );
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Could not fetch people", ex); // No toast.
        } catch (JSONException ex) {
            Logger.printException(() -> "Could not parse people", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to get people", ex);
        }

        return null;
    }
}
