package app.morphe.extension.reddit.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import app.morphe.extension.reddit.settings.preference.RVXMorphedPreferenceFragment;
import app.morphe.extension.shared.ResourceUtils;

@SuppressWarnings("all")
public class ActivityHook {
    public static int getIcon() {
        return ResourceUtils.getDrawableIdentifier("icon_ai");
    }

    public static boolean hook(Activity activity) {
        Intent intent = activity.getIntent();
        if ("RVX".equals(intent.getStringExtra("com.reddit.extra.initial_url"))) {
            initialize(activity);
            return true;
        }
        return false;
    }

    public static void initialize(Activity activity) {
        SettingsStatus.load();

        final int fragmentId = View.generateViewId();
        final FrameLayout fragment = new FrameLayout(activity);
        fragment.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        fragment.setId(fragmentId);

        final LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setFitsSystemWindows(true);
        linearLayout.setTransitionGroup(true);
        linearLayout.addView(fragment);
        linearLayout.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        activity.setContentView(linearLayout);

        activity.getFragmentManager()
                .beginTransaction()
                .replace(fragmentId, new RVXMorphedPreferenceFragment())
                .commit();
    }

    public static boolean isAcknowledgment(Enum<?> e) {
        return e != null && "ACKNOWLEDGMENTS".equals(e.name());
    }

    public static Intent initializeByIntent(Context context) {
        Intent intent = new Intent();
        intent.setClassName(context, "com.reddit.webembed.browser.WebBrowserActivity");
        intent.putExtra("com.reddit.extra.initial_url", "RVX");
        return intent;
    }
}
