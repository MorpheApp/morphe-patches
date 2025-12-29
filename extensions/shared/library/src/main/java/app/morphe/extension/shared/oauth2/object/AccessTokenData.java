package app.morphe.extension.shared.oauth2.object;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * OAuth2 access token data.
 */
public class AccessTokenData {
    /**
     * Access token in the authorization header, usually starting with 'ya29'.
     */
    public final String accessToken;
    /**
     * How long to cache access token objects.
     */
    public final int expiresIn;
    /**
     * Refresh token is used to reissue access token.
     * <p>
     * Access token expire within minutes or hours, but refresh token have no expiration time.
     * Theoretically, access token can be renewed an unlimited number of times with a refresh token
     * since a refresh token is similar to a GitHub PAT, which has no expiration date, this is not included in debug logs.
     */
    public final String refreshToken;
    /**
     * Typically, this value is '5' (5 seconds).
     * YouTube TV and YouTube VR send a request every 5 seconds to fetch a refresh token.
     */
    public final String scope;
    /**
     * Type of authorization header.
     * Typically, this value is 'Bearer'.
     */
    public final String tokenType;
    /**
     * When this code was fetched.
     */
    public final long fetchTime;

    public AccessTokenData(JSONObject json) throws JSONException {
        this(json.getString("refresh_token"), json);
    }

    public AccessTokenData(String refreshToken, JSONObject json) throws JSONException {
        accessToken = json.getString("access_token");
        expiresIn = json.getInt("expires_in");
        this.refreshToken = refreshToken;
        scope = json.getString("scope");
        tokenType = json.getString("token_type");
        fetchTime = System.currentTimeMillis();
    }

    public boolean isExpired() {
        return expiresIn * 1000L < System.currentTimeMillis() - fetchTime;
    }

    @NonNull
    @Override
    public String toString() {
        // Do not include refresh token in toString().
        return "AccessToken{"
                + "accessToken='" + accessToken + '\''
                + ", expiresIn='" + expiresIn + '\''
                + ", scope='" + scope + '\''
                + ", tokenType='" + tokenType + '\''
                + '}';
    }
}