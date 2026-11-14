/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4803
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceCategory;
import android.preference.TwoStatePreference;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.WrapperListAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.patches.CustomFontPatch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Styles the Morphe settings like the Compose settings of Reddit 2026.25.0+:
 * bold uppercase section headers, the colors of the app, a caret on rows that open something,
 * and the Reddit toggle instead of the platform switch.
 * <p>
 * Sizes are measured from the settings of the app, which use the system font (or the user's
 * custom font when {@link CustomFontPatch} is active).
 * Colors follow the active Reddit theme (Alien Blue, Mint, Pony, Trees, Night, and AMOLED).
 * <p>
 * Older app versions keep the platform preference style, which matches their settings.
 */
@SuppressWarnings({"deprecation", "unused"})
public final class RedditSettingsStyle {

    /**
     * Views that were already styled, so recycled views are not changed twice.
     */
    private static final Set<View> styledViews = Collections.newSetFromMap(new WeakHashMap<>());

    private static final Typeface DEFAULT_BODY_TYPEFACE = Typeface.DEFAULT;
    private static final Typeface DEFAULT_BOLD_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    private static Typeface bodyTypeface = DEFAULT_BODY_TYPEFACE;
    private static Typeface boldTypeface = DEFAULT_BOLD_TYPEFACE;

    /**
     * If the patched app uses the Compose settings.
     * Return value is changed during patching.
     */
    public static boolean isModernStyle() {
        return false;
    }

    // region Colors and fonts.

    /**
     * Color palettes built into Reddit's Compose settings screen for each theme.
     */
    private enum Palette {
        LIGHT(
                false,
                0xFFFFFFFF, // background
                0xFFFFFFFF, // dialogSurface
                0xFF181C1F, // dialogTitle
                0xFF181C1F, // dialogText
                0xFFE9EBED, // dialogButtonBg
                0xFF181C1F, // dialogButtonText
                0xFF181C1F, // text & back arrow
                0xFF5C6C74, // header
                0xFF5C6C74, // secondary (subtitles, caret)
                0xFFE5EBEE, // trackUnchecked
                0xFF717578, // trackOutline
                0xFF737577, // knobUnchecked (gray)
                0xFF000000, // trackChecked
                0xFFFFFFFF, // knobChecked
                0xFF181C1F  // checkMark
        ),
        MINT(
                false,
                0xFFFFFFFF, // background
                0xFFFFFFFF, // dialogSurface
                0xFF464748, // dialogTitle
                0xFF737577, // dialogText
                0xFFE9EBED, // dialogButtonBg
                0xFF464748, // dialogButtonText
                0xFF4D5753, // text & back arrow (mint_tone3/text)
                0xFF4F5A56, // header (mint section headers)
                0xFF4F5A56, // secondary (subtitles, caret)
                0xFFE3ECE9, // trackUnchecked
                0xFF717876, // trackOutline
                0xFF737577, // knobUnchecked (gray)
                0xFF00C8AA, // trackChecked (teal)
                0xFFFFFFFF, // knobChecked
                0xFF181C1F  // checkMark
        ),
        NIGHT(
                true,
                0xFF0E1113, // background
                0xFF121617, // dialogSurface
                0xFFDBE4E9, // dialogTitle
                0xFFDBE4E9, // dialogText
                0xFF2A3236, // dialogButtonBg
                0xFFDBE4E9, // dialogButtonText
                0xFFDBE4E9, // text & back arrow
                0xFF8BA2AD, // header
                0xFF8BA2AD, // secondary
                0xFF2A3236, // trackUnchecked
                0xFF979B9E, // trackOutline
                0xFF8BA2AD, // knobUnchecked
                0xFFEEF1F3, // trackChecked
                0xFF181C1F, // knobChecked
                0xFFEEF1F3  // checkMark
        ),
        PONY(
                false,
                0xFFFFFFFF, // background
                0xFFFFFFFF, // dialogSurface
                0xFF2E2E61, // dialogTitle
                0xFFD5346A, // dialogText
                0xFFFAECDB, // dialogButtonBg
                0xFF2E2E61, // dialogButtonText
                0xFFD5346A, // text & back arrow (pony_tone2)
                0xFFD5346A, // header
                0xFFD5346A, // secondary
                0xFFFDF8F4, // trackUnchecked
                0xFF7B7774, // trackOutline (warm gray)
                0xFFD5346A, // knobUnchecked (pink)
                0xFFFF479D, // trackChecked (hot pink)
                0xFFFFFFFF, // knobChecked
                0xFF181C1F  // checkMark
        ),
        TREES(
                false,
                0xFFFFFFFF, // background
                0xFFFFFFFF, // dialogSurface
                0xFF255D35, // dialogTitle
                0xFF3E8447, // dialogText
                0xFFEEFAD6, // dialogButtonBg
                0xFF255D35, // dialogButtonText
                0xFF3E8447, // text & back arrow (trees_tone2)
                0xFF3E8447, // header
                0xFF3E8447, // secondary
                0xFFF4FAF0, // trackUnchecked
                0xFF74796F, // trackOutline (olive-gray)
                0xFF255D35, // knobUnchecked (forest green)
                0xFF52C92E, // trackChecked (bright green)
                0xFFFFFFFF, // knobChecked
                0xFF181C1F  // checkMark
        ),
        AMOLED(
                true,
                0xFF000000, // background
                0xFF121212, // dialogSurface
                0xFFD4D7D9, // dialogTitle
                0xFFD4D7D9, // dialogText
                0xFF1E1E1E, // dialogButtonBg
                0xFFD4D7D9, // dialogButtonText
                0xFFD4D7D9, // text & back arrow
                0xFF898D90, // header
                0xFF898D90, // secondary
                0xFF1E1E1E, // trackUnchecked
                0xFF8F8F8F, // trackOutline
                0xFF898D90, // knobUnchecked
                0xFFEEF1F3, // trackChecked
                0xFF161616, // knobChecked
                0xFFEEF1F3  // checkMark
        );

