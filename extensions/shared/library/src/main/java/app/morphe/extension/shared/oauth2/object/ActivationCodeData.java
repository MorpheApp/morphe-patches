package app.morphe.extension.shared.oauth2.object;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * OAuth2 activation code data.
 */
public class ActivationCodeData {
    /**
     * Used in requests for refresh token.
     */
    public final String deviceCode;
    /**
     * Activation code (Similar to SMS verification code).
     * User should enter this code in {@link #verificationUrl} and log in.
     */
    public final String userCode;
    /**
     * How long to cache activation code objects.
     * Typically, this value is '1800' (1800 seconds).
     */
    public final int expiresIn;
    /**
     * Typically, this value is '5' (5 seconds).
     * YouTube TV and YouTube VR send a request every 5 seconds to fetch a refresh token.
     */
    public final int interval;
    /**
     * Users enter their activation code here and log in.
     * Typically, this value is '<a href="https://www.google.com/device">...</a>'.
     */
    public final String verificationUrl;
    /**
     * When this code was fetched.
     */
    public final long fetchTime;

    public ActivationCodeData(@NonNull JSONObject json) throws JSONException {
        deviceCode = json.getString("device_code");
        userCode = json.getString("user_code");
        expiresIn = json.getInt("expires_in");
        interval = json.getInt("interval");
        verificationUrl = json.getString("verification_url");
        fetchTime = System.currentTimeMillis();
    }

    public boolean isExpired() {
        return expiresIn * 1000L < System.currentTimeMillis() - fetchTime;
    }

    @NonNull
    @Override
    public String toString() {
        return "ActivationCodeData{"
                + "deviceCode='" + deviceCode + '\''
                + ", userCode='" + userCode + '\''
                + ", expiresIn=" + expiresIn
                + ", interval=" + interval
                + ", verificationUrl='" + verificationUrl + '\''
                + '}';
    }
}