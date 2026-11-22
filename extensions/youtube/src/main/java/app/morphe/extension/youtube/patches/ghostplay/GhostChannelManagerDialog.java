/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.VideoInformation;

/**
 * Interactive manager for Auto-Ghost channels:
 * - Search YouTube channels by name, @handle, or URL and add with one tap
 * - Compact expandable list of saved channels (shows avatar, handle, description, and recent videos on tap)
 * - Live search filter over saved channels and one-tap deletion
 */
public final class GhostChannelManagerDialog {

    private static final int TAB_SAVED = 0;
    private static final int TAB_SEARCH = 1;

    private GhostChannelManagerDialog() {
    }

    public static void show(@NonNull Context rawContext, @Nullable Runnable onDismissCallback) {
        try {
            Context context = GhostHistoryDialog.resolveActivityContext(rawContext);
            if (context == null) return;

            Pair<Dialog, LinearLayout> created = CustomDialog.create(
                    context,
                    str("morphe_ghost_play_channels_manager_title"),
                    null,
                    null,
                    str("morphe_ghost_play_close"),
                    () -> {},
                    null,
                    null,
                    null,
                    true
            );
            Dialog dialog = created.first;
            LinearLayout mainLayout = created.second;
            if (onDismissCallback != null) {
                dialog.setOnDismissListener(d -> onDismissCallback.run());
            }

            Controller controller = new Controller(context, dialog);
            int insertIdx = Math.max(0, mainLayout.getChildCount() - 1);
            mainLayout.addView(controller.root, insertIdx);
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "GhostChannelManagerDialog.show failure", ex);
        }
    }

    private static final class Controller {
        final Context context;
        final Dialog dialog;
        final LinearLayout root;

        final TextView tabSavedBtn;
        final TextView tabSearchBtn;
        final LinearLayout savedPanel;
        final LinearLayout searchPanel;

        // Saved tab views.
        final EditText savedFilterInput;
        final LinearLayout savedListContainer;
        final TextView savedEmptyText;
        final Set<String> expandedKeys = new HashSet<>();
        final Set<String> loadingKeys = new HashSet<>();
        final Set<String> fetchedKeys = new HashSet<>();

        // Search tab views.
        final EditText searchQueryInput;
        final TextView searchActionBtn;
        final TextView searchStatusText;
        final LinearLayout searchResultsContainer;

        int activeTab;
        List<GhostChannelStore.ChannelEntry> lastSearchResults = Collections.emptyList();

        Controller(Context context, Dialog dialog) {
            this.context = context;
            this.dialog = dialog;

            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Tab bar.
            LinearLayout tabs = new LinearLayout(context);
            tabs.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tabsLp.bottomMargin = Dim.dp10;
            tabs.setLayoutParams(tabsLp);

            tabSavedBtn = buildTabButton(context);
            tabSearchBtn = buildTabButton(context);
            ((LinearLayout.LayoutParams) tabSavedBtn.getLayoutParams()).rightMargin = Dim.dp6;
            tabSavedBtn.setOnClickListener(v -> switchTab(TAB_SAVED));
            tabSearchBtn.setOnClickListener(v -> switchTab(TAB_SEARCH));
            tabs.addView(tabSavedBtn);
            tabs.addView(tabSearchBtn);
            root.addView(tabs);

            // Saved panel.
            savedPanel = new LinearLayout(context);
            savedPanel.setOrientation(LinearLayout.VERTICAL);

            savedFilterInput = CustomDialog.createEditText(context);
            savedFilterInput.setHint(str("morphe_ghost_play_channels_filter_hint"));
            savedFilterInput.setSingleLine(true);
            savedFilterInput.setTextSize(14f);
            LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            filterLp.bottomMargin = Dim.dp8;
            savedFilterInput.setLayoutParams(filterLp);
            savedFilterInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) { renderSavedList(); }
            });
            savedPanel.addView(savedFilterInput);

            ScrollView savedScroll = new ScrollView(context);
            savedScroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp(320)));
            savedListContainer = new LinearLayout(context);
            savedListContainer.setOrientation(LinearLayout.VERTICAL);
            savedScroll.addView(savedListContainer);
            savedPanel.addView(savedScroll);

            savedEmptyText = new TextView(context);
            savedEmptyText.setText(str("morphe_ghost_play_channels_empty"));
            savedEmptyText.setTextColor(GhostTheme.mutedText());
            savedEmptyText.setTextSize(13f);
            savedEmptyText.setGravity(Gravity.CENTER);
            savedEmptyText.setPadding(Dim.dp12, Dim.dp24, Dim.dp12, Dim.dp24);

            // Search panel.
            searchPanel = new LinearLayout(context);
            searchPanel.setOrientation(LinearLayout.VERTICAL);

            LinearLayout searchRow = new LinearLayout(context);
            searchRow.setOrientation(LinearLayout.HORIZONTAL);
            searchRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            srLp.bottomMargin = Dim.dp8;
            searchRow.setLayoutParams(srLp);

            searchQueryInput = CustomDialog.createEditText(context);
            searchQueryInput.setHint(str("morphe_ghost_play_channels_search_hint"));
            searchQueryInput.setSingleLine(true);
            searchQueryInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            searchQueryInput.setTextSize(14f);
            LinearLayout.LayoutParams sqLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            sqLp.rightMargin = Dim.dp6;
            searchQueryInput.setLayoutParams(sqLp);
            searchQueryInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    runChannelSearch();
                    return true;
                }
                return false;
            });

            searchActionBtn = new TextView(context);
            searchActionBtn.setText(str("morphe_ghost_play_channels_search_button"));
            searchActionBtn.setTextColor(GhostTheme.onAccent());
            searchActionBtn.setTextSize(13f);
            searchActionBtn.setTypeface(null, Typeface.BOLD);
            searchActionBtn.setPadding(Dim.dp12, Dim.dp10, Dim.dp12, Dim.dp10);
            searchActionBtn.setBackground(GhostTheme.morpheGradientRect(Dim.dp10));
            searchActionBtn.setOnClickListener(v -> runChannelSearch());

            searchRow.addView(searchQueryInput);
            searchRow.addView(searchActionBtn);
            searchPanel.addView(searchRow);

            searchStatusText = new TextView(context);
            searchStatusText.setText(str("morphe_ghost_play_channels_search_guide"));
            searchStatusText.setTextColor(GhostTheme.mutedText());
            searchStatusText.setTextSize(12f);
            searchStatusText.setPadding(Dim.dp4, 0, Dim.dp4, Dim.dp8);
            searchPanel.addView(searchStatusText);

            ScrollView searchScroll = new ScrollView(context);
            searchScroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp(290)));
            searchResultsContainer = new LinearLayout(context);
            searchResultsContainer.setOrientation(LinearLayout.VERTICAL);
            searchScroll.addView(searchResultsContainer);
            searchPanel.addView(searchScroll);

            root.addView(savedPanel);
            root.addView(searchPanel);

            switchTab(GhostChannelStore.count() == 0 ? TAB_SEARCH : TAB_SAVED);
        }

        private void switchTab(int tab) {
            activeTab = tab;
            updateTabLabels();
            savedPanel.setVisibility(tab == TAB_SAVED ? View.VISIBLE : View.GONE);
            searchPanel.setVisibility(tab == TAB_SEARCH ? View.VISIBLE : View.GONE);
            if (tab == TAB_SAVED) {
                renderSavedList();
            } else {
                renderSearchResults();
            }
        }

        private void updateTabLabels() {
            int count = GhostChannelStore.count();
            tabSavedBtn.setText(str("morphe_ghost_play_channels_tab_saved", count));
            tabSearchBtn.setText(str("morphe_ghost_play_channels_tab_search"));

            styleTab(tabSavedBtn, activeTab == TAB_SAVED);
            styleTab(tabSearchBtn, activeTab == TAB_SEARCH);
        }

        private void triggerDetailFetch(@NonNull String key, @NonNull GhostChannelStore.ChannelEntry entry) {
            if (loadingKeys.contains(key)) {
                return;
            }
            loadingKeys.add(key);
            GhostChannelSearchApi.fetchDetailsAsync(entry, updated -> {
                loadingKeys.remove(key);
                fetchedKeys.add(key);
                if (updated != null && GhostChannelStore.contains(updated.channelId(), updated.name())) {
                    GhostChannelStore.addOrUpdate(updated);
                }
                if (dialog.isShowing() && activeTab == TAB_SAVED) {
                    renderSavedList();
                }
            });
        }

        private void renderSavedList() {
            savedListContainer.removeAllViews();
            updateTabLabels();

            // Quick-add banner for the currently playing channel if not yet saved.
            String playingChannelName = VideoInformation.getChannelName();
            String playingChannelId = VideoInformation.getChannelId();
            if (Utils.isNotEmpty(playingChannelName) && !GhostChannelStore.contains(playingChannelId, playingChannelName)) {
                savedListContainer.addView(buildCurrentChannelBanner(playingChannelId, playingChannelName));
            }

            List<GhostChannelStore.ChannelEntry> all = GhostChannelStore.getAll();
            String query = savedFilterInput.getText() == null
                    ? "" : savedFilterInput.getText().toString().trim().toLowerCase(Locale.ROOT);

            int shown = 0;
            for (GhostChannelStore.ChannelEntry entry : all) {
                if (!entry.matchesFilter(query)) continue;
                savedListContainer.addView(buildSavedRow(entry));
                shown++;
            }

            if (shown == 0) {
                savedEmptyText.setText(all.isEmpty()
                        ? str("morphe_ghost_play_channels_empty")
                        : str("morphe_ghost_play_channels_filter_no_match"));
                savedListContainer.addView(savedEmptyText);
            }
        }

        private View buildCurrentChannelBanner(@Nullable String channelId, @NonNull String channelName) {
            LinearLayout banner = new LinearLayout(context);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding(Dim.dp10, Dim.dp8, Dim.dp10, Dim.dp8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Dim.dp8;
            banner.setLayoutParams(lp);
            banner.setBackground(GhostTheme.outlinedRect(
                    Dim.dp10, GhostTheme.accentSoft(), Dim.dp(1), GhostTheme.accentBorder()));

            TextView label = new TextView(context);
            label.setText(str("morphe_ghost_play_channels_add_current", channelName));
            label.setTextColor(ThemeUtils.getAppForegroundColor());
            label.setTextSize(12.5f);
            label.setTypeface(null, Typeface.BOLD);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);

            TextView addBtn = new TextView(context);
            addBtn.setText(str("morphe_ghost_play_channels_add_button"));
            addBtn.setTextColor(GhostTheme.onAccent());
            addBtn.setTextSize(12f);
            addBtn.setTypeface(null, Typeface.BOLD);
            addBtn.setPadding(Dim.dp10, Dim.dp6, Dim.dp10, Dim.dp6);
            addBtn.setBackground(GhostTheme.morpheGradientRect(Dim.dp8));
            addBtn.setOnClickListener(v -> {
                GhostChannelStore.ChannelEntry base = new GhostChannelStore.ChannelEntry(
                        channelId != null ? channelId : "",
                        channelName,
                        "", "", "", "", Collections.emptyList(), System.currentTimeMillis()
                );
                GhostChannelStore.addOrUpdate(base);
                GhostPlayPatch.markCurrentVideoIfMatchesChannel();
                Utils.showSnackbarShort(str("morphe_ghost_play_channel_added_toast", channelName));
                String key = !base.channelId().isEmpty() ? base.channelId() : base.name();
                triggerDetailFetch(key, base);
                renderSavedList();
            });

            banner.addView(label);
            banner.addView(addBtn);
            return banner;
        }

        private View buildSavedRow(@NonNull GhostChannelStore.ChannelEntry entry) {
            String key = !entry.channelId().isEmpty() ? entry.channelId() : entry.name();
            boolean expanded = expandedKeys.contains(key);

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Dim.dp10, Dim.dp8, Dim.dp10, Dim.dp8);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = Dim.dp6;
            card.setLayoutParams(cardLp);
            card.setBackground(GhostTheme.roundedRect(Dim.dp10, GhostTheme.cardSurface()));

            // Compact top row: Avatar + Name/Handle + Expand indicator + Delete button.
            LinearLayout top = new LinearLayout(context);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            ImageView avatar = buildAvatarView(context, entry);
            top.addView(avatar);

            LinearLayout info = new LinearLayout(context);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            infoLp.leftMargin = Dim.dp10;
            infoLp.rightMargin = Dim.dp6;
            info.setLayoutParams(infoLp);

            TextView nameView = new TextView(context);
            nameView.setText(entry.name().isEmpty() ? entry.channelId() : entry.name());
            nameView.setTextColor(ThemeUtils.getAppForegroundColor());
            nameView.setTextSize(13.5f);
            nameView.setTypeface(null, Typeface.BOLD);
            nameView.setMaxLines(1);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(nameView);

            String subLine = buildSubLine(entry);
            if (!subLine.isEmpty()) {
                TextView subView = new TextView(context);
                subView.setText(subLine);
                subView.setTextColor(GhostTheme.mutedText());
                subView.setTextSize(11.5f);
                subView.setMaxLines(1);
                subView.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(subView);
            }
            top.addView(info);

            TextView chevron = new TextView(context);
            chevron.setText(expanded ? "▲" : "▼");
            chevron.setTextColor(GhostTheme.adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.5f));
            chevron.setTextSize(11f);
            chevron.setPadding(Dim.dp6, Dim.dp4, Dim.dp6, Dim.dp4);
            top.addView(chevron);

            TextView delBtn = new TextView(context);
            delBtn.setText(str("morphe_ghost_play_channels_delete_button"));
            delBtn.setTextColor(GhostTheme.danger());
            delBtn.setTextSize(11.5f);
            delBtn.setTypeface(null, Typeface.BOLD);
            delBtn.setPadding(Dim.dp8, Dim.dp6, Dim.dp8, Dim.dp6);
            delBtn.setBackground(GhostTheme.roundedRect(Dim.dp8, GhostTheme.dangerSoft()));
            delBtn.setOnClickListener(v -> {
                GhostChannelStore.remove(entry.channelId(), entry.name());
                expandedKeys.remove(key);
                loadingKeys.remove(key);
                fetchedKeys.remove(key);
                Utils.showSnackbarShort(str("morphe_ghost_play_channel_removed_toast",
                        entry.name().isEmpty() ? entry.channelId() : entry.name()));
                renderSavedList();
            });
            top.addView(delBtn);

            card.addView(top);

            // Expandable detail drawer: description + recent video titles.
            if (expanded) {
                LinearLayout detail = new LinearLayout(context);
                detail.setOrientation(LinearLayout.VERTICAL);
                detail.setPadding(Dim.dp4, Dim.dp8, Dim.dp4, Dim.dp2);

                if (!entry.description().isEmpty()) {
                    TextView descView = new TextView(context);
                    descView.setText(entry.description());
                    descView.setTextColor(GhostTheme.adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.78f));
                    descView.setTextSize(11.5f);
                    descView.setMaxLines(3);
                    descView.setEllipsize(TextUtils.TruncateAt.END);
                    detail.addView(descView);
                }

                if (!entry.recentVideos().isEmpty()) {
                    TextView hdr = new TextView(context);
                    hdr.setText(str("morphe_ghost_play_channels_recent_videos"));
                    hdr.setTextColor(GhostTheme.mutedText());
                    hdr.setTextSize(11f);
                    hdr.setTypeface(null, Typeface.BOLD);
                    hdr.setPadding(0, Dim.dp6, 0, Dim.dp2);
                    detail.addView(hdr);

                    for (String videoTitle : entry.recentVideos()) {
                        TextView vt = new TextView(context);
                        vt.setText("• " + videoTitle);
                        vt.setTextColor(GhostTheme.adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.88f));
                        vt.setTextSize(11.5f);
                        vt.setMaxLines(1);
                        vt.setEllipsize(TextUtils.TruncateAt.END);
                        vt.setPadding(0, Dim.dp2, 0, 0);
                        detail.addView(vt);
                    }
                } else if (loadingKeys.contains(key)) {
                    TextView loading = new TextView(context);
                    loading.setText(str("morphe_ghost_play_channels_loading_details"));
                    loading.setTextColor(GhostTheme.mutedText());
                    loading.setTextSize(11.5f);
                    loading.setPadding(0, Dim.dp4, 0, 0);
                    detail.addView(loading);
                } else {
                    TextView emptyRecent = new TextView(context);
                    emptyRecent.setText(str("morphe_ghost_play_channels_no_recent_videos"));
                    emptyRecent.setTextColor(GhostTheme.mutedText());
                    emptyRecent.setTextSize(11.5f);
                    emptyRecent.setPadding(0, Dim.dp4, 0, 0);
                    emptyRecent.setOnClickListener(v -> {
                        fetchedKeys.remove(key);
                        triggerDetailFetch(key, entry);
                        renderSavedList();
                    });
                    detail.addView(emptyRecent);
                }

                card.addView(detail);
            }

            top.setOnClickListener(v -> {
                if (expandedKeys.contains(key)) {
                    expandedKeys.remove(key);
                } else {
                    expandedKeys.add(key);
                    if ((entry.recentVideos().isEmpty() || entry.avatarUrl().isEmpty()) && !fetchedKeys.contains(key)) {
                        triggerDetailFetch(key, entry);
                    }
                }
                renderSavedList();
            });

            return card;
        }

        private void runChannelSearch() {
            String query = searchQueryInput.getText() == null ? "" : searchQueryInput.getText().toString().trim();
            if (query.isEmpty()) {
                return;
            }
            searchStatusText.setText(str("morphe_ghost_play_channels_searching"));
            searchResultsContainer.removeAllViews();

            GhostChannelSearchApi.searchAsync(query, (results, error) -> {
                if (!dialog.isShowing()) return;
                lastSearchResults = results;
                if (error) {
                    searchStatusText.setText(str("morphe_ghost_play_channels_search_error"));
                } else if (results.isEmpty()) {
                    searchStatusText.setText(str("morphe_ghost_play_channels_search_no_results"));
                } else {
                    searchStatusText.setText(str("morphe_ghost_play_channels_search_count", results.size()));
                }
                renderSearchResults();
            });
        }

        private void renderSearchResults() {
            searchResultsContainer.removeAllViews();
            for (GhostChannelStore.ChannelEntry entry : lastSearchResults) {
                searchResultsContainer.addView(buildSearchResultRow(entry));
            }
        }

        private View buildSearchResultRow(@NonNull GhostChannelStore.ChannelEntry entry) {
            boolean alreadyAdded = GhostChannelStore.contains(entry.channelId(), entry.name());

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Dim.dp10, Dim.dp8, Dim.dp10, Dim.dp8);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = Dim.dp6;
            card.setLayoutParams(cardLp);
            card.setBackground(GhostTheme.roundedRect(Dim.dp10, GhostTheme.cardSurface()));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            row.addView(buildAvatarView(context, entry));

            LinearLayout info = new LinearLayout(context);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            infoLp.leftMargin = Dim.dp10;
            infoLp.rightMargin = Dim.dp8;
            info.setLayoutParams(infoLp);

            TextView nameView = new TextView(context);
            nameView.setText(entry.name());
            nameView.setTextColor(ThemeUtils.getAppForegroundColor());
            nameView.setTextSize(13.5f);
            nameView.setTypeface(null, Typeface.BOLD);
            nameView.setMaxLines(1);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(nameView);

            String sub = buildSubLine(entry);
            if (!sub.isEmpty()) {
                TextView subView = new TextView(context);
                subView.setText(sub);
                subView.setTextColor(GhostTheme.mutedText());
                subView.setTextSize(11.5f);
                subView.setMaxLines(1);
                subView.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(subView);
            }

            if (!entry.description().isEmpty()) {
                TextView descView = new TextView(context);
                descView.setText(entry.description());
                descView.setTextColor(GhostTheme.adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.72f));
                descView.setTextSize(11f);
                descView.setMaxLines(2);
                descView.setEllipsize(TextUtils.TruncateAt.END);
                descView.setPadding(0, Dim.dp2, 0, 0);
                info.addView(descView);
            }

            row.addView(info);

            TextView actionBtn = new TextView(context);
            actionBtn.setText(str(alreadyAdded
                    ? "morphe_ghost_play_channels_added_badge"
                    : "morphe_ghost_play_channels_add_button"));
            actionBtn.setTextColor(alreadyAdded ? GhostTheme.mutedText() : GhostTheme.onAccent());
            actionBtn.setTextSize(12f);
            actionBtn.setTypeface(null, Typeface.BOLD);
            actionBtn.setPadding(Dim.dp10, Dim.dp6, Dim.dp10, Dim.dp6);
            actionBtn.setBackground(alreadyAdded
                    ? GhostTheme.roundedRect(Dim.dp8, GhostTheme.subtleSurface())
                    : GhostTheme.morpheGradientRect(Dim.dp8));

            actionBtn.setOnClickListener(v -> {
                if (GhostChannelStore.contains(entry.channelId(), entry.name())) {
                    GhostChannelStore.remove(entry.channelId(), entry.name());
                    Utils.showSnackbarShort(str("morphe_ghost_play_channel_removed_toast", entry.name()));
                } else {
                    GhostChannelStore.addOrUpdate(entry);
                    GhostPlayPatch.markCurrentVideoIfMatchesChannel();
                    Utils.showSnackbarShort(str("morphe_ghost_play_channel_added_toast", entry.name()));
                    if (entry.recentVideos().isEmpty()) {
                        String key = !entry.channelId().isEmpty() ? entry.channelId() : entry.name();
                        triggerDetailFetch(key, entry);
                    }
                }
                updateTabLabels();
                renderSearchResults();
            });

            row.addView(actionBtn);
            card.addView(row);

            if (!entry.recentVideos().isEmpty()) {
                TextView rv = new TextView(context);
                rv.setText(String.join(" / ", entry.recentVideos()));
                rv.setTextColor(GhostTheme.mutedText());
                rv.setTextSize(11f);
                rv.setMaxLines(2);
                rv.setEllipsize(TextUtils.TruncateAt.END);
                rv.setPadding(Dim.dp4, Dim.dp6, Dim.dp4, 0);
                card.addView(rv);
            }

            return card;
        }
    }

    private static ImageView buildAvatarView(Context context, GhostChannelStore.ChannelEntry entry) {
        int size = Dim.dp(38);
        ImageView iv = new ImageView(context);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ShapeDrawable placeholder = new ShapeDrawable(new OvalShape());
        placeholder.getPaint().setColor(GhostTheme.accentSoft());
        iv.setBackground(placeholder);
        if (!entry.avatarUrl().isEmpty()) {
            GhostChannelSearchApi.loadAvatar(iv, entry.avatarUrl());
        }
        return iv;
    }

    private static String buildSubLine(GhostChannelStore.ChannelEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (!entry.handle().isEmpty()) sb.append(entry.handle());
        if (!entry.subscriberText().isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(entry.subscriberText());
        }
        return sb.toString();
    }

    private static TextView buildTabButton(Context context) {
        TextView tv = new TextView(context);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);
        tv.setTextSize(12.5f);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private static void styleTab(TextView tv, boolean active) {
        if (active) {
            tv.setBackground(GhostTheme.morpheGradientRect(Dim.dp8));
            tv.setTextColor(GhostTheme.onAccent());
        } else {
            tv.setBackground(GhostTheme.roundedRect(Dim.dp8, GhostTheme.subtleSurface()));
            tv.setTextColor(GhostTheme.adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.78f));
        }
    }
}