        final boolean isDark;
        final int background;
        final int dialogSurface;
        final int dialogTitle;
        final int dialogText;
        final int dialogButtonBg;
        final int dialogButtonText;
        final int text;
        final int header;
        final int secondary;
        final int trackUnchecked;
        final int trackOutline;
        final int knobUnchecked;
        final int trackChecked;
        final int knobChecked;
        final int checkMark;

        Palette(boolean isDark, int background, int dialogSurface,
                int dialogTitle, int dialogText, int dialogButtonBg, int dialogButtonText,
                int text, int header, int secondary,
                int trackUnchecked, int trackOutline, int knobUnchecked,
                int trackChecked, int knobChecked, int checkMark) {
            this.isDark = isDark;
            this.background = background;
            this.dialogSurface = dialogSurface;
            this.dialogTitle = dialogTitle;
            this.dialogText = dialogText;
            this.dialogButtonBg = dialogButtonBg;
            this.dialogButtonText = dialogButtonText;
            this.text = text;
            this.header = header;
            this.secondary = secondary;
            this.trackUnchecked = trackUnchecked;
            this.trackOutline = trackOutline;
            this.knobUnchecked = knobUnchecked;
            this.trackChecked = trackChecked;
            this.knobChecked = knobChecked;
            this.checkMark = checkMark;
        }
    }

    private static Palette currentPalette = Palette.NIGHT;
    private static boolean themeColorsLoaded;

    /**
     * Resolves the active Reddit theme and applies the matching color palette and fonts.
     * Must be called before the settings are shown, and again if the app theme can have changed.
     */
    public static void loadThemeColors(@Nullable Activity activity) {
        styledViews.clear();
        themeColorsLoaded = true;
        resolveTypefaces(activity);
        currentPalette = detectPalette(activity);
        Utils.setIsDarkModeEnabled(currentPalette.isDark);

        // Sync ThemeUtils so Morphe's CustomDialog (App Icon picker, Restart, Import/Export, etc.)
        // uses Reddit's elevated dialog surface color and custom text/button styling.
        ThemeUtils.setThemeLightColor(currentPalette.isDark ? Palette.LIGHT.dialogSurface : currentPalette.dialogSurface);
        ThemeUtils.setThemeDarkColor(currentPalette.dialogSurface);
        ThemeUtils.setDialogThemeColors(
                currentPalette.dialogSurface,
                currentPalette.dialogTitle,
                currentPalette.dialogText,
                currentPalette.dialogButtonBg,
                currentPalette.dialogButtonText
        );

        Logger.printDebug(() -> "Theme palette: " + currentPalette.name()
                + " dark: " + currentPalette.isDark
                + " text: " + Integer.toHexString(currentPalette.text)
                + " header: " + Integer.toHexString(currentPalette.header)
                + " secondary: " + Integer.toHexString(currentPalette.secondary)
                + " background: " + Integer.toHexString(currentPalette.background)
                + " dialogSurface: " + Integer.toHexString(currentPalette.dialogSurface));
    }

