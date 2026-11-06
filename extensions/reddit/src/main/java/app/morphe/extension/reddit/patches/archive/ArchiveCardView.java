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
    interface Navigation { void open(ArchiveItem item); default void original(ArchiveItem item) { open(item); } }
    final ArchiveTheme theme;
    private final TextView community, time, title, body, flair, context, score, replies, share, overflow;
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

    ArchiveCardView(Context context, ArchiveTheme theme, Navigation navigation) {
        super(context); this.theme = theme; this.navigation = navigation;
        setOrientation(VERTICAL); setPadding(theme.dp(16), theme.dp(8), theme.dp(16), 0);
        setBackground(theme.ripple(theme.background, 0));
        setFocusable(true); setOnClickListener(view -> open());
        LinearLayout metadata = new LinearLayout(context); metadata.setGravity(Gravity.CENTER_VERTICAL);
        ImageView communityIcon = new ImageView(context);
        communityIcon.setImageDrawable(theme.icon("icon_community_fill", 20, theme.secondary));
        communityIcon.setPadding(theme.dp(3), theme.dp(3), theme.dp(3), theme.dp(3));
        communityIcon.setBackground(theme.shape(theme.surface, 24));
        communityIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        metadata.addView(communityIcon, new LayoutParams(theme.dp(24), theme.dp(24)));
        community = theme.text(12, theme.foreground, theme.semibold);
        community.setSingleLine(); community.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams communityParams = new LayoutParams(0, -2, 1); communityParams.setMarginStart(theme.dp(8));
        metadata.addView(community, communityParams);
        time = theme.text(12, theme.secondary, theme.regular); time.setPadding(theme.dp(6), 0, 0, 0); metadata.addView(time);
        overflow = theme.text(12, theme.secondary, theme.regular);
        overflow.setCompoundDrawables(theme.icon("icon_overflow_horizontal", 20, theme.secondary), null, null, null);
        overflow.setGravity(Gravity.CENTER); overflow.setPadding(theme.dp(14), 0, 0, 0);
        overflow.setBackground(theme.ripple(Color.TRANSPARENT, 24));
        overflow.setOnClickListener(view -> original()); overflow.setContentDescription("Open original discussion for more actions");
        metadata.addView(overflow, new LayoutParams(theme.dp(48), theme.dp(48)));
        addView(metadata, new LayoutParams(-1, -2));
        this.context = theme.text(12, theme.secondary, theme.regular);
        this.context.setPadding(0, 0, 0, theme.dp(6)); addView(this.context);
        title = theme.text(16, theme.foreground, theme.bold);
        title.setLineSpacing(theme.dp(2), 1); title.setPadding(0, 0, 0, theme.dp(8));
        title.setMaxLines(4); title.setEllipsize(TextUtils.TruncateAt.END); addView(title, new LayoutParams(-1, -2));
        flair = theme.text(12, theme.foreground, theme.semibold);
        flair.setPadding(theme.dp(8), theme.dp(3), theme.dp(8), theme.dp(3));
        flair.setMaxLines(2); flair.setEllipsize(TextUtils.TruncateAt.END);
        flair.setBackground(theme.shape(theme.surface, 12));
        LayoutParams flairParams = new LayoutParams(-2, -2); flairParams.bottomMargin = theme.dp(8); addView(flair, flairParams);
        body = theme.text(14, theme.foreground, theme.regular);
        body.setLineSpacing(theme.dp(3), 1); body.setEllipsize(TextUtils.TruncateAt.END);
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
        score = action("icon_upvote");
        score.setCompoundDrawablesRelative(theme.icon("icon_upvote", 20, theme.foreground), null,
                theme.icon("icon_downvote", 20, theme.foreground), null);
        replies = action("icon_comment"); share = action("icon_share"); share.setText("Share");
        actions.addView(score, new LayoutParams(-2, theme.dp(48)));
        LayoutParams replyParams = new LayoutParams(-2, theme.dp(48)); replyParams.setMarginStart(theme.dp(8)); actions.addView(replies, replyParams);
        View space = new View(context); actions.addView(space, new LayoutParams(0, 1, 1));
        actions.addView(share, new LayoutParams(-2, theme.dp(48)));
        LayoutParams actionParams = new LayoutParams(-1, -2); actionParams.topMargin = theme.dp(4); actionParams.bottomMargin = theme.dp(4);
        addView(actions, actionParams);
        View line = new View(context); line.setBackgroundColor(theme.divider); addView(line, new LayoutParams(-1, Math.max(1, theme.dp(.5f))));
    }

    private TextView action(String icon) {
        TextView view = theme.text(12, theme.foreground, theme.semibold);
        view.setMinWidth(theme.dp(48));
        view.setGravity(Gravity.CENTER); view.setPadding(theme.dp(10), 0, theme.dp(10), 0);
        view.setCompoundDrawablePadding(theme.dp(5));
        view.setCompoundDrawablesRelative(theme.icon(icon, 20, theme.foreground), null, null, null);
        // 32dp visual pill inside a 48dp touch target.
        view.setBackground(new android.graphics.drawable.InsetDrawable(theme.ripple(theme.surface, 20), 0, theme.dp(8), 0, theme.dp(8)));
        view.setOnClickListener(v -> original()); return view;
    }

    void bind(ArchiveItem item, ArchivePostDetails details) {
        bind(item, details, "Archived");
    }
    void bind(ArchiveItem item, ArchivePostDetails details, String provenance) {
        this.item = item; this.details = details;
        String name = item.subreddit.isEmpty() ? "u/" + item.author : "r/" + item.subreddit;
        android.text.SpannableString metadata = new android.text.SpannableString(name + " · "
                + ArchiveDisplay.age(item.createdUtc, System.currentTimeMillis() / 1000) + " · " + provenance);
        metadata.setSpan(new android.text.style.ForegroundColorSpan(theme.secondary), name.length(), metadata.length(), 0);
        metadata.setSpan(new android.text.style.TypefaceSpan(theme.regular), name.length(), metadata.length(), 0);
        community.setText(metadata); time.setVisibility(GONE);
        community.setContentDescription(name + ". " + provenance + ". " + android.text.format.DateUtils.formatDateTime(getContext(), item.createdUtc * 1000,
                android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_YEAR));
        context.setVisibility(item.comments ? VISIBLE : GONE); context.setText("u/" + item.author + " commented");
        String titleText = item.comments ? (details == null ? "" : details.title) : item.title;
        title.setText(titleText); title.setVisibility(titleText.isEmpty() ? GONE : VISIBLE);
        title.setTextSize(item.comments ? 14 : 16); title.setTypeface(item.comments ? theme.semibold : theme.bold);
        title.setMaxLines(item.comments ? 2 : 4);
        String flairText = item.comments ? "" : (details == null ? item.flair : details.flair);
        if (item.nsfw || details != null && details.nsfw) flairText = "NSFW" + (flairText.isEmpty() ? "" : " · " + flairText);
        if (item.spoiler || details != null && details.spoiler) flairText = "Spoiler" + (flairText.isEmpty() ? "" : " · " + flairText);
        flair.setText(flairText); flair.setVisibility(flairText.isEmpty() ? GONE : VISIBLE);
        body.setText(item.text.length() > 6000 ? item.text.substring(0, 6000) : item.text);
        body.setMaxLines(item.comments ? 6 : 3); body.setVisibility(item.text.isEmpty() ? GONE : VISIBLE);
        score.setText(ArchiveDisplay.count(item.score, "Vote"));
        score.setContentDescription((item.score == null ? "Score unavailable" : "Archived score " + item.score) + ". Open original discussion to vote");
        Long count = details == null ? item.commentCount : details.commentCount;
        replyLabel = item.comments ? "Reply" : ArchiveDisplay.count(count, "");
        replies.setText(replyLabel);
        replies.setContentDescription(item.comments ? "Open original comment to reply"
                : (count == null ? "Comments" : "Archived comment count " + count) + ". Open original discussion");
        share.setContentDescription("Open original discussion to share"); bindMedia();
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
    }
    void resumeImage() { if (imageUrl != null && imageRequest == null && isAttachedToWindow()) startImage(); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        boolean compact = getResources().getConfiguration().fontScale > 1.3f || MeasureSpec.getSize(widthSpec) < theme.dp(340);
        String shareLabel = compact ? "" : "Share";
        String commentLabel = compact ? "" : replyLabel;
        if (!share.getText().toString().equals(shareLabel)) share.setText(shareLabel);
        if (!replies.getText().toString().equals(commentLabel)) replies.setText(commentLabel);
        super.onMeasure(widthSpec, heightSpec);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); resumeImage(); }
    @Override protected void onDetachedFromWindow() { cancelImage(); super.onDetachedFromWindow(); }
    private void open() { if (item != null) navigation.open(item); }
    private void original() { if (item != null) navigation.original(item); }
}
