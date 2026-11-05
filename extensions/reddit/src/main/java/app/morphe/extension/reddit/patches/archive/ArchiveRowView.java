package app.morphe.extension.reddit.patches.archive;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** One wrap-content lazy item. No inner scroller, viewport estimate, or touch interception. */
public final class ArchiveRowView extends LinearLayout {
    private ArchiveSection section;
    private ArchiveTheme theme;
    private ArchiveCardView card;
    private TextView status, retry;
    private ProgressBar progress;
    private int kind = -1;

    ArchiveRowView(Context context, ArchiveSection section) {
        super(context); setOrientation(VERTICAL);
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        bind(section);
    }
    void bind(ArchiveSection next) {
        section = next;
        ArchiveTheme current = new ArchiveTheme(getContext());
        boolean changed = theme == null || current.background != theme.background || current.foreground != theme.foreground
                || current.surface != theme.surface || current.secondary != theme.secondary || current.divider != theme.divider;
        if (kind != next.row.kind || changed) { theme = current; rebuild(next.row.kind); }
        setTag(next.row.key);
        if (card != null) card.bind(next.row.item, next.row.details);
        if (kind == ArchiveSnapshot.STATUS) {
            status.setText(next.row.message); status.setVisibility(next.row.message.isEmpty() ? GONE : VISIBLE);
            progress.setVisibility(next.row.loading ? VISIBLE : GONE); retry.setVisibility(next.row.retry ? VISIBLE : GONE);
            boolean empty = next.row.message.isEmpty() && !next.row.loading && !next.row.retry;
            setPadding(theme.dp(16), empty ? 0 : theme.dp(16), theme.dp(16), empty ? 0 : theme.dp(24));
        }
    }
    private void rebuild(int nextKind) {
        removeAllViews(); card = null; status = null; retry = null; progress = null;
        kind = nextKind; setPadding(0, 0, 0, 0); setBackgroundColor(theme.background);
        if (kind == ArchiveSnapshot.CARD) {
            card = new ArchiveCardView(getContext(), theme, this::open); addView(card, new LayoutParams(-1, -2));
        } else if (kind == ArchiveSnapshot.INDICATOR) {
            TextView indicator = theme.text(12, theme.secondary, theme.semibold);
            indicator.setText("Unhidden · Archived history"); indicator.setGravity(Gravity.CENTER_VERTICAL);
            indicator.setMinHeight(theme.dp(48)); indicator.setPadding(theme.dp(16), theme.dp(12), theme.dp(16), theme.dp(12));
            indicator.setBackground(theme.ripple(theme.surface, 0)); indicator.setCompoundDrawablePadding(theme.dp(8));
            indicator.setCompoundDrawablesRelative(theme.icon("icon_info", 16, theme.secondary), null, null, null);
            indicator.setContentDescription("Unhidden. Archived history. Tap for source and limitations");
            indicator.setOnClickListener(view -> new AlertDialog.Builder(getContext()).setTitle("Unhidden · Archived history")
                    .setMessage("Public history restored from Arctic Shift. Content and counts may be incomplete or outdated. "
                            + "Open the original discussion to vote, reply, share, or view full media.")
                    .setPositiveButton("Got it", null).show());
            addView(indicator, new LayoutParams(-1, -2));
        } else {
            setGravity(Gravity.CENTER_HORIZONTAL);
            progress = new ProgressBar(getContext()); progress.setIndeterminateTintList(ColorStateList.valueOf(theme.secondary));
            addView(progress, new LayoutParams(theme.dp(24), theme.dp(24)));
            status = theme.text(14, theme.secondary, theme.regular); status.setGravity(Gravity.CENTER);
            status.setPadding(0, theme.dp(12), 0, theme.dp(12)); status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
            addView(status, new LayoutParams(-1, -2));
            retry = theme.text(14, theme.foreground, theme.semibold); retry.setText("Retry"); retry.setGravity(Gravity.CENTER);
            retry.setMinHeight(theme.dp(48)); retry.setMinWidth(theme.dp(96)); retry.setPadding(theme.dp(20), 0, theme.dp(20), 0);
            retry.setBackground(theme.ripple(theme.surface, 24)); retry.setOnClickListener(view -> section.controller.retry());
            addView(retry, new LayoutParams(-2, -2));
        }
    }
    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); theme = null; if (section != null) bind(section);
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (card != null) { if (visibility == VISIBLE) card.resumeImage(); else card.cancelImage(); }
    }
    private void open(ArchiveItem item) {
        try { getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.discussionUrl())).setPackage(getContext().getPackageName())); }
        catch (ActivityNotFoundException failure) { Toast.makeText(getContext(), "Reddit could not open this discussion.", Toast.LENGTH_SHORT).show(); }
    }
}