    /**
     * Uses the user's custom font when {@link CustomFontPatch} is enabled,
     * otherwise uses the system font (which stock Compose settings and ForceSystemFontPatch use).
     */
    private static void resolveTypefaces(@Nullable Activity activity) {
        bodyTypeface = DEFAULT_BODY_TYPEFACE;
        boldTypeface = DEFAULT_BOLD_TYPEFACE;
        if (!CustomFontPatch.isPatchIncluded()) return;

        Resources res = activity != null ? activity.getResources() : null;
        Typeface customRegular = CustomFontPatch.getCustomTypeface(res, "regular", Typeface.NORMAL);
        Typeface customBold = CustomFontPatch.getCustomTypeface(res, "bold", Typeface.BOLD);
        if (customRegular != null) {
            bodyTypeface = customRegular;
        }
        if (customBold != null) {
            boldTypeface = customBold;
        } else if (customRegular != null) {
            boldTypeface = Typeface.create(customRegular, Typeface.BOLD);
        }
    }

    private static Palette detectPalette(@Nullable Activity activity) {
        if (activity != null) {
            try {
                Resources.Theme theme = activity.getTheme();

                // 1. Primary detection: Reddit's built-in redditBaseTheme enum attribute.
                final int baseThemeAttr = ResourceUtils.getAttrIdentifier("redditBaseTheme");
                if (baseThemeAttr != 0) {
                    TypedValue value = new TypedValue();
                    if (theme.resolveAttribute(baseThemeAttr, value, true)) {
                        switch (value.data) {
                            case 0: return Palette.LIGHT;  // AlienBlue
                            case 1: return Palette.MINT;   // Mint
                            case 2:                        // Night
                            case 6: return Palette.NIGHT;  // AnonymousBrowsing
                            case 3: return Palette.PONY;   // Pony
                            case 4: return Palette.TREES;  // Trees
                            case 5: return Palette.AMOLED; // Amoled
                        }
                    }
                }

                // 2. Fallback: tone8 background check.
                Integer tone8 = resolveTone8Color(activity, theme);
                if (tone8 != null) {
                    if (luminance(tone8) >= 0.5f) {
                        return Palette.LIGHT;
                    }
                    return (tone8 & 0x00FFFFFF) == 0 ? Palette.AMOLED : Palette.NIGHT;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "detectPalette failure", ex);
            }
        }

        // Fallback to Morphe's dark-mode state.
        return Utils.isDarkModeEnabled() ? Palette.NIGHT : Palette.LIGHT;
    }

    @Nullable
    private static Integer resolveTone8Color(Activity activity, Resources.Theme theme) {
        final int attribute = ResourceUtils.getAttrIdentifier("rdt_ds_color_tone8");
        if (attribute == 0) return null;
        TypedValue value = new TypedValue();
        if (!theme.resolveAttribute(attribute, value, true)) return null;
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        return value.resourceId != 0 ? activity.getColor(value.resourceId) : null;
    }

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

    private static Palette palette() {
        if (!themeColorsLoaded) {
            return Utils.isDarkModeEnabled() ? Palette.NIGHT : Palette.LIGHT;
        }
        return currentPalette;
    }

    private static boolean isDark() {
        return palette().isDark;
    }

    /**
     * Background of the settings.
     */
    public static int getBackgroundColor() {
        if (!themeColorsLoaded) return ThemeUtils.getAppBackgroundColor();
        return currentPalette.background;
    }

