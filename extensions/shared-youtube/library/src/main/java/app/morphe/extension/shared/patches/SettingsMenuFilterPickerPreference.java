/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2029
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.DRAWABLE_CHECKMARK;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.DRAWABLE_CHECKMARK_BOLD;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.ID_MORPHE_CHECK_ICON;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.ID_MORPHE_CHECK_ICON_PLACEHOLDER;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.ID_MORPHE_ITEM_TEXT;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Selecting a category grays and locks its children because the filter hides descendants
 * recursively, keeping the picker state consistent with what actually gets hidden.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SettingsMenuFilterPickerPreference extends Preference {

    public SettingsMenuFilterPickerPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public SettingsMenuFilterPickerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public SettingsMenuFilterPickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SettingsMenuFilterPickerPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        showPickerDialog();
    }

    private void showPickerDialog() {
        Context context = getContext();
        List<Row> rows = buildRows(BaseSettingsMenuFilter.discoveredTree());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        Set<String> selectedLower = new HashSet<>(BaseSettingsMenuFilter.activeFilterEntries());
        HierarchyAdapter[] adapterRef = {null};

        if (rows.isEmpty()) {
            TextView hint = new TextView(context);
            hint.setText(str("morphe_settings_menu_filter_picker_empty"));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hint.setTextColor(Utils.getAppForegroundColor());
            hint.setPadding(0, 24, 0, 24);
            content.addView(hint, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            ListView listView = new ListView(context);
            listView.setId(android.R.id.list);

            HierarchyAdapter adapter = new HierarchyAdapter(context, rows, selectedLower);
            adapterRef[0] = adapter;
            listView.setAdapter(adapter);

            listView.setOnItemClickListener((parent, view, position, id) -> {
                Row row = adapter.getItem(position);
                if (row == null || isLockedByParent(row, selectedLower)) return;
                boolean nowSelected = BaseSettingsMenuFilter.toggleInFilter(row.title());
                String key = row.title().toLowerCase();
                if (nowSelected) selectedLower.add(key);
                else selectedLower.remove(key);
                adapter.notifyDataSetChanged();
            });

            LinearLayout.LayoutParams listViewParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1.0f);
            content.addView(listView, listViewParams);
        }

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                getTitle() != null ? getTitle().toString() : "",
                null,
                null,
                null,
                () -> {},
                null,
                str("morphe_settings_reset"),
                () -> {
                    BaseSettingsMenuFilter.clearFilter();
                    selectedLower.clear();
                    if (adapterRef[0] != null) adapterRef[0].notifyDataSetChanged();
                },
                false
        );

        LinearLayout mainLayout = dialogPair.second;
        mainLayout.addView(content, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1.0f));

        dialogPair.first.show();
    }

    private static List<Row> buildRows(List<BaseSettingsMenuFilter.DiscoveredNode> tree) {
        LinkedHashSet<String> rootOrder = new LinkedHashSet<>();
        LinkedHashMap<String, LinkedHashSet<String>> childrenByParent = new LinkedHashMap<>();

        for (BaseSettingsMenuFilter.DiscoveredNode node : tree) {
            if (node.parent() == null) {
                rootOrder.add(node.title());
            } else {
                childrenByParent
                        .computeIfAbsent(node.parent(), k -> new LinkedHashSet<>())
                        .add(node.title());
            }
        }

        List<Row> rows = new ArrayList<>();
        for (String root : rootOrder) {
            LinkedHashSet<String> children = childrenByParent.get(root);
            if (children != null && !children.isEmpty()) {
                rows.add(new Row(RowType.CATEGORY, root, null));
                for (String child : children) {
                    rows.add(new Row(RowType.CHILD, child, root));
                }
            } else {
                rows.add(new Row(RowType.LEAF, root, null));
            }
        }
        return rows;
    }

    /**
     * Locked children inherit selection from their parent category and cannot be toggled
     * independently while the category is selected.
     */
    private static boolean isLockedByParent(Row row, Set<String> selectedLower) {
        return row.type() == RowType.CHILD
                && row.parentTitle() != null
                && selectedLower.contains(row.parentTitle().toLowerCase());
    }

    private enum RowType { CATEGORY, LEAF, CHILD }

    private record Row(RowType type, String title, @Nullable String parentTitle) {}

    private static final class HierarchyAdapter extends BaseAdapter {
        private static final int CHILD_INDENT_DP = 24;
        private static final float DISABLED_ALPHA = 0.5f;

        private final Context context;
        private final List<Row> rows;
        private final Set<String> selectedLower;
        private final int checkDrawable;
        private final int childIndentPx;
        private final int foregroundColor;

        HierarchyAdapter(Context context, List<Row> rows, Set<String> selectedLower) {
            this.context = context;
            this.rows = rows;
            this.selectedLower = selectedLower;
            this.checkDrawable = Utils.appIsUsingBoldIcons() ? DRAWABLE_CHECKMARK_BOLD : DRAWABLE_CHECKMARK;
            this.childIndentPx = (int) (CHILD_INDENT_DP * context.getResources().getDisplayMetrics().density);
            this.foregroundColor = Utils.getAppForegroundColor();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public boolean isEnabled(int position) {
            return !isLockedByParent(rows.get(position), selectedLower);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = convertView;
            Holder holder;
            if (view == null) {
                view = LayoutInflater.from(context)
                        .inflate(LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED, parent, false);
                holder = new Holder();
                holder.placeholder = view.findViewById(ID_MORPHE_CHECK_ICON_PLACEHOLDER);
                holder.itemText = view.findViewById(ID_MORPHE_ITEM_TEXT);
                holder.checkIcon = view.findViewById(ID_MORPHE_CHECK_ICON);
                holder.checkIcon.setImageResource(checkDrawable);
                holder.defaultPaddingLeft = holder.itemText.getPaddingLeft();
                view.setTag(holder);
            } else {
                holder = (Holder) view.getTag();
            }

            Row row = rows.get(position);
            holder.itemText.setText(row.title());
            holder.itemText.setTextColor(foregroundColor);
            holder.itemText.setTypeface(null, row.type() == RowType.CATEGORY ? Typeface.BOLD : Typeface.NORMAL);
            holder.itemText.setPadding(
                    row.type() == RowType.CHILD ? holder.defaultPaddingLeft + childIndentPx : holder.defaultPaddingLeft,
                    holder.itemText.getPaddingTop(),
                    holder.itemText.getPaddingRight(),
                    holder.itemText.getPaddingBottom());

            boolean parentSelected = isLockedByParent(row, selectedLower);
            boolean visualChecked = parentSelected || selectedLower.contains(row.title().toLowerCase());

            holder.checkIcon.setVisibility(visualChecked ? View.VISIBLE : View.GONE);
            holder.checkIcon.setColorFilter(foregroundColor);
            holder.placeholder.setVisibility(visualChecked ? View.GONE : View.VISIBLE);

            float alpha = parentSelected ? DISABLED_ALPHA : 1.0f;
            holder.itemText.setAlpha(alpha);
            holder.checkIcon.setAlpha(alpha);

            return view;
        }

        private static final class Holder {
            ImageView checkIcon;
            View placeholder;
            TextView itemText;
            int defaultPaddingLeft;
        }
    }
}
