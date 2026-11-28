/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4803
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
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
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.TwoStatePreference;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toolbar;
import android.widget.WrapperListAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
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

    /**
     * Toolbar height if the theme has none.
     */
    private static final int DEFAULT_ACTION_BAR_SIZE_DP = 56;

    /**
     * Preference extras key of a child setting that is indented under its parent.
     */
    private static final String EXTRA_INDENTED = "morphe_indented";

    public static void setIndented(Preference preference) {
        preference.getExtras().putBoolean(EXTRA_INDENTED, true);
    }

    private static boolean isIndented(Object item) {
        if (!(item instanceof Preference preference)) return false;
        Bundle extras = preference.peekExtras();
        return extras != null && extras.getBoolean(EXTRA_INDENTED);
    }

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
     * The toggle outline is the unchecked track color mixed 50% with black (light themes)
     * or white (dark themes).
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
                0xFF737677, // trackOutline
                0xFF5C6C74, // knobUnchecked (slate)
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
                0xFFE5EBEE, // trackUnchecked
                0xFF737677, // trackOutline
                0xFF737577, // knobUnchecked (gray)
                0xFF00CCC0, // trackChecked (mint_primary)
                0xFFFFFFFF, // knobChecked
                0xFF1E1E1E  // checkMark
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
                0xFF95999B, // trackOutline
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
                0xFFFDF5ED, // trackUnchecked (pony_tone6)
                0xFF7F7B77, // trackOutline
                0xFFD5346A, // knobUnchecked (pink)
                0xFFFD4497, // trackChecked (pony_primary)
                0xFFFFFFFF, // knobChecked
                0xFF131427  // checkMark (dark navy)
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
                0xFFF6FCE8, // trackUnchecked (trees_tone6)
                0xFF7B7E74, // trackOutline
                0xFF3E8447, // knobUnchecked (trees_tone2)
                0xFF5ACE33, // trackChecked (trees_primary)
                0xFFFFFFFF, // knobChecked
                0xFF05301B  // checkMark (dark green)
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

        // Sync ThemeUtils so full screen pages (About) use the app background color,
        // and Morphe's CustomDialog (App Icon picker, Restart, Import/Export, etc.)
        // uses Reddit's elevated dialog surface color and custom text/button styling.
        ThemeUtils.setThemeLightColor(currentPalette.isDark ? Palette.LIGHT.background : currentPalette.background);
        ThemeUtils.setThemeDarkColor(currentPalette.background);
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

    // region App toggle colors

    /**
     * Colors of the last toggle the app drew, or null if the app did not draw a toggle yet.
     */
    @Nullable
    private static volatile AppToggleColors appToggleColors;

    private static final class AppToggleColors {
        /**
         * Palette that was active when the app drew the toggle.
         * The colors are only used while this palette is active.
         */
        final Palette palette;
        final long[] values;
        final int knobChecked, trackChecked, borderChecked;
        final int knobUnchecked, trackUnchecked, borderUnchecked;

        AppToggleColors(Palette palette, long[] values) {
            this.palette = palette;
            this.values = values;
            knobChecked = toArgb(values[0]);
            trackChecked = toArgb(values[1]);
            borderChecked = toArgb(values[2]);
            // values[3] is the content color of the check mark icon, but the icon uses its own tint.
            knobUnchecked = toArgb(values[4]);
            trackUnchecked = toArgb(values[5]);
            borderUnchecked = toArgb(values[6]);
        }
    }

    /**
     * Compose stores an sRGB color as the ARGB color in the upper 32 bits,
     * and the color space id (0 for sRGB) in the lowest 6 bits.
     * Other color spaces store the color differently, so they cannot be converted with {@link #toArgb(long)}.
     */
    private static boolean isNotSrgb(long composeColor) {
        return (composeColor & 0x3F) != 0;
    }

    private static int toArgb(long composeColor) {
        return (int) (composeColor >>> 32);
    }

    /**
     * Injection point.
     * Called each time the app creates the colors of a settings toggle, with the colors of an
     * enabled toggle: checked thumb, track, border and icon, then unchecked thumb, track, border and icon.
     */
    public static void setAppToggleColors(long checkedThumb, long checkedTrack, long checkedBorder, long checkedIcon,
                                          long uncheckedThumb, long uncheckedTrack, long uncheckedBorder, long uncheckedIcon) {
        try {
            final long[] values = {checkedThumb, checkedTrack, checkedBorder, checkedIcon,
                    uncheckedThumb, uncheckedTrack, uncheckedBorder, uncheckedIcon};
            AppToggleColors current = appToggleColors;
            if (current != null && Arrays.equals(current.values, values)) {
                return; // Same colors as the last toggle.
            }
            for (long value : values) {
                if (isNotSrgb(value)) return;
            }

            // Without an activity the palette cannot be detected,
            // and the colors could be used with the wrong palette.
            Activity activity = Utils.getActivity();
            if (activity == null) return;

            Palette palette = detectPalette(activity);
            appToggleColors = new AppToggleColors(palette, values);
            Logger.printDebug(() -> "App toggle colors of palette: " + palette.name());
        } catch (Exception ex) {
            Logger.printException(() -> "setAppToggleColors failure", ex);
        }
    }

    /**
     * @return The toggle colors of the app for the active palette, or null to use the built-in palette.
     */
    @Nullable
    private static AppToggleColors appToggleColors() {
        AppToggleColors colors = appToggleColors;
        return colors != null && colors.palette == palette() ? colors : null;
    }

    /**
     * Palette that was active when the app drew the check mark of a toggle, or null if it did not draw one yet.
     */
    @Nullable
    private static volatile Palette appCheckMarkPalette;
    private static volatile int appCheckMarkColor;

    /**
     * Injection point.
     * Called with the tint of the check mark icon of an enabled toggle.
     */
    public static void setAppCheckMarkColor(long color) {
        try {
            if (isNotSrgb(color)) return;
            final int argb = toArgb(color);
            if (appCheckMarkPalette != null && appCheckMarkColor == argb) {
                return; // Same color as the last check mark.
            }

            Activity activity = Utils.getActivity();
            if (activity == null) return;

            Palette palette = detectPalette(activity);
            // Clear the palette first, so the color is never used with the previous palette.
            appCheckMarkPalette = null;
            appCheckMarkColor = argb;
            appCheckMarkPalette = palette;
        } catch (Exception ex) {
            Logger.printException(() -> "setAppCheckMarkColor failure", ex);
        }
    }

    // endregion

    /**
     * Background of the settings.
     */
    public static int getBackgroundColor() {
        if (!themeColorsLoaded) return ThemeUtils.getAppBackgroundColor();
        return currentPalette.background;
    }

    /**
     * @return A sub-page that opens in its own dialog, see RedditPreferenceFragment for its toolbar.
     */
    public static PreferenceScreen createSubPage(PreferenceManager preferenceManager,
                                                   Context context,
                                                   String key,
                                                   String title,
                                                   @Nullable String summary) {
        // Sub-page dialog inherits its theme from the PreferenceScreen context rather than the main DialogFragment.
        Context screenContext = new ContextThemeWrapper(context, Utils.isDarkModeEnabled()
                ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        PreferenceScreen screen = preferenceManager.createPreferenceScreen(screenContext);
        screen.setKey(key);
        screen.setTitle(title);
        if (summary != null) {
            screen.setSummary(summary);
        }
        return screen;
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
                styleRow(view, adapter.getItem(position), isFirstVisibleRow(adapter, position));
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

    /**
     * @return If only untitled categories, which show nothing, are above the row.
     */
    private static boolean isFirstVisibleRow(ListAdapter adapter, int position) {
        for (int i = 0; i < position; i++) {
            if (!(adapter.getItem(i) instanceof PreferenceCategory category)
                    || category.getTitle() != null) {
                return false;
            }
        }
        return true;
    }

    private static void styleRow(View view, Object item, boolean isFirst) {
        TextView title = view.findViewById(android.R.id.title);
        if (title == null) return;

        if (item instanceof PreferenceCategory) {
            styleSectionHeader(view, title, isFirst);
            return;
        }

        // Views are recycled between indented and normal rows, so start padding runs on every bind.
        final int startPadding = isIndented(item)
                ? Dim.dp40
                : Dim.dp16;
        view.setPaddingRelative(startPadding, 0, Dim.dp16, 0);

        // Text is set again for every bind, the style only once per view.
        if (styledViews.add(view)) {
            view.setMinimumHeight(Dim.dp48);

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

        title.setAllCaps(true);
        title.setTypeface(boldTypeface);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setLetterSpacing(0);
        title.setTextColor(headerColor());

        if (!styledViews.add(view)) return;

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

    private static float dpToPx(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, Dim.getMetrics());
    }

    // Sizes are rounded to whole pixels and the border width is rounded up, like the app toggle (Compose).
    private static final int TOGGLE_WIDTH = Math.round(dpToPx(52));
    private static final int TOGGLE_HEIGHT = Math.round(dpToPx(32));
    /**
     * The knob center is half the track height from the track end, and moves 20dp.
     * The thumb bounds are narrower than the checked knob, which is drawn past them.
     */
    private static final int THUMB_WIDTH = Math.round(dpToPx(20));
    private static final int TRACK_PADDING = (TOGGLE_HEIGHT - THUMB_WIDTH) / 2;
    private static final float KNOB_CHECKED_RADIUS = Math.round(dpToPx(24)) / 2f;
    private static final float KNOB_UNCHECKED_RADIUS = Math.round(dpToPx(16)) / 2f;
    private static final float TRACK_OUTLINE_WIDTH = (float) Math.ceil(dpToPx(2));
    /**
     * The app check mark is the 20dp icon drawn at 12dp, with a stroke width of 1.8 / 20 of the icon.
     */
    private static final int CHECK_ICON_SIZE = Math.round(dpToPx(12));
    private static final float CHECK_STROKE_WIDTH = dpToPx(1.08f);

    // Toggle colors of the app if available, otherwise of the active palette.
    private static int trackCheckedColor() {
        AppToggleColors app = appToggleColors();
        return app != null ? app.trackChecked : palette().trackChecked;
    }

    private static int borderCheckedColor() {
        AppToggleColors app = appToggleColors();
        return app != null ? app.borderChecked : Color.TRANSPARENT;
    }

    private static int knobCheckedColor() {
        AppToggleColors app = appToggleColors();
        return app != null ? app.knobChecked : palette().knobChecked;
    }

    private static int checkMarkColor() {
        return appCheckMarkPalette == palette() ? appCheckMarkColor : palette().checkMark;
    }

    private static int trackUncheckedColor() {
        AppToggleColors app = appToggleColors();
        return app != null ? app.trackUnchecked : palette().trackUnchecked;
    }

    private static int trackOutlineColor() {
        AppToggleColors app = appToggleColors();
        return app != null ? app.borderUnchecked : palette().trackOutline;
    }

    private static int knobUncheckedColor() {
        AppToggleColors app = appToggleColors();
        return app != null ? app.knobUnchecked : palette().knobUnchecked;
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

            // Like the app, the border is drawn over the track,
            // so a translucent border is blended with the track color.
            paint.setColor(color(checked ? trackCheckedColor() : trackUncheckedColor()));
            canvas.drawRoundRect(rect, radius, radius, paint);

            final int border = checked ? borderCheckedColor() : trackOutlineColor();
            if (Color.alpha(border) != 0) {
                // Border inside the bounds.
                final float halfWidth = TRACK_OUTLINE_WIDTH / 2;
                rect.inset(halfWidth, halfWidth);
                radius = rect.height() / 2;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(TRACK_OUTLINE_WIDTH);
                paint.setColor(color(border));
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
        @Nullable
        private Drawable checkIcon;
        private boolean checkIconLoaded;

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            final float cx = bounds.exactCenterX();
            final float cy = bounds.exactCenterY();
            final float radius = checked ? KNOB_CHECKED_RADIUS : KNOB_UNCHECKED_RADIUS;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color(checked ? knobCheckedColor() : knobUncheckedColor()));
            canvas.drawCircle(cx, cy, radius, paint);

            if (!checked) return;

            final int checkColor = color(checkMarkColor());
            if (!checkIconLoaded) {
                checkIconLoaded = true;
                Drawable icon = ResourceUtils.getDrawable("icon_checkmark");
                checkIcon = icon != null ? icon.mutate() : null;
            }

            if (checkIcon != null) {
                // The app check mark icon, centered on the knob.
                final int left = Math.round(cx - CHECK_ICON_SIZE / 2f);
                final int top = Math.round(cy - CHECK_ICON_SIZE / 2f);
                checkIcon.setBounds(left, top, left + CHECK_ICON_SIZE, top + CHECK_ICON_SIZE);
                checkIcon.setTint(checkColor);
                checkIcon.draw(canvas);
                return;
            }

            // Fallback with the shape of the app icon, in dp from the knob center.
            final float unit = Dim.dp(1);
            check.reset();
            check.moveTo(cx - 4.84f * unit, cy - 0.13f * unit);
            check.lineTo(cx - 1.75f * unit, cy + 2.85f * unit);
            check.lineTo(cx + 4.88f * unit, cy - 3.66f * unit);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(CHECK_STROKE_WIDTH);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(checkColor);
            canvas.drawPath(check, paint);
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

    /**
     * @return The toolbar height of the theme of the context.
     */
    public static int getActionBarSize(Context context) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            return TypedValue.complexToDimensionPixelSize(tv.data, context.getResources().getDisplayMetrics());
        }
        return Dim.dp(DEFAULT_ACTION_BAR_SIZE_DP);
    }

    /**
     * @return The background color of the settings dialog and its sub-pages.
     */
    public static int getSettingsBackgroundColor() {
        return isModernStyle()
                ? getBackgroundColor()
                : ThemeUtils.getAppBackgroundColor();
    }

    /**
     * Makes a settings dialog window fill the screen, with system bars colored like the settings.
     */
    public static void styleWindow(Window window) {
        final int appBackgroundColor = getSettingsBackgroundColor();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setStatusBarColor(appBackgroundColor);
        window.setNavigationBarColor(appBackgroundColor);
        // Dark system bar icons only on a light background.
        window.getDecorView().setSystemUiVisibility(Utils.isDarkModeEnabled()
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    /**
     * Sets the shadow below a settings toolbar.
     */
    public static void applyToolbarElevation(View view) {
        final boolean modernStyle = isModernStyle();
        final boolean isDark = Utils.isDarkModeEnabled();
        view.setElevation(modernStyle ? (isDark ? Dim.dp1 : Dim.dp6) : Dim.dp2);
        if (modernStyle && !isDark) {
            view.setTranslationZ(Dim.dp2);
        }
    }

    /**
     * @return A settings toolbar with a title and a back button.
     */
    public static Toolbar createToolbar(Context context, CharSequence title, Runnable onBack) {
        final boolean modernStyle = isModernStyle();
        // Icons use the text color of the rows, the title is white or black like the app titles.
        final int appForegroundColor = modernStyle
                ? getTextColor()
                : ThemeUtils.getAppForegroundColor();
        final int titleColor = modernStyle
                ? getTitleColor()
                : appForegroundColor;
        final int appBackgroundColor = getSettingsBackgroundColor();

        Toolbar toolbar = new Toolbar(context);
        toolbar.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, getActionBarSize(context)));
        toolbar.setBackgroundColor(appBackgroundColor);
        toolbar.setTitle(title);
        toolbar.setTitleTextColor(titleColor);

        toolbar.post(() -> {
            TextView titleTextView = Utils.getChildView(toolbar, false,
                    view -> view instanceof TextView);
            if (titleTextView != null) {
                if (modernStyle) {
                    styleToolbarTitle(titleTextView);
                } else {
                    titleTextView.setTextSize(18);
                }
            }
        });

        Drawable backIcon = Objects.requireNonNull(ResourceUtils.getDrawable("icon_arrow_back")).mutate();
        backIcon.setTint(appForegroundColor);
        toolbar.setNavigationIcon(backIcon);
        toolbar.setNavigationContentDescription(str("morphe_settings_navigate_up"));
        toolbar.setNavigationOnClickListener(v -> onBack.run());
        return toolbar;
    }
}
