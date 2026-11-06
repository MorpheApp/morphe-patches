package app.morphe.extension.reddit.patches.archive;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Recyclable profile post/comment row, using Reddit's own fonts, theme and icon drawables. */
final class ArchiveCardView extends LinearLayout {
    interface Navigation {
        void open(ArchiveItem item);
        default void original(ArchiveItem item) { open(item); }
        default Object events() { return null; }
    }
    final ArchiveTheme theme;
    private final TextView community, title, body, flair, context, score, replies, share, repost, join;
    private final ImageView upvote, downvote, communityIcon, overflow;
    private ArchiveActions.Entry voteEntry;
    private final ArchiveActions.Observer voteObserver = this::renderVote;
    private final FrameLayout media;
    private final ImageView image;
    private final TextView placeholder, badge;
    private final Navigation navigation;
    private ArchiveItem item;
    private ArchivePostDetails details;
    private ArchiveImageLoader.Request imageRequest;
    private String imageUrl;
    private int imageGeneration;
    private float aspect = 16f / 9f;
    private String replyLabel = "";
    private ArchiveImageLoader.Request communityRequest;
    private String communityUrl;
    private int communityGeneration;

    ArchiveCardView(Context context, ArchiveTheme theme, Navigation navigation) {
        super(context); this.theme = theme; this.navigation = navigation;
        setOrientation(VERTICAL); setPadding(theme.dp(16), theme.dp(12), theme.dp(16), 0);
        setBackground(theme.ripple(theme.background, 0));
        setFocusable(true); setOnClickListener(view -> open());
        LinearLayout metadata = new LinearLayout(context); metadata.setGravity(Gravity.CENTER_VERTICAL);
        communityIcon = new ImageView(context);
        communityIcon.setImageDrawable(theme.icon("community_default", 24, Color.WHITE));
        communityIcon.setBackground(theme.shape(0xffff4500, 12));
        communityIcon.setScaleType(ImageView.ScaleType.CENTER_CROP); communityIcon.setClipToOutline(true);
        communityIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        metadata.addView(communityIcon, new LayoutParams(theme.dp(24), theme.dp(24)));
        community = theme.text(12, theme.foreground, theme.bold); community.setLineHeight(theme.sp(16));
        community.setSingleLine(); community.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams communityParams = new LayoutParams(0, -2, 1); communityParams.setMarginStart(theme.dp(8));
        metadata.addView(community, communityParams);
        join = theme.text(12, theme.background, theme.bold); join.setText("Join"); join.setGravity(Gravity.CENTER);
        join.setBackground(theme.ripple(theme.foreground, 12)); join.setPadding(theme.dp(10), 0, theme.dp(10), 0);
        join.setOnClickListener(view -> ArchiveActions.join(getContext(), voteEntry, navigation.events(), item));
        LayoutParams joinParams = new LayoutParams(-2, theme.dp(24)); joinParams.setMarginStart(theme.dp(8)); metadata.addView(join, joinParams);
        overflow = new ImageView(context);
        overflow.setImageDrawable(theme.icon("icon_overflow_vertical", 16, theme.secondary));
        overflow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        overflow.setBackground(theme.ripple(Color.TRANSPARENT, 12));
        overflow.setPadding(theme.dp(4), theme.dp(4), theme.dp(4), theme.dp(4));
        overflow.setOnClickListener(view -> original()); overflow.setContentDescription("Open original discussion for more actions");
        LayoutParams overflowParams = new LayoutParams(theme.dp(24), theme.dp(24)); overflowParams.setMarginStart(theme.dp(8));
        metadata.addView(overflow, overflowParams);
        LayoutParams metadataParams = new LayoutParams(-1, -2); metadataParams.bottomMargin = theme.dp(8);
        addView(metadata, metadataParams);
        this.context = theme.text(12, theme.secondary, theme.regular);
        this.context.setPadding(0, 0, 0, theme.dp(6)); addView(this.context);
        title = theme.text(16, theme.foreground, theme.bold);
        title.setLineHeight(theme.sp(20)); title.setPadding(0, 0, 0, theme.dp(8));
        title.setMaxLines(4); title.setEllipsize(TextUtils.TruncateAt.END); addView(title, new LayoutParams(-1, -2));
        flair = theme.text(12, theme.foreground, theme.semibold);
        flair.setPadding(theme.dp(8), theme.dp(3), theme.dp(8), theme.dp(3));
        flair.setMaxLines(2); flair.setEllipsize(TextUtils.TruncateAt.END);
        flair.setBackground(theme.shape(theme.surface, 12));
        LayoutParams flairParams = new LayoutParams(-2, -2); flairParams.bottomMargin = theme.dp(8); addView(flair, flairParams);
        body = theme.text(14, theme.content, theme.regular);
        body.setLineHeight(theme.sp(20)); body.setEllipsize(TextUtils.TruncateAt.END);
        body.setPadding(0, 0, 0, theme.dp(8)); addView(body, new LayoutParams(-1, -2));
        media = new FrameLayout(context); media.setBackground(theme.shape(theme.surface, 16)); media.setClipToOutline(true);
        image = new ImageView(context); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); media.addView(image, new FrameLayout.LayoutParams(-1, -1));
        placeholder = theme.text(14, theme.secondary, theme.semibold); placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(theme.dp(16), theme.dp(16), theme.dp(16), theme.dp(16));
        media.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        badge = theme.text(12, Color.WHITE, theme.semibold); badge.setGravity(Gravity.CENTER);
        badge.setPadding(theme.dp(10), theme.dp(5), theme.dp(10), theme.dp(5)); badge.setBackground(theme.shape(0xcc181c1f, 20));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        badgeParams.setMargins(theme.dp(12), theme.dp(12), theme.dp(12), theme.dp(12)); media.addView(badge, badgeParams);
        media.setOnClickListener(view -> open()); addView(media, new LayoutParams(-1, theme.dp(200)));
        media.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) resizeMedia(right - left);
        });
        LinearLayout actions = new LinearLayout(context); actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout voting = new LinearLayout(context); voting.setGravity(Gravity.CENTER_VERTICAL);
        voting.setBackground(new android.graphics.drawable.InsetDrawable(theme.outlined(false), 0, theme.dp(8), 0, theme.dp(8)));
        voting.setPadding(0, 0, 0, 0);
        upvote = voteButton("icon_upvote", 1); downvote = voteButton("icon_downvote", -1);
        score = theme.text(12, theme.action, theme.semibold); score.setGravity(Gravity.CENTER);
        score.setMinWidth(theme.dp(20)); score.setSingleLine();
        score.setOnClickListener(view -> ArchiveActions.vote(getContext(), voteEntry, 1));
        voting.addView(upvote, new LayoutParams(theme.dp(36), theme.dp(48)));
        voting.addView(score, new LayoutParams(-2, theme.dp(48)));
        View voteDivider = new View(context); voteDivider.setBackgroundColor(theme.outline);
        voting.addView(voteDivider, new LayoutParams(theme.dp(1), theme.dp(16)));
        voting.addView(downvote, new LayoutParams(theme.dp(36), theme.dp(48)));
        replies = action("icon_comment"); share = action("icon_share"); repost = action("icon_rotate");
        replies.setOnClickListener(view -> open());
        share.setOnClickListener(view -> { if (item != null) ArchiveActions.share(getContext(), item); });
        repost.setOnClickListener(view -> ArchiveActions.repost(getContext(), voteEntry, navigation.events(), item));
        actions.addView(voting, new LayoutParams(-2, theme.dp(48)));
        LayoutParams replyParams = new LayoutParams(-2, theme.dp(48)); replyParams.setMarginStart(theme.dp(8)); actions.addView(replies, replyParams);
        View space = new View(context); actions.addView(space, new LayoutParams(0, 1, 1));
        LayoutParams repostParams = new LayoutParams(-2, theme.dp(48)); repostParams.setMarginStart(theme.dp(8));
        actions.addView(repost, repostParams);
        LayoutParams shareParams = new LayoutParams(-2, theme.dp(48)); shareParams.setMarginStart(theme.dp(8));
        actions.addView(share, shareParams);
        LayoutParams actionParams = new LayoutParams(-1, -2);
        addView(actions, actionParams);
    }

    private TextView action(String icon) {
        TextView view = theme.text(12, theme.action, theme.semibold);
        view.setMinWidth(theme.dp(40));
        view.setGravity(Gravity.CENTER); view.setPadding(theme.dp(12), 0, theme.dp(12), 0);
        view.setSingleLine(); view.setCompoundDrawablePadding(theme.dp(8));
        view.setCompoundDrawablesRelative(theme.icon(icon, 16, theme.action), null, null, null);
        // Reddit's Small content action: 32dp outlined pill, 16dp icon, 12dp horizontal padding.
        view.setBackground(new android.graphics.drawable.InsetDrawable(theme.outlined(true), 0, theme.dp(8), 0, theme.dp(8)));
        // InsetDrawable supplies padding to View.setBackground; restore content padding afterwards.
        view.setPadding(theme.dp(12), 0, theme.dp(12), 0);
        return view;
    }

    private ImageView voteButton(String icon, int direction) {
        ImageView button = new ImageView(getContext()); button.setImageDrawable(theme.icon(icon, 16, theme.action));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setBackground(new android.graphics.drawable.InsetDrawable(theme.ripple(Color.TRANSPARENT, 16), 0, theme.dp(8), 0, theme.dp(8)));
        button.setPadding(theme.dp(10), theme.dp(16), theme.dp(10), theme.dp(16));
        button.setFocusable(true); button.setOnClickListener(view -> ArchiveActions.vote(getContext(), voteEntry, direction));
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button"); info.setCheckable(true); info.setChecked(host.isSelected());
            }
        });
        return button;
    }

    private void renderVote(ArchiveActions.Entry entry) {
        if (item == null || !entry.fullname.equals((item.comments ? "t1_" : "t3_") + item.id)) return;
        ArchiveVoteState state = entry.state;
        boolean enabled = !entry.pending && !entry.loading && (state == null || state.allowed);
        upvote.setEnabled(enabled); downvote.setEnabled(enabled);
        upvote.setSelected(state != null && state.direction == 1); downvote.setSelected(state != null && state.direction == -1);
        int upColor = upvote.isSelected() ? theme.upvote : theme.action;
        int downColor = downvote.isSelected() ? theme.downvote : theme.action;
        upvote.setImageDrawable(theme.icon(upvote.isSelected() ? "icon_upvote_fill" : "icon_upvote", 16, upColor));
        downvote.setImageDrawable(theme.icon(downvote.isSelected() ? "icon_downvote_fill" : "icon_downvote", 16, downColor));
        upvote.setAlpha(enabled ? 1f : .45f); downvote.setAlpha(enabled ? 1f : .45f);
        score.setText(ArchiveDisplay.count(state == null ? item.score : state.score, "—"));
        score.setTextColor(state == null || state.direction == 0 ? theme.action : state.direction == 1 ? upColor : downColor);
        score.setEnabled(enabled);
        join.setVisibility(!item.comments && !item.subreddit.isEmpty() && !entry.joined ? VISIBLE : GONE);
        join.setEnabled(!entry.joinPending); join.setAlpha(entry.joinPending ? .45f : 1f);
        repost.setEnabled(!entry.loadedPost || entry.crosspostable);
        repost.setAlpha(repost.isEnabled() ? 1f : .45f);
        bindCommunity(entry.communityIcon);
        String status = entry.loading ? "Loading current vote" : entry.pending ? "Submitting vote" : !entry.signedIn ? "Sign in to vote"
                : state != null && !state.allowed ? state.reason : entry.error;
        upvote.setContentDescription((upvote.isSelected() ? "Undo upvote" : "Upvote") + (status.isEmpty() ? "" : ". " + status));
        downvote.setContentDescription((downvote.isSelected() ? "Undo downvote" : "Downvote") + (status.isEmpty() ? "" : ". " + status));
        score.setContentDescription(state == null ? "Archived score " + ArchiveDisplay.count(item.score, "unavailable")
                : state.score == null ? "Score hidden" : "Score " + state.score);
    }

    private void observeVotes() {
        ArchiveActions.unobserve(voteEntry, voteObserver); voteEntry = null;
        if (item != null && isAttachedToWindow()) voteEntry = ArchiveActions.observe(item, voteObserver);
    }

    void bind(ArchiveItem item, ArchivePostDetails details) {
        bind(item, details, "Archived");
    }
    void bind(ArchiveItem item, ArchivePostDetails details, String provenance) {
        boolean changed = this.item == null || !this.item.id.equals(item.id) || this.item.comments != item.comments;
        this.item = item; this.details = details;
        String name = item.subreddit.isEmpty() ? "u/" + item.author : "r/" + item.subreddit;
        android.text.SpannableString metadata = new android.text.SpannableString(name + " "
                + ArchiveDisplay.age(item.createdUtc, System.currentTimeMillis() / 1000) + " · " + provenance);
        metadata.setSpan(new android.text.style.ForegroundColorSpan(theme.secondary), name.length(), metadata.length(), 0);
        metadata.setSpan(new android.text.style.TypefaceSpan(theme.regular), name.length(), metadata.length(), 0);
        community.setText(metadata);
        community.setContentDescription(name + ". " + provenance + ". " + android.text.format.DateUtils.formatDateTime(getContext(), item.createdUtc * 1000,
                android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_YEAR));
        context.setVisibility(item.comments ? VISIBLE : GONE); context.setText("u/" + item.author + " commented");
        String titleText = item.comments ? (details == null ? "" : details.title) : item.title;
        title.setText(titleText); title.setVisibility(titleText.isEmpty() ? GONE : VISIBLE);
        title.setTextSize(item.comments ? 14 : 16); title.setTypeface(item.comments ? theme.semibold : theme.bold);
        title.setLineHeight(theme.sp(20));
        title.setMaxLines(item.comments ? 2 : 4);
        String flairText = item.comments ? "" : (details == null ? item.flair : details.flair);
        if (item.nsfw || details != null && details.nsfw) flairText = "NSFW" + (flairText.isEmpty() ? "" : " · " + flairText);
        if (item.spoiler || details != null && details.spoiler) flairText = "Spoiler" + (flairText.isEmpty() ? "" : " · " + flairText);
        flair.setText(flairText); flair.setVisibility(flairText.isEmpty() ? GONE : VISIBLE);
        body.setText(item.text.length() > 6000 ? item.text.substring(0, 6000) : item.text);
        body.setMaxLines(item.comments ? 6 : 3); body.setVisibility(item.text.isEmpty() ? GONE : VISIBLE);
        score.setText(ArchiveDisplay.count(item.score, "Vote"));
        score.setContentDescription((item.score == null ? "Score unavailable" : "Archived score " + item.score));
        Long count = details == null ? item.commentCount : details.commentCount;
        replyLabel = item.comments ? "" : ArchiveDisplay.count(count, "");
        replies.setText(replyLabel);
        replies.setContentDescription(item.comments ? "Open original comment"
                : (count == null ? "Comments" : "Archived comment count " + count) + ". Open original discussion");
        share.setContentDescription("Share " + (item.comments ? "comment" : "post") + " link");
        repost.setVisibility(item.comments ? GONE : VISIBLE); repost.setContentDescription("Repost to another community");
        join.setVisibility(item.comments || item.subreddit.isEmpty() ? GONE : VISIBLE);
        join.setContentDescription("Join r/" + item.subreddit);
        if (changed) bindCommunity(null);
        bindMedia();
        if (changed || voteEntry == null) observeVotes();
        else renderVote(voteEntry);
    }

    private void bindMedia() {
        boolean hasMedia = !item.comments && (details != null && (details.preview != null || details.video || details.galleryCount > 0)
                || item.kind.equals("image") || item.kind.contains("video"));
        media.setVisibility(hasMedia ? VISIBLE : GONE);
        boolean masked = item.nsfw || item.spoiler || details != null && (details.nsfw || details.spoiler);
        String nextUrl = hasMedia && !masked && details != null && details.preview != null ? details.preview.url : null;
        boolean video = details != null && details.video || item.kind.contains("video");
        badge.setVisibility(!masked && (video || details != null && details.galleryCount > 1) ? VISIBLE : GONE);
        badge.setText(video ? "" : details != null ? "1/" + details.galleryCount : "");
        badge.setCompoundDrawables(video ? theme.icon("icon_play", 24, Color.WHITE) : null, null, null, null);
        placeholder.setText(masked ? "Hidden preview · Open in Reddit" : "View media in Reddit");
        media.setContentDescription(masked ? "Hidden sensitive or spoiler preview. Open original discussion"
                : video ? "Video preview. Open original discussion to play" : "Post image preview. Open original discussion");
        ArchivePostDetails.Preview preview = details == null ? null : details.preview;
        aspect = preview != null && preview.width > 0 && preview.height > 0
                ? Math.max(.8f, Math.min(2f, (float) preview.width / preview.height)) : 16f / 9f;
        resizeMedia(media.getWidth());
        if (!java.util.Objects.equals(imageUrl, nextUrl)) {
            cancelImage(); imageUrl = nextUrl; image.setImageDrawable(null); placeholder.setVisibility(VISIBLE);
        }
        if (hasMedia && imageUrl != null && imageRequest == null && isAttachedToWindow()) startImage();
        if (!hasMedia || masked) { cancelImage(); imageUrl = null; image.setImageDrawable(null); placeholder.setVisibility(VISIBLE); }
    }
    private void resizeMedia(int width) {
        if (width <= 0) return;
        int height = Math.round(width / aspect);
        if (media.getLayoutParams().height != height) { media.getLayoutParams().height = height; media.requestLayout(); }
    }
    private void startImage() {
        int generation = ++imageGeneration;
        imageRequest = ArchiveImageLoader.load(imageUrl, bitmap -> {
            if (generation != imageGeneration || !isAttachedToWindow()) return;
            image.setImageBitmap(bitmap); placeholder.setVisibility(bitmap == null ? VISIBLE : GONE);
        });
    }
    void cancelImage() {
        imageGeneration++; if (imageRequest != null) imageRequest.cancel(); imageRequest = null;
        communityGeneration++; if (communityRequest != null) communityRequest.cancel(); communityRequest = null;
    }
    private void bindCommunity(String url) {
        if (!java.util.Objects.equals(communityUrl, url)) {
            communityGeneration++; if (communityRequest != null) communityRequest.cancel(); communityRequest = null;
            communityUrl = url; communityIcon.setImageDrawable(theme.icon("community_default", 24, Color.WHITE));
        }
        if (url != null && communityRequest == null && isAttachedToWindow()) {
            int generation = ++communityGeneration;
            communityRequest = ArchiveImageLoader.load(url, bitmap -> {
                if (generation == communityGeneration && isAttachedToWindow() && bitmap != null) communityIcon.setImageBitmap(bitmap);
            });
        }
    }
    void resumeImage() {
        if (imageUrl != null && imageRequest == null && isAttachedToWindow()) startImage();
        bindCommunity(communityUrl);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        boolean compact = getResources().getConfiguration().fontScale > 1.3f || MeasureSpec.getSize(widthSpec) < theme.dp(340);
        String commentLabel = compact ? "" : replyLabel;
        if (!replies.getText().toString().equals(commentLabel)) replies.setText(commentLabel);
        int replyGap = commentLabel.isEmpty() ? 0 : theme.dp(8);
        share.setCompoundDrawablePadding(0); repost.setCompoundDrawablePadding(0);
        if (replies.getCompoundDrawablePadding() != replyGap) replies.setCompoundDrawablePadding(replyGap);
        super.onMeasure(widthSpec, heightSpec);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); resumeImage(); observeVotes(); }
    @Override protected void onDetachedFromWindow() {
        ArchiveActions.unobserve(voteEntry, voteObserver); voteEntry = null; cancelImage(); super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && item != null) { observeVotes(); ArchiveActions.refresh(voteEntry); }
    }
    private void open() { if (item != null) navigation.open(item); }
    private void original() { if (item != null) navigation.original(item); }
}
