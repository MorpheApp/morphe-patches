package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.view.View;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class FixPipChatBarPatch {

    /**
     * Injection point.
     */
    public static void onPipModeChanged(Activity activity, boolean isEnteringPip) {
        Logger.printVerbose(() -> "Picture-in-picture: " + isEnteringPip);
        if (!isEnteringPip) return;
        View navBarBg = activity.findViewById(android.R.id.navigationBarBackground);
        if (navBarBg != null) navBarBg.setVisibility(View.GONE);
    }
}
