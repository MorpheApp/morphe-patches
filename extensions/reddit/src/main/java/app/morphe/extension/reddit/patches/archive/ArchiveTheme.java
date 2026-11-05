package app.morphe.extension.reddit.patches.archive;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/** Uses the host APK's resources and current theme, not the device's Material defaults. */
final class ArchiveTheme {
    final Context context;
    final int background, foreground, secondary, surface, divider;
    final Typeface regular, semibold, bold;

    ArchiveTheme(Context context) {
        this.context = context;
        background = attribute("rdt_ds_color_rpl_neutral_background", color("neutral_background"));
        foreground = attribute("rdt_body_text_color", color("rdt_content_strong_neutral"));
        secondary = attribute("rdt_meta_text_color", color("rdt_content_weak_neutral"));
        surface = color("rdt_background_weak_neutral");
        divider = attribute("rdt_line_color", secondary & 0x00ffffff | 0x33000000);
        regular = font("reddit_sans_regular"); semibold = font("reddit_sans_semi_bold"); bold = font("reddit_sans_bold");
    }

    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private int id(String name, String type) { return context.getResources().getIdentifier(name, type, context.getPackageName()); }
    private int color(String name) { return context.getColor(id(name, "color")); }
    private Typeface font(String name) { return context.getResources().getFont(id(name, "font")); }
    private int attribute(String name, int fallback) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(id(name, "attr"), value, true)) return fallback;
        if (value.resourceId != 0) return context.getColor(value.resourceId);
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT ? value.data : fallback;
    }

    Drawable icon(String name, int size, int tint) {
        Drawable icon = context.getDrawable(id(name, "drawable")).mutate();
        icon.setTint(tint); icon.setBounds(0, 0, dp(size), dp(size));
        return icon;
    }

    GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    Drawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf((foreground & 0x00ffffff) | 0x20000000),
                shape(color, radius), shape(0xffffffff, radius));
    }

    TextView text(int size, int color, Typeface face) {
        TextView view = new TextView(context);
        view.setTextSize(size); view.setTextColor(color); view.setTypeface(face);
        view.setIncludeFontPadding(false);
        return view;
    }
}
