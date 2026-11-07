package app.morphe.extension.reddit.patches.archive;

/** Immutable, plain-text archive record. Discussion URLs are built from validated IDs. */
public final class ArchiveItem {
    public final String id, author, subreddit, title, text, linkId, kind;
    public final long createdUtc;
    public final Long score;
    public final boolean comments;
    public final Long commentCount;
    public final String flair;
    public final boolean nsfw, spoiler;

    public ArchiveItem(String id, String author, String subreddit, long createdUtc, String title,
                       String text, String linkId, String kind, Long score, boolean comments) {
        this(id, author, subreddit, createdUtc, title, text, linkId, kind, score, comments,
                null, "", false, false);
    }

    public ArchiveItem(String id, String author, String subreddit, long createdUtc, String title,
                       String text, String linkId, String kind, Long score, boolean comments,
                       Long commentCount, String flair, boolean nsfw, boolean spoiler) {
        this.id = id; this.author = author; this.subreddit = subreddit; this.createdUtc = createdUtc;
        this.title = title; this.text = text; this.linkId = linkId; this.kind = kind;
        this.score = score; this.comments = comments;
        this.commentCount = commentCount; this.flair = flair; this.nsfw = nsfw; this.spoiler = spoiler;
    }

    public String discussionUrl() {
        // Do not trust a permalink or external URL supplied by an archive record.
        return comments ? "https://www.reddit.com/comments/" + linkId + "/_/" + id + "/"
                : "https://www.reddit.com/comments/" + id + "/";
    }
}
