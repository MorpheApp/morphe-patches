package app.morphe.extension.reddit.patches.archive;

/** Live account state, deliberately separate from the archived score. */
public final class ArchiveVoteState {
    public final Long score;
    public final int direction;
    public final boolean allowed;
    public final String reason;

    public ArchiveVoteState(Long score, int direction, boolean allowed, String reason) {
        if (direction < -1 || direction > 1) throw new IllegalArgumentException("Invalid vote direction");
        this.score = score; this.direction = direction; this.allowed = allowed; this.reason = reason;
    }
    public static ArchiveVoteState live(int score, int direction, boolean archived, boolean hidden) {
        return new ArchiveVoteState(hidden ? null : (long) score, direction, !archived,
                archived ? "Voting is closed on this item" : "");
    }
    public ArchiveVoteState withDirection(int next) {
        return new ArchiveVoteState(score == null ? null : score + next - direction, next, allowed, reason);
    }
    public int toggle(int requested) {
        if (requested != -1 && requested != 1) throw new IllegalArgumentException("Expected an arrow");
        return direction == requested ? 0 : requested;
    }
}
