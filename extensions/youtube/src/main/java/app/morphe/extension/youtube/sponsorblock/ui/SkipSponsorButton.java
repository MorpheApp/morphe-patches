package app.morphe.extension.youtube.sponsorblock.ui;

import static app.morphe.extension.shared.ResourceUtils.getColor;
import static app.morphe.extension.shared.ResourceUtils.getDimension;
import static app.morphe.extension.shared.ResourceUtils.getDimensionPixelSize;
import static app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Objects;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.sponsorblock.SegmentPlaybackController;
import app.morphe.extension.youtube.sponsorblock.objects.SponsorSegment;

public class SkipSponsorButton extends FrameLayout {
    /**
     * Adds a high contrast border around the skip button.
     * <p>
     * This feature is not currently used.
     * If this is added, it needs an additional button width change because
     * as-is the skip button text is clipped when this is on.
     */
    private static final boolean highContrast = false;
    /**
     * Extra vertical padding for SB buttons when using bold player layouts.
     * YT seems to use the same skip button vertical padding for both the old and
     * new player layouts, and it's not enough for the bold layout and the SB buttons clip
     * the bold player buttons.
     */
    public static final int SB_BUTTON_EXTRA_VERTICAL_PADDING =
            RESTORE_OLD_PLAYER_BUTTONS
            ? 0
            : Dim.dp10;
    private final LinearLayout skipSponsorBtnContainer;
    private final TextView skipSponsorTextView;
    private final Paint background;
    private final Paint border;
    private SponsorSegment segment;
    final int defaultBottomMargin;
    final int ctaBottomMargin;

    public SkipSponsorButton(Context context) {
        this(context, null);
    }

    public SkipSponsorButton(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public SkipSponsorButton(Context context, AttributeSet attributeSet, int defStyleAttr) {
        this(context, attributeSet, defStyleAttr, 0);
    }

    public SkipSponsorButton(Context context, AttributeSet attributeSet, int defStyleAttr, int defStyleRes) {
        super(context, attributeSet, defStyleAttr, defStyleRes);

        LayoutInflater.from(context).inflate(ResourceUtils.getIdentifierOrThrow(context,
                        ResourceType.LAYOUT, "morphe_sb_skip_sponsor_button"),
                this, true);
        setMinimumHeight(getDimensionPixelSize("ad_skip_ad_button_min_height"));
        skipSponsorBtnContainer = Objects.requireNonNull(findViewById(ResourceUtils.getIdentifierOrThrow(
                context, ResourceType.ID, "morphe_sb_skip_sponsor_button_container")));

        background = new Paint();
        background.setColor(getColor("skip_ad_button_background_color"));
        background.setStyle(Paint.Style.FILL);

        border = new Paint();
        border.setColor(getColor("skip_ad_button_border_color"));
        border.setStrokeWidth(getDimension("ad_skip_ad_button_border_width"));
        border.setStyle(Paint.Style.STROKE);

        skipSponsorTextView = Objects.requireNonNull(findViewById(ResourceUtils.getIdentifier(context,
                ResourceType.ID, "morphe_sb_skip_sponsor_button_text")));
        ctaBottomMargin = getDimensionPixelSize("skip_button_cta_bottom_margin"); // Same as skip_button_default_portrait_bottom_margin
        defaultBottomMargin = getDimensionPixelSize("skip_button_default_bottom_margin")
                + SB_BUTTON_EXTRA_VERTICAL_PADDING;

        updateLayout();

        skipSponsorBtnContainer.setOnClickListener(v -> skipButtonClicked());
    }

    public void skipButtonClicked() {
        // The view controller handles hiding this button, but hide it here as well just in case something goofs.
        setVisibility(View.GONE);
        SegmentPlaybackController.onSkipSegmentClicked(segment);
    }

    @Override  // android.view.ViewGroup
    protected final void dispatchDraw(@NonNull Canvas canvas) {
        final int left = skipSponsorBtnContainer.getLeft();
        final int top = skipSponsorBtnContainer.getTop();
        final int right = left + skipSponsorBtnContainer.getWidth();
        final int bottom = top + skipSponsorBtnContainer.getHeight();

        // Determine corner radius for rounded button
        float cornerRadius = skipSponsorBtnContainer.getHeight() / 2f;

        if (Settings.SB_SQUARE_LAYOUT.get()) {
            // Square button.
            canvas.drawRect(left, top, right, bottom, background);
            if (highContrast) {
                canvas.drawLines(new float[]{
                                right, top, left, top,
                                left, top, left, bottom,
                                left, bottom, right, bottom},
                        border); // Draw square border.
            }
        } else {
            // Rounded button.
            RectF rect = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, background); // Draw rounded background.
            if (highContrast) {
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, border); // Draw rounded border.
            }
        }

        super.dispatchDraw(canvas);
    }

    /**
     * Update the layout of this button.
     */
    public void updateLayout() {
        if (Settings.SB_SQUARE_LAYOUT.get()) {
            // No padding for square corners.
            setPadding(0, 0, 0, 0);
        } else {
            // Apply padding for rounded corners.
            final int padding = SponsorBlockViewController.ROUNDED_LAYOUT_MARGIN;
            setPadding(padding, 0, padding, 0);
        }
    }

    public void updateSkipButtonText(@NonNull SponsorSegment segment) {
        this.segment = segment;
        CharSequence newText = segment.getSkipButtonText();

        //noinspection StringEqualsCharSequence
        if (newText.equals(skipSponsorTextView.getText())) {
            return;
        }
        skipSponsorTextView.setText(newText);
    }
}
