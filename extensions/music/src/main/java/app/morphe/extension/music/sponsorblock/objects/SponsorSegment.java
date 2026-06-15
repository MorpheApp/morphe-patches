package app.morphe.extension.music.sponsorblock.objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public class SponsorSegment implements Comparable<SponsorSegment> {

    @NonNull
    public final SegmentCategory category;
    @Nullable
    public final String UUID;
    public final long start;
    public final long end;
    public final boolean isLocked;
    public boolean didAutoSkip = false;

    public SponsorSegment(@NonNull SegmentCategory category, @Nullable String UUID,
                          long start, long end, boolean isLocked) {
        this.category = category;
        this.UUID = UUID;
        this.start = start;
        this.end = end;
        this.isLocked = isLocked;
    }

    public boolean shouldAutoSkip() {
        return category.getBehaviour().skipAutomatically;
    }

    public boolean endIsNear(long videoTime, long threshold) {
        return Math.abs(end - videoTime) <= threshold;
    }

    public boolean containsSegment(SponsorSegment other) {
        return start <= other.start && other.end <= end;
    }

    public long length() {
        return end - start;
    }

    @NonNull
    public String getSkippedToastText() {
        return category.skippedToastText.toString();
    }

    @Override
    public int compareTo(SponsorSegment o) {
        return start == o.start
                ? Long.compare(o.length(), length())
                : Long.compare(start, o.start);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SponsorSegment other)) return false;
        return Objects.equals(UUID, other.UUID)
                && category == other.category
                && start == other.start
                && end == other.end;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(UUID);
    }

    @NonNull
    @Override
    public String toString() {
        return "SponsorSegment{category=" + category + ", start=" + start + ", end=" + end + '}';
    }
}
