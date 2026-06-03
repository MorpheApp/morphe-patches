/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.sponsorblock.ui;

import static app.morphe.extension.shared.ResourceUtils.getColor;
import static app.morphe.extension.shared.ResourceUtils.getDimensionPixelSize;
import static app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS;
import static app.morphe.extension.youtube.sponsorblock.ui.SkipSponsorButton.SB_BUTTON_EXTRA_VERTICAL_PADDING;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.ViewAnimations;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.sponsorblock.SponsorBlockUtils;

/**
 * Floating panel shown over the player when creating a new SponsorBlock segment.
 * Supports drag-to-reposition; the position is persisted across sessions via
 * {@link Settings#SB_NEW_SEGMENT_PANEL_POSITION} as relative fractions of the parent's
 * dimensions, so it scales correctly across screen rotations and different screen sizes.
 */
public final class NewSegmentLayout extends FrameLayout {
    private static final ColorStateList rippleColorStateList = new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_enabled}},
            new int[]{0x33ffffff} // Ripple effect color (semi-transparent white)
    );

    final int defaultBottomMargin;
    final int ctaBottomMargin;

    private float dragStartX, dragStartY;
    private float initialTransX, initialTransY;
    private boolean isDragging;
    // Stored squared to compare against squared distance, avoiding sqrt() in the hot path.
    private final int touchSlopSquare;

    public NewSegmentLayout(final Context context) {
        this(context, null);
    }

    public NewSegmentLayout(final Context context, final AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public NewSegmentLayout(final Context context, final AttributeSet attributeSet, final int defStyleAttr) {
        this(context, attributeSet, defStyleAttr, 0);
    }

    public NewSegmentLayout(final Context context, final AttributeSet attributeSet,
                            final int defStyleAttr, final int defStyleRes) {
        super(context, attributeSet, defStyleAttr, defStyleRes);

        final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        touchSlopSquare = touchSlop * touchSlop;

        LayoutInflater.from(context).inflate(ResourceUtils.getIdentifierOrThrow(context,
                ResourceType.LAYOUT,  "morphe_sb_new_segment"), this, true
        );

        initializeButton(
                context,
                "morphe_sb_new_segment_rewind",
                "morphe_sb_backward",
                () -> VideoInformation.seekToRelative(-Settings.SB_CREATE_NEW_SEGMENT_STEP.get()),
                "Rewind button clicked"
        );

        initializeButton(
                context,
                "morphe_sb_new_segment_forward",
                "morphe_sb_forward",
                () -> VideoInformation.seekToRelative(Settings.SB_CREATE_NEW_SEGMENT_STEP.get()),
                "Forward button clicked"
        );

        initializeButton(
                context,
                "morphe_sb_new_segment_adjust",
                "morphe_sb_adjust",
                SponsorBlockUtils::onMarkLocationClicked,
                "Adjust button clicked"
        );

        initializeButton(
                context,
                "morphe_sb_new_segment_compare",
                "morphe_sb_compare",
                SponsorBlockUtils::onPreviewClicked,
                "Compare button clicked"
        );

        initializeButton(
                context,
                "morphe_sb_new_segment_edit",
                "morphe_sb_edit",
                SponsorBlockUtils::onEditByHandClicked,
                "Edit button clicked"
        );

        initializeButton(
                context,
                "morphe_sb_new_segment_publish",
                "morphe_sb_publish",
                SponsorBlockUtils::onPublishClicked,
                "Publish button clicked"
        );

        defaultBottomMargin = getDimensionPixelSize("brand_interaction_default_bottom_margin")
                + SB_BUTTON_EXTRA_VERTICAL_PADDING;
        ctaBottomMargin = getDimensionPixelSize("brand_interaction_cta_bottom_margin");
    }

    /**
     * Initializes a segment button with the given resource identifier name with the given handler and a ripple effect.
     *
     * @param context                The context.
     * @param resourceIdentifierName The resource identifier name for the button.
     * @param handler                The handler for the button's click event.
     * @param debugMessage           The debug message to print when the button is clicked.
     */
    private void initializeButton(Context context,
                                  String resourceIdentifierName,
                                  String imageResourceName,
                                  ButtonOnClickHandlerFunction handler,
                                  String debugMessage) {
        ImageButton button = findViewById(ResourceUtils.getIdentifierOrThrow(
                context, ResourceType.ID, resourceIdentifierName));

        final int background = ResourceUtils.getIdentifierOrThrow(
                ResourceType.DRAWABLE,
                RESTORE_OLD_PLAYER_BUTTONS
                        ? imageResourceName
                        : imageResourceName + "_bold");
        button.setImageResource(background);

        // Add ripple effect
        button.setBackground(new RippleDrawable(rippleColorStateList, null, null));
        button.setOnClickListener(v -> {
            handler.apply();
            Logger.printDebug(() -> debugMessage);
        });
    }

    /**
     * Update the layout of this UI control.
     */
    public void updateLayout() {
        final boolean squareLayout = Settings.SB_SQUARE_LAYOUT.get();

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) getLayoutParams();
        final int margin = squareLayout
                ? 0
                : SponsorBlockViewController.ROUNDED_LAYOUT_MARGIN;
        params.setMarginStart(margin);
        setLayoutParams(params);

        GradientDrawable backgroundDrawable = new GradientDrawable();
        backgroundDrawable.setColor(getColor("skip_ad_button_background_color"));
        final float cornerRadius = squareLayout ? 0f : Dim.dp16;
        backgroundDrawable.setCornerRadius(cornerRadius);
        setBackground(backgroundDrawable);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Reapply the saved position on every layout change (first load, rotation, resize).
        // Stored as relative fractions, so scaling to the new parent size preserves the
        // panel's proportional location across orientations (e.g. right edge stays right edge).
        // Skip during an active drag to avoid snapping the panel mid-gesture.
        if (changed && !isDragging && getWidth() > 0 && getHeight() > 0) {
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null && parent.getWidth() > 0 && parent.getHeight() > 0) {
                final long saved = Settings.SB_NEW_SEGMENT_PANEL_POSITION.get();
                // Position is packed as two 32-bit float bit patterns: X fraction in the upper
                // 32 bits, Y fraction in the lower 32 bits. Default 0L → (0f, 0f) → no translation.
                setTranslationX(Float.intBitsToFloat((int) (saved >> 32)) * parent.getWidth());
                setTranslationY(Float.intBitsToFloat((int) saved) * parent.getHeight());
                clampTranslationToBounds();
            }
        }
    }

    /**
     * Required when overriding {@link #onTouchEvent} - ensures accessibility services receive click events.
     */
    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = ev.getRawX();
                dragStartY = ev.getRawY();
                initialTransX = getTranslationX();
                initialTransY = getTranslationY();
                isDragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!isDragging) {
                    float dx = ev.getRawX() - dragStartX;
                    float dy = ev.getRawY() - dragStartY;
                    if (dx * dx + dy * dy > touchSlopSquare) {
                        isDragging = true;
                        // Prevent ancestor scrollable views from stealing subsequent events.
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    }
                }
                return isDragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                return false;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                setTranslationX(initialTransX + (ev.getRawX() - dragStartX));
                setTranslationY(initialTransY + (ev.getRawY() - dragStartY));
                return true;
            case MotionEvent.ACTION_UP:
                isDragging = false;
                clampTranslationToBounds();
                saveRelativePosition();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                return true;
        }
        return false;
    }

    /**
     * Clamps the panel's translation so all four edges stay within the parent's bounds,
     * preventing the panel from becoming unreachable after a screen rotation or resize.
     * No-op if the parent is unavailable or the view has not completed its first layout pass.
     */
    private void clampTranslationToBounds() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) return;

        final int height = getHeight();
        final int width = getWidth();
        if (width == 0 || height == 0) return;

        final int top = getTop();
        final int left = getLeft();
        final float transX = Math.max(-left, Math.min(getTranslationX(), parent.getWidth() - left - width));
        final float transY = Math.max(-top, Math.min(getTranslationY(), parent.getHeight() - top - height));
        setTranslationX(transX);
        setTranslationY(transY);
    }

    private void saveRelativePosition() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) return;
        // Save as fractions of parent dimensions so the position scales correctly on rotation.
        // & 0xFFFFFFFFL prevents sign extension when widening the Y int bits to long.
        Settings.SB_NEW_SEGMENT_PANEL_POSITION.save(
                (long) Float.floatToIntBits(getTranslationX() / parent.getWidth()) << 32
                        | (Float.floatToIntBits(getTranslationY() / parent.getHeight()) & 0xFFFFFFFFL));
    }

    void showWithAnimation() {
        ViewAnimations.fadeIn(this, 100);
    }

    void hideWithAnimation() {
        ViewAnimations.fadeOut(this, 100);
    }

    @FunctionalInterface
    private interface ButtonOnClickHandlerFunction {
        void apply();
    }
}
