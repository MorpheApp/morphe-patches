/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.ResourceUtils.getIdentifierOrThrow;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Objects;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * A {@link CustomDialogListPreference} that shows a full adaptive-icon preview
 * (background + foreground, rounded) next to each list entry.
 * <p>
 * Icons are resolved by convention:
 * {@code morphe_adaptive_background_{value}} and {@code morphe_adaptive_foreground_{value}}
 * are layered and rendered to a rounded bitmap. Entries with no matching resources
 * fall back to the app's launcher icon.
 */
@SuppressWarnings({"unused", "deprecation"})
public class IconListPreference extends CustomDialogListPreference {

    public static final int LAYOUT_MORPHE_ICON_LIST_ITEM = getIdentifierOrThrow(
            ResourceType.LAYOUT, "morphe_icon_list_item");
    public static final int ID_MORPHE_ITEM_ICON = getIdentifierOrThrow(
            ResourceType.ID, "morphe_item_icon");

    static final float ICON_SIZE_DP = 48f;
    static final float ICON_CORNER_RADIUS_FRACTION = 0.22f;

    @Nullable
    private static String originalLauncherIconName;

    @Nullable
    private static String originalNotificationIconName;

    /** Called from CustomBrandingPatch.setBranding() at app startup. */
    public static void setOriginalLauncherIconName(@Nullable String name) {
        originalLauncherIconName = name;
    }

    /** Called from CustomBrandingPatch.setBranding() at app startup. */
    public static void setOriginalNotificationIconName(@Nullable String name) {
        originalNotificationIconName = name;
    }

    @Nullable
    static String getOriginalNotificationIconName() {
        return originalNotificationIconName;
    }

    /**
     * Resolves the original unpatched launcher icon drawable.
     * Uses the resource name injected at patch time; falls back to the current app icon.
     */
    static Drawable resolveOriginalIconDrawable(Context context) {
        try {
            if (originalLauncherIconName != null && !originalLauncherIconName.isEmpty()) {
                int resId = ResourceUtils.getIdentifier(ResourceType.MIPMAP, originalLauncherIconName);
                if (resId != 0) {
                    Drawable drawable = context.getDrawable(resId);
                    if (drawable != null) return drawable;
                }
            }
        } catch (Exception ignored) {}
        return context.getPackageManager().getApplicationIcon(context.getApplicationInfo());
    }

    @Nullable
    private Drawable[] iconDrawables;

    public IconListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public IconListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public IconListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public IconListPreference(Context context) {
        super(context);
    }

    /**
     * Overrides the automatically resolved drawables.
     * Must be the same length as {@link #getEntryValues()}.
     * Pass {@code null} to revert to convention-based resolution.
     */
    public void setIconDrawables(@Nullable Drawable[] drawables) {
        this.iconDrawables = drawables;
    }

