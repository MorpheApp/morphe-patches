package app.morphe.extension.music.jam;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

/** Secondary entry point for session setup before opening the player. */
@SuppressWarnings("deprecation")
public final class JamProbePreference extends Preference {
    public JamProbePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    @Override protected void onClick() {
        JamUi.open(getContext());
    }

}
