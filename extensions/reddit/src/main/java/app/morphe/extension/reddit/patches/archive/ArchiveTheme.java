package app.morphe.extension.reddit.patches.archive;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.TextView;

/** Host fonts and icons, with colors captured from Reddit's active Compose palette. */
final class ArchiveTheme {
    final Context context;
    final int background, foreground, secondary, surface, divider, outline, action, upvote, downvote, content;
    final Typeface regular, semibold, bold;

    ArchiveTheme(Context context, int[] colors) {
        this.context = context;
        background = colors[0];
        foreground = colors[1];
        secondary = colors[2];
        surface = colors[3];
        divider = colors[4];
        outline = colors[5];
        action = colors[6];
        upvote = colors[7]; downvote = colors[8];
        content = colors[9];
        regular = font("roboto_regular"); semibold = font("roboto_medium"); bold = font("roboto_bold");
    }

    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    int sp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().scaledDensity); }
    private int id(String name, String type) { return context.getResources().getIdentifier(name, type, context.getPackageName()); }
    private Typeface font(String name) { return context.getResources().getFont(id(name, "font")); }

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

    Drawable outlined(boolean interactive) {
        GradientDrawable border = shape(android.graphics.Color.TRANSPARENT, 16);
        border.setStroke(dp(1), outline);
        return interactive ? new RippleDrawable(ColorStateList.valueOf((action & 0x00ffffff) | 0x20000000),
                border, shape(0xffffffff, 16)) : border;
    }

    TextView text(int size, int color, Typeface face) {
        TextView view = new TextView(context);
        view.setTextSize(size); view.setTextColor(color); view.setTypeface(face);
        view.setIncludeFontPadding(false);
        return view;
    }
}