    private static int backgroundColor() {
        return getBackgroundColor();
    }

    private static int withAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(alpha * 255) << 24);
    }

    /**
     * Toolbar title. The app uses pure white or black for screen titles.
     */
    public static int getTitleColor() {
        return isDark() ? Color.WHITE : Color.BLACK;
    }

    /**
     * Text of rows and the toolbar icons.
     */
    public static int getTextColor() {
        return palette().text;
    }

    /**
     * Color of section headers ("GENERAL", "ADS", etc.).
     */
    private static int headerColor() {
        return palette().header;
    }

    /**
     * Color of subtitles, the caret, and the unchecked toggle knob.
     */
    private static int secondaryColor() {
        return palette().secondary;
    }

    private static ColorStateList textColors(int color) {
        return new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{withAlpha(color, 0.38f), color}
        );
    }

    // endregion

    // region List.

    /**
     * Styles the rows of a preference list. The preference fragment sets a new adapter whenever
     * the preferences change, so the adapter is wrapped again before the next frame is drawn.
     *
     * @return The listener to remove when the list is destroyed.
     */
    public static ViewTreeObserver.OnPreDrawListener styleList(ListView listView) {
        listView.setBackgroundColor(backgroundColor());
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, Dim.dp16);

        ViewTreeObserver.OnPreDrawListener listener = () -> {
            ListAdapter adapter = listView.getAdapter();
            if (adapter == null || adapter instanceof StyledAdapter) {
                return true;
            }
            listView.setAdapter(new StyledAdapter(adapter));
            // Skip the frame with the unstyled rows.
            return false;
        };
        listView.getViewTreeObserver().addOnPreDrawListener(listener);
        return listener;
    }

    private record StyledAdapter(ListAdapter adapter) implements WrapperListAdapter {

        @Override
        public ListAdapter getWrappedAdapter() {
            return adapter;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = adapter.getView(position, convertView, parent);
            try {
                styleRow(view, adapter.getItem(position), position);
            } catch (Exception ex) {
                Logger.printException(() -> "styleRow failure", ex);
            }
            return view;
        }

        // Everything else is the same as the preference adapter.
        @Override
        public boolean areAllItemsEnabled() {
            return adapter.areAllItemsEnabled();
        }

        @Override
        public boolean isEnabled(int position) {
            return adapter.isEnabled(position);
        }

        @Override
        public void registerDataSetObserver(DataSetObserver observer) {
            adapter.registerDataSetObserver(observer);
        }

        @Override
        public void unregisterDataSetObserver(DataSetObserver observer) {
            adapter.unregisterDataSetObserver(observer);
        }

        @Override
        public int getCount() {
            return adapter.getCount();
        }

        @Override
        public Object getItem(int position) {
            return adapter.getItem(position);
        }

        @Override
        public long getItemId(int position) {
            return adapter.getItemId(position);
        }

        @Override
        public boolean hasStableIds() {
            return adapter.hasStableIds();
        }

        @Override
        public int getItemViewType(int position) {
            return adapter.getItemViewType(position);
        }

        @Override
        public int getViewTypeCount() {
            return adapter.getViewTypeCount();
        }

        @Override
        public boolean isEmpty() {
            return adapter.isEmpty();
        }
    }

    // endregion

    // region Rows.

    private static void styleRow(View view, Object item, int position) {
        TextView title = view.findViewById(android.R.id.title);
        if (title == null) return;

        if (item instanceof PreferenceCategory) {
            styleSectionHeader(view, title, position == 0);
            return;
        }

        // Text is set again for every bind, the style only once per view.
        if (styledViews.add(view)) {
            view.setMinimumHeight(Dim.dp48);
            view.setPaddingRelative(Dim.dp16, 0, Dim.dp16, 0);

            title.setTypeface(bodyTypeface);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTextColor(textColors(getTextColor()));

            // Container of the title and the subtitle.
            final Object titleParent = title.getParent();
            if (titleParent instanceof View textContainer && titleParent != view) {
                textContainer.setPaddingRelative(
                        textContainer.getPaddingStart(), Dim.dp12, textContainer.getPaddingEnd(), Dim.dp12);
            }

            TextView summary = view.findViewById(android.R.id.summary);
            if (summary != null) {
                summary.setTypeface(bodyTypeface);
                summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                summary.setTextColor(textColors(secondaryColor()));
            }

            View icon = view.findViewById(android.R.id.icon);
            // Without an icon, the platform keeps space for one. Reddit rows start at the edge.
            if (icon instanceof ImageView && ((ImageView) icon).getDrawable() == null) {
                hideIconFrame(view, icon);
            }
        }

        Switch toggle = findSwitch(view);
        if (toggle != null) {
            styleToggle(toggle);
        } else if (!(item instanceof TwoStatePreference)) {
            addCaret(view);
        }
    }

    private static void styleSectionHeader(View view, TextView title, boolean isFirst) {
        // Recycled views can change between the first and another position.
        final int topPadding = isFirst ? Dim.dp16 : Dim.dp24;
        if (view == title) {
            title.setPaddingRelative(Dim.dp16, topPadding, Dim.dp16, Dim.dp8);
        } else {
            title.setPaddingRelative(0, topPadding, 0, Dim.dp8);
        }

        if (!styledViews.add(view)) return;

        title.setAllCaps(true);
        title.setTypeface(boldTypeface);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setLetterSpacing(0);
        title.setTextColor(headerColor());

        view.setMinimumHeight(0);
        if (view != title) {
            view.setPaddingRelative(Dim.dp16, 0, Dim.dp16, 0);
            title.setMinHeight(0);
            title.setMinimumHeight(0);

            View icon = view.findViewById(android.R.id.icon);
            if (icon != null) {
                hideIconFrame(view, icon);
            }
        }
    }

    private static void hideIconFrame(View row, View icon) {
        final Object iconParent = icon.getParent();
        ((iconParent instanceof View && iconParent != row) ? (View) iconParent : icon)
                .setVisibility(View.GONE);
    }

    @Nullable
    private static Switch findSwitch(View view) {
        if (view instanceof Switch) return (Switch) view;
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                Switch toggle = findSwitch(group.getChildAt(i));
                if (toggle != null) return toggle;
            }
        }
        return null;
    }

    /**
     * Adds the caret Reddit shows on rows that open another screen or a dialog.
     */
    private static void addCaret(View view) {
        ViewGroup widgetFrame = view.findViewById(android.R.id.widget_frame);
        if (widgetFrame == null || styledViews.contains(widgetFrame)) return;

        Drawable caret = ResourceUtils.getDrawable("icon_caret_right");
        if (caret == null) return;
        styledViews.add(widgetFrame);

        caret = caret.mutate();
        caret.setTint(secondaryColor());

        ImageView caretView = new ImageView(view.getContext());
        caretView.setImageDrawable(caret);
        caretView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Dim.dp20, Dim.dp20);
        params.setMarginStart(Dim.dp8);
        widgetFrame.addView(caretView, params);
        widgetFrame.setVisibility(View.VISIBLE);
        widgetFrame.setMinimumWidth(0);
        widgetFrame.setPaddingRelative(0, 0, 0, 0);
    }

    // endregion

    // region Toggle.

    private static void styleToggle(Switch toggle) {
        if (!styledViews.add(toggle)) return;

        toggle.setThumbTintList(null);
        toggle.setTrackTintList(null);
        toggle.setThumbDrawable(new ToggleThumbDrawable());
        toggle.setTrackDrawable(new ToggleTrackDrawable());
        toggle.setShowText(false);
        toggle.setSwitchMinWidth(TOGGLE_WIDTH);
        toggle.setBackground(null);
    }

    private static final int TOGGLE_WIDTH = Dim.dp(52);
    private static final int TOGGLE_HEIGHT = Dim.dp(32);
    /**
     * The knob center is half the track height from the track end, and moves 20dp.
     * The thumb bounds are narrower than the checked knob, which is drawn past them.
     */
    private static final int THUMB_WIDTH = Dim.dp(20);
    private static final int TRACK_PADDING = (TOGGLE_HEIGHT - THUMB_WIDTH) / 2;
    private static final float KNOB_CHECKED_RADIUS = Dim.dp(12);
    private static final float KNOB_UNCHECKED_RADIUS = Dim.dp(8);
    private static final float TRACK_OUTLINE_WIDTH = Dim.dp(2);
    private static final float CHECK_STROKE_WIDTH = Dim.dp(1.4f);

    // Toggle colors of the active palette.
    private static int trackCheckedColor() {
        return palette().trackChecked;
    }

    private static int knobCheckedColor() {
        return palette().knobChecked;
    }

    private static int trackUncheckedColor() {
        return palette().trackUnchecked;
    }

    private static int trackOutlineColor() {
        return palette().trackOutline;
    }

    private static int knobUncheckedColor() {
        return palette().knobUnchecked;
    }

    private abstract static class StatefulDrawable extends Drawable {
        protected final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        protected boolean checked;
        protected boolean enabled = true;
        private int alpha = 255;

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean newChecked = false, newEnabled = false;
            for (int s : state) {
                if (s == android.R.attr.state_checked) newChecked = true;
                else if (s == android.R.attr.state_enabled) newEnabled = true;
            }
            if (newChecked == checked && newEnabled == enabled) return false;
            checked = newChecked;
            enabled = newEnabled;
            invalidateSelf();
            return true;
        }

        protected int color(int color) {
            final float stateAlpha = enabled ? 1f : 0.4f;
            return withAlpha(color, (Color.alpha(color) / 255f) * stateAlpha * (alpha / 255f));
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Rounded track. Checked: filled. Unchecked: filled and outlined.
     */
    private static final class ToggleTrackDrawable extends StatefulDrawable {
        private final RectF rect = new RectF();

        @Override
        public void draw(@NonNull Canvas canvas) {
            rect.set(getBounds());
            float radius = rect.height() / 2;
            paint.setStyle(Paint.Style.FILL);

            if (checked) {
                paint.setColor(color(trackCheckedColor()));
                canvas.drawRoundRect(rect, radius, radius, paint);
            } else {
                // Outline inside the bounds.
                paint.setColor(color(trackOutlineColor()));
                canvas.drawRoundRect(rect, radius, radius, paint);
                rect.inset(TRACK_OUTLINE_WIDTH, TRACK_OUTLINE_WIDTH);
                radius = rect.height() / 2;
                paint.setColor(color(trackUncheckedColor()));
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
        }

        @Override
        public boolean getPadding(Rect padding) {
            padding.set(TRACK_PADDING, TRACK_PADDING, TRACK_PADDING, TRACK_PADDING);
            return true;
        }

        @Override
        public int getIntrinsicWidth() {
            return TOGGLE_WIDTH;
        }

        @Override
        public int getIntrinsicHeight() {
            return TOGGLE_HEIGHT;
        }
    }

    /**
     * Round knob. Checked: large with a check mark. Unchecked: small and gray.
     */
    private static final class ToggleThumbDrawable extends StatefulDrawable {
        private final Path check = new Path();

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            final float cx = bounds.exactCenterX();
            final float cy = bounds.exactCenterY();
            final float radius = checked ? KNOB_CHECKED_RADIUS : KNOB_UNCHECKED_RADIUS;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color(checked ? knobCheckedColor() : knobUncheckedColor()));
            canvas.drawCircle(cx, cy, radius, paint);

            if (checked) {
                // Points in dp of a 24dp knob.
                final float unit = Dim.dp(1);
                check.reset();
                check.moveTo(cx - 4.5f * unit, cy + 0.2f * unit);
                check.lineTo(cx - 1.4f * unit, cy + 3.2f * unit);
                check.lineTo(cx + 4.6f * unit, cy - 3.4f * unit);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(CHECK_STROKE_WIDTH);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(color(palette().checkMark));
                canvas.drawPath(check, paint);
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return THUMB_WIDTH;
        }

        @Override
        public int getIntrinsicHeight() {
            return TOGGLE_HEIGHT;
        }
    }

    // endregion

    /**
     * Styles the toolbar title like the title of the app screens.
     */
    public static void styleToolbarTitle(TextView title) {
        title.setTypeface(boldTypeface);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(getTitleColor());
    }
}
