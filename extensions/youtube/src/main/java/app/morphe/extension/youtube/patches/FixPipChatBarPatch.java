package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.view.View;

@SuppressWarnings("unused")
public class FixPipChatBarPatch {

    /**
     * Injection point.
     */
    public static void onPipModeChanged(Activity activity, boolean isEnteringPip) {
        if (!isEnteringPip) return;
        View navBarBg = activity.findViewById(android.R.id.navigationBarBackground);
        if (navBarBg != null) navBarBg.setVisibility(View.GONE);
    }
}