    /**
     * Builds a rounded icon drawable for each entry by combining
     * {@code morphe_adaptive_background_{value}} and {@code morphe_adaptive_foreground_{value}}.
     * Falls back to the app's launcher icon for unrecognized entries (e.g. ORIGINAL).
     */
    @NonNull
    protected Drawable[] resolveIconDrawables() {
        CharSequence[] values = getEntryValues();
        if (values == null) return new Drawable[0];

        Context context = getContext();
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int sizePx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, ICON_SIZE_DP, dm));
        float cornerRadius = sizePx * ICON_CORNER_RADIUS_FRACTION;

        Drawable[] drawables = new Drawable[values.length];
        for (int i = 0; i < values.length; i++) {
            String suffix = values[i].toString().toLowerCase(Locale.US);
            drawables[i] = buildIconDrawable(context, suffix, sizePx, cornerRadius);
        }
        return drawables;
    }

    /**
     * Combines background + foreground for a given style suffix into a single
     * rounded-corner drawable rendered at {@code sizePx}.
     */
    @Nullable
    private static Drawable buildIconDrawable(
            Context context, String suffix, int sizePx, float cornerRadius) {
        try {
            int bgResId = resolveResId("morphe_adaptive_background_" + suffix);
            int fgResId = resolveResId("morphe_adaptive_foreground_" + suffix);

            Drawable bg = bgResId != 0 ? context.getDrawable(bgResId) : null;
            Drawable fg = fgResId != 0 ? context.getDrawable(fgResId) : null;

            // Morphe assets are adaptive icon layers (108dp canvas, 72dp safe zone).
            boolean isAdaptive = (bg != null || fg != null);
            Drawable source;
            if (bg != null && fg != null) {
                source = new LayerDrawable(new Drawable[]{bg, fg});
            } else {
                // No morphe assets (e.g. ORIGINAL) - use the original unpatched launcher icon.
                source = Objects.requireNonNullElseGet(fg,
                        () -> Objects.requireNonNullElseGet(bg,
                                () -> resolveOriginalIconDrawable(context)));
            }

            return renderToRounded(context.getResources(), source, sizePx, cornerRadius, isAdaptive);
        } catch (Exception e) {
            return null;
        }
    }

    static int resolveResId(String name) {
        int resId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, name);
        if (resId == 0) resId = ResourceUtils.getIdentifier(ResourceType.MIPMAP, name);
        return resId;
    }

    @NonNull
    static Drawable renderToRounded(
            Resources res, Drawable source, int sizePx, float cornerRadius, boolean isAdaptive) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Path clip = new Path();
        clip.addRoundRect(0, 0, sizePx, sizePx, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.clipPath(clip);
        if (isAdaptive) {
            // Adaptive icon layers use a 108dp canvas with a 72dp safe zone.
            // Scale so the safe-zone content fills the full bitmap.
            int scaledSize = Math.round(sizePx * (108f / 72f));
            int offset = (scaledSize - sizePx) / 2;
            source.setBounds(-offset, -offset, sizePx + offset, sizePx + offset);
        } else {
            source.setBounds(0, 0, sizePx, sizePx);
        }
        source.draw(canvas);
        return new BitmapDrawable(res, bmp);
    }

    @Override
    protected void showDialog(Bundle state) {
        if (iconDrawables == null) {
            iconDrawables = resolveIconDrawables();
        }

        // Fall back to parent if no drawables could be resolved.
        boolean hasAnyIcon = false;
        for (Drawable d : iconDrawables) {
            if (d != null) { hasAnyIcon = true; break; }
        }
        if (!hasAnyIcon) {
            super.showDialog(state);
            return;
        }

        Context context = getContext();
        CharSequence[] entriesToShow = getEntriesForDialog();
        CharSequence[] entryValues = getEntryValues();

        ListView listView = new ListView(context);
        listView.setId(android.R.id.list);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        IconListPreferenceAdapter adapter = new IconListPreferenceAdapter(
                context,
                LAYOUT_MORPHE_ICON_LIST_ITEM,
                entriesToShow,
                entryValues,
                getValue(),
                iconDrawables
        );
        listView.setAdapter(adapter);

        String currentValue = getValue();
        if (currentValue != null) {
            for (int i = 0, length = entryValues.length; i < length; i++) {
                if (currentValue.equals(entryValues[i].toString())) {
                    listView.setItemChecked(i, true);
                    listView.setSelection(i);
                    break;
                }
            }
        }

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                getTitle() != null ? getTitle().toString() : "",
                null, null, null, null,
                this::clearHighlightedEntriesForDialog,
                null, null,
                true
        );

        Dialog dialog = dialogPair.first;
        dialog.setOnDismissListener(d -> clearHighlightedEntriesForDialog());

        LinearLayout mainLayout = dialogPair.second;
        mainLayout.addView(listView, mainLayout.getChildCount() - 1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selected = entryValues[position].toString();
            if (callChangeListener(selected)) {
                setValue(selected);
                if (getStaticSummary() == null) {
                    CharSequence[] originalEntries = getEntries();
                    if (originalEntries != null && position < originalEntries.length) {
                        setSummary(originalEntries[position]);
                    }
                }
                adapter.setSelectedValue(selected);
                adapter.notifyDataSetChanged();
            }
            clearHighlightedEntriesForDialog();
            dialog.dismiss();
        });

        dialog.show();
    }

    /**
     * Adapter that renders each list row with a checkmark, an adaptive icon preview,
     * and a text label.
     */
    public static class IconListPreferenceAdapter extends ArrayAdapter<CharSequence> {

        private static class ViewHolder {
            ImageView checkIcon;
            View placeholder;
            ImageView itemIcon;
            TextView itemText;
        }

        private final int layoutResourceId;
        private final CharSequence[] entryValues;
        private final Drawable[] iconDrawables;
        private String selectedValue;

        public IconListPreferenceAdapter(
                Context context,
                int resource,
                CharSequence[] entries,
                CharSequence[] entryValues,
                String selectedValue,
                Drawable[] iconDrawables) {
            super(context, resource, entries);
            this.layoutResourceId = resource;
            this.entryValues = entryValues;
            this.selectedValue = selectedValue;
            this.iconDrawables = iconDrawables;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = convertView;
            ViewHolder holder;

            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(layoutResourceId, parent, false);
                holder = new ViewHolder();
                holder.placeholder = view.findViewById(CustomDialogListPreference.ID_MORPHE_CHECK_ICON_PLACEHOLDER);
                holder.itemText = view.findViewById(CustomDialogListPreference.ID_MORPHE_ITEM_TEXT);
                holder.checkIcon = view.findViewById(CustomDialogListPreference.ID_MORPHE_CHECK_ICON);
                holder.checkIcon.setImageResource(Utils.appIsUsingBoldIcons()
                        ? CustomDialogListPreference.DRAWABLE_CHECKMARK_BOLD
                        : CustomDialogListPreference.DRAWABLE_CHECKMARK);
                holder.itemIcon = view.findViewById(ID_MORPHE_ITEM_ICON);
                view.setTag(holder);
            } else {
                holder = (ViewHolder) view.getTag();
            }

            holder.itemText.setText(getItem(position));
            holder.itemText.setTextColor(Utils.getAppForegroundColor());

            boolean isSelected = entryValues[position].toString().equals(selectedValue);
            holder.checkIcon.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            holder.checkIcon.setColorFilter(Utils.getAppForegroundColor());
            holder.placeholder.setVisibility(isSelected ? View.GONE : View.VISIBLE);

            if (holder.itemIcon != null) {
                Drawable icon = (iconDrawables != null && position < iconDrawables.length)
                        ? iconDrawables[position] : null;
                if (icon != null) {
                    holder.itemIcon.setImageDrawable(icon);
                    holder.itemIcon.setVisibility(View.VISIBLE);
                } else {
                    holder.itemIcon.setVisibility(View.INVISIBLE);
                }
            }

            return view;
        }

        public void setSelectedValue(String value) {
            this.selectedValue = value;
        }
    }
}
