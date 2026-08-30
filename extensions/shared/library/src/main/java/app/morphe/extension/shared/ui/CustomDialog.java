/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original first edition code:
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/584b00fd87f83504b8886e4f3f674f8c3943cd91
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/2e9d6959c94df7588b9e34b18770e9f437e91926
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/ece8076f7cefd752b97515014bc50fe4fd80171e
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/2b62fc2224c42da024fd64602346ff30613517c0
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/a426e2af5086367a2a1fee83abbbd2ea230bda06
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/584b00fd87f83504b8886e4f3f674f8c3943cd91
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/14a8f4fb96f5e2a4bc264a54115e0870b1a1ffa8
 * https://github.com/MorpheApp/morphe-patches/commit/f5371ca998c019609c2b5558b3408ab1fec065c8
 * https://github.com/MorpheApp/morphe-patches/pull/2638
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.ui;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * A utility class for creating a customizable dialog with a title, message or EditText, and up to three buttons (OK, Cancel, Neutral).
 * The dialog supports themed colors, rounded corners, and dynamic button layout based on screen width. It is dismissible by default.
 */
public class CustomDialog {
    private final Context context;
    private final Dialog dialog;
    private final LinearLayout mainLayout;

    /**
     * Creates a custom dialog with a styled layout, including a title, message, buttons, and an optional EditText.
     * The dialog's appearance adapts to the app's dark mode setting, with rounded corners and customizable button actions.
     * Buttons adjust dynamically to their text content and are arranged in a single row if they fit within 80% of the
     * screen width, with the Neutral button aligned to the left and OK/Cancel buttons centered on the right.
     * If buttons do not fit, each is placed on a separate row, all aligned to the right.
     *
     * @param context                     Context used to create the dialog.
     * @param title                       Title text of the dialog.
     * @param message                     Message text of the dialog (supports Spanned for HTML), or null if replaced by EditText.
     * @param editText                    EditText to include in the dialog, or null if no EditText is needed.
     * @param okButtonText                OK button text, or null to use the default "OK" string.
     * @param onOkClick                   Action to perform when the OK button is clicked.
     * @param onCancelClick               Action to perform when the Cancel button is clicked, or null if no Cancel button is needed.
     * @param neutralButtonText           Neutral button text, or null if no Neutral button is needed.
     * @param onNeutralClick              Action to perform when the Neutral button is clicked, or null if no Neutral button is needed.
     * @param dismissDialogOnNeutralClick If the dialog should be dismissed when the Neutral button is clicked.
     * @return The Dialog and its main LinearLayout container.
     */
    public static Pair<Dialog, LinearLayout> create(Context context, CharSequence title, CharSequence message,
                                                    @Nullable EditText editText, CharSequence okButtonText,
                                                    Runnable onOkClick, Runnable onCancelClick,
                                                    @Nullable CharSequence neutralButtonText,
                                                    @Nullable Runnable onNeutralClick,
                                                    boolean dismissDialogOnNeutralClick) {
        return create(context, title, message, editText, okButtonText, onOkClick, onCancelClick,
                neutralButtonText, onNeutralClick, dismissDialogOnNeutralClick, true);
    }

    public static Pair<Dialog, LinearLayout> create(Context context, CharSequence title, CharSequence message,
                                                    @Nullable EditText editText, CharSequence okButtonText,
                                                    Runnable onOkClick, Runnable onCancelClick,
                                                    @Nullable CharSequence neutralButtonText,
                                                    @Nullable Runnable onNeutralClick,
                                                    boolean dismissDialogOnNeutralClick,
                                                    boolean accentOkButton) {
        Logger.printDebug(() -> "Creating custom dialog with title: " + title);
        CustomDialog customDialog = new CustomDialog(context, title, message, editText,
                okButtonText, onOkClick, onCancelClick,
                neutralButtonText, onNeutralClick, dismissDialogOnNeutralClick, accentOkButton);
        return new Pair<>(customDialog.dialog, customDialog.mainLayout);
    }

    /**
     * Initializes a custom dialog with the specified parameters.
     *
     * @param context                     Context used to create the dialog.
     * @param title                       Title text of the dialog.
     * @param message                     Message text of the dialog, or null if replaced by EditText.
     * @param editText                    EditText to include in the dialog, or null if no EditText is needed.
     * @param okButtonText                OK button text, or null to use the default "OK" string.
     * @param onOkClick                   Action to perform when the OK button is clicked.
     * @param onCancelClick               Action to perform when the Cancel button is clicked, or null if no Cancel button is needed.
     * @param neutralButtonText           Neutral button text, or null if no Neutral button is needed.
     * @param onNeutralClick              Action to perform when the Neutral button is clicked, or null if no Neutral button is needed.
     * @param dismissDialogOnNeutralClick If the dialog should be dismissed when the Neutral button is clicked.
     */
    private CustomDialog(Context context, CharSequence title, CharSequence message, @Nullable EditText editText,
                         CharSequence okButtonText, Runnable onOkClick, Runnable onCancelClick,
                         @Nullable CharSequence neutralButtonText, @Nullable Runnable onNeutralClick,
                         boolean dismissDialogOnNeutralClick, boolean accentOkButton) {
        this.context = context;
        this.dialog = new Dialog(context);
        this.dialog.requestWindowFeature(Window.FEATURE_NO_TITLE); // Remove default title bar.

        // Create main layout.
        mainLayout = createMainLayout();
        addTitle(title);
        addContent(message, editText);
        addButtons(okButtonText, onOkClick, onCancelClick, neutralButtonText,
                onNeutralClick, dismissDialogOnNeutralClick, accentOkButton);

        // Set dialog content and window attributes.
        dialog.setContentView(mainLayout);
        Window window = dialog.getWindow();
        if (window != null) {
            Utils.setDialogWindowParameters(window, Gravity.CENTER, 0, 90, false);
        }
    }

    /**
     * Creates the main layout for the dialog with vertical orientation and rounded corners.
     *
     * @return The configured LinearLayout for the dialog.
     */
    private LinearLayout createMainLayout() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(Dim.dp24, Dim.dp16, Dim.dp24, Dim.dp24);
        layout.setBackground(createRoundedBackground(28, ThemeUtils.getDialogBackgroundColor()));

        return layout;
    }

    /**
     * Adds a title to the dialog if provided.
     *
     * @param title The title text to display.
     */
    private void addTitle(CharSequence title) {
        if (TextUtils.isEmpty(title)) return;

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextSize(18);
        titleView.setTextColor(ThemeUtils.getAppForegroundColor());
        titleView.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, Dim.dp16);
        titleView.setLayoutParams(params);

        mainLayout.addView(titleView);
    }

    /**
     * Adds a message or EditText to the dialog within a ScrollView.
     *
     * @param message  The message text to display (supports Spanned for HTML), or null if replaced by EditText.
     * @param editText The EditText to include, or null if no EditText is needed.
     */
    private void addContent(CharSequence message, @Nullable EditText editText) {
        // Create content container (message/EditText) inside a ScrollView only if message or editText is provided.
        if (message == null && editText == null) return;

        ScrollView scrollView = new ScrollView(context);
        // Disable the vertical scrollbar.
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout contentContainer = new LinearLayout(context);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentContainer);

        // EditText (if provided).
        if (editText != null) {
            scrollView.setBackground(createRoundedBackground(10, ThemeUtils.getEditTextBackground()));
            scrollView.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);
            scrollView.setClipToOutline(true);

            // Remove EditText from its current parent, if any.
            ViewGroup parent = (ViewGroup) editText.getParent();
            if (parent != null) parent.removeView(editText);
            // Style the EditText to match the dialog theme.
            editText.setTextColor(ThemeUtils.getAppForegroundColor());
            editText.setBackgroundColor(Color.TRANSPARENT);
            editText.setPadding(0, 0, 0, 0);
            contentContainer.addView(editText, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            // Message (if not replaced by EditText).
        } else {
            TextView messageView = new TextView(context);
            // Supports Spanned (HTML).
            messageView.setText(message);
            messageView.setTextSize(16);
            messageView.setTextColor(ThemeUtils.getAppForegroundColor());
            // Enable HTML link clicking if the message contains links.
            if (message instanceof Spanned) {
                messageView.setMovementMethod(LinkMovementMethod.getInstance());
            }
            contentContainer.addView(messageView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Weight to take available space.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f);
        scrollView.setLayoutParams(params);
        // Add ScrollView to main layout only if content exist.
        mainLayout.addView(scrollView);
    }

    /**
     * Adds buttons to the dialog, arranging them dynamically based on their widths.
     *
     * @param okButtonText                OK button text, or null to use the default "OK" string.
     * @param onOkClick                   Action for the OK button click.
     * @param onCancelClick               Action for the Cancel button click, or null if no Cancel button.
     * @param neutralButtonText           Neutral button text, or null if no Neutral button.
     * @param onNeutralClick              Action for the Neutral button click, or null if no Neutral button.
     * @param dismissDialogOnNeutralClick If the dialog should dismiss on Neutral button click.
     */
    private void addButtons(CharSequence okButtonText, Runnable onOkClick, Runnable onCancelClick,
                            @Nullable CharSequence neutralButtonText, @Nullable Runnable onNeutralClick,
                            boolean dismissDialogOnNeutralClick, boolean accentOkButton) {
        // Button container.
        LinearLayout buttonContainer = new LinearLayout(context);
        buttonContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams buttonContainerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonContainerParams.setMargins(0, Dim.dp16, 0, 0);
        buttonContainer.setLayoutParams(buttonContainerParams);

        List<Button> buttons = new ArrayList<>();
        List<Integer> buttonWidths = new ArrayList<>();

        // Create buttons in order: Neutral, Cancel, OK.
        if (neutralButtonText != null && onNeutralClick != null) {
            Button neutralButton = createButton(context, dialog, neutralButtonText,
                    onNeutralClick, false, dismissDialogOnNeutralClick);
            buttons.add(neutralButton);
            buttonWidths.add(measureButtonWidth(neutralButton));
        }
        if (onCancelClick != null) {
            String localizedCancelString = ResourceUtils.getSystemStringByLocale("cancel",
                    BaseSettings.MORPHE_LANGUAGE.get().getLocale());
            Button cancelButton = createButton(context, dialog, localizedCancelString,
                    onCancelClick, false, true);
            buttons.add(cancelButton);
            buttonWidths.add(measureButtonWidth(cancelButton));
        }
        if (onOkClick != null) {
            CharSequence localizedOkString = okButtonText != null ? okButtonText
                    : ResourceUtils.getSystemStringByLocale("ok",
                    BaseSettings.MORPHE_LANGUAGE.get().getLocale());
            Button okButton = createButton(context, dialog, localizedOkString,
                    onOkClick, accentOkButton, true);
            buttons.add(okButton);
            buttonWidths.add(measureButtonWidth(okButton));
        }

        // Handle button layout.
        layoutButtons(buttonContainer, buttons, buttonWidths);
        mainLayout.addView(buttonContainer);
    }

    /**
     * Creates a styled button for use inside a {@link CustomDialog} layout.
     * Can be called externally to create additional buttons that match the dialog's visual style.
     *
     * @param context       Context used to create the button.
     * @param dialog        The dialog to dismiss on click, or null if no dismissal is needed.
     * @param text          The button text to display.
     * @param onClick       The action to perform on button click, or null for no action.
     * @param isOkButton    If true, uses the OK button style (accent background); otherwise uses the secondary style.
     * @param dismissDialog If true, dismisses the dialog when the button is clicked.
     * @return The created Button.
     */
    public static Button createButton(Context context, @Nullable Dialog dialog,
                                      CharSequence text, @Nullable Runnable onClick,
                                      boolean isOkButton, boolean dismissDialog) {
        Button button = new Button(context, null, 0);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        // Set internal padding.
        button.setPadding(Dim.dp8, 0, Dim.dp8, 0);
        // Clear theme-imposed minimum width so text is never clipped with ellipsis.
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        // Background color for OK button (inversion) and Cancel/Neutral buttons.
        button.setBackground(createRoundedBackground(20, isOkButton
                ? ThemeUtils.getOkButtonBackgroundColor()
                : ThemeUtils.getCancelOrNeutralButtonBackgroundColor()));

        button.setTextColor(Utils.isDarkModeEnabled()
                ? (isOkButton ? Color.BLACK : Color.WHITE)
                : (isOkButton ? Color.WHITE : Color.BLACK));

        ViewAnimations.applyPressEffect(button);

        button.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
            if (dismissDialog && dialog != null) dialog.dismiss();
        });

        return button;
    }

    /**
     * Creates a row of two equally sized buttons, to add to a dialog layout
     * in addition to the dialog buttons.
     *
     * @param context Context used to create the row.
     * @param first   Button shown on the left.
     * @param second  Button shown on the right.
     * @return The created row.
     */
    public static LinearLayout createButtonRow(Context context, Button first, Button second) {
        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, Dim.dp36, 1.0f);
        firstParams.setMargins(0, 0, Dim.dp4, 0);
        first.setLayoutParams(firstParams);

        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, Dim.dp36, 1.0f);
        secondParams.setMargins(Dim.dp4, 0, 0, 0);
        second.setLayoutParams(secondParams);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, Dim.dp16, 0, 0);
        row.setLayoutParams(rowParams);
        row.addView(first);
        row.addView(second);

        return row;
    }

    /**
     * Returns the usable pixel width available for buttons inside the dialog.
     * Dialog window = 90% of screen width; mainLayout horizontal padding = dp24 * 2.
     */
    private int buttonAreaWidth() {
        return (int) (Dim.getScreenWidth() * 0.9f) - Dim.dp24 * 2;
    }

    /**
     * Measures the intrinsic width of a button, capped at the available button area.
     */
    private int measureButtonWidth(Button button) {
        int widthSpec = View.MeasureSpec.makeMeasureSpec(buttonAreaWidth(), View.MeasureSpec.AT_MOST);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        button.measure(widthSpec, heightSpec);
        return button.getMeasuredWidth();
    }

    /**
     * Arranges buttons in the dialog, either in a single row or multiple rows based on their total width.
     *
     * @param buttonContainer The container for the buttons.
     * @param buttons         The list of buttons to arrange.
     * @param buttonWidths    The measured widths of the buttons.
     */
    private void layoutButtons(LinearLayout buttonContainer, List<Button> buttons, List<Integer> buttonWidths) {
        if (buttons.isEmpty()) return;

        // Check if buttons fit in one row.
        int totalWidth = 0;
        for (Integer width : buttonWidths) {
            totalWidth += width;
        }
        if (buttonWidths.size() > 1) {
            // Add margins for gaps.
            totalWidth += (buttonWidths.size() - 1) * Dim.dp8;
        }

        // Single button: stretch to full width.
        if (buttons.size() == 1) {
            layoutSingleButton(buttonContainer, buttons.get(0));
        } else if (totalWidth <= buttonAreaWidth()) {
            // Single row: Neutral, Cancel, OK.
            layoutButtonsInRow(buttonContainer, buttons, buttonWidths);
        } else {
            // Multiple rows: OK, Cancel, Neutral.
            layoutButtonsInColumns(buttonContainer, buttons);
        }
    }

    /**
     * Arranges a single button, stretching it to full width.
     *
     * @param buttonContainer The container for the button.
     * @param button          The button to arrange.
     */
    private void layoutSingleButton(LinearLayout buttonContainer, Button button) {
        LinearLayout singleContainer = new LinearLayout(context);
        singleContainer.setOrientation(LinearLayout.HORIZONTAL);
        singleContainer.setGravity(Gravity.CENTER);

        ViewGroup parent = (ViewGroup) button.getParent();
        if (parent != null) parent.removeView(button);

        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Dim.dp36));
        singleContainer.addView(button);
        buttonContainer.addView(singleContainer);
    }

    /**
     * Arranges buttons in a single horizontal row with proportional widths.
     *
     * @param buttonContainer The container for the buttons.
     * @param buttons         The list of buttons to arrange.
     * @param buttonWidths    The measured widths of the buttons.
     */
    private void layoutButtonsInRow(LinearLayout buttonContainer, List<Button> buttons, List<Integer> buttonWidths) {
        LinearLayout rowContainer = new LinearLayout(context);
        rowContainer.setOrientation(LinearLayout.HORIZONTAL);
        rowContainer.setGravity(Gravity.CENTER);
        rowContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Add all buttons with proportional weights and specific margins.
        for (int i = 0; i < buttons.size(); i++) {
            Button button = getButton(buttons, buttonWidths, i);
            rowContainer.addView(button);
        }

        buttonContainer.addView(rowContainer);
    }

    @NonNull
    private Button getButton(List<Button> buttons, List<Integer> buttonWidths, int i) {
        Button button = buttons.get(i);
        ViewGroup parent = (ViewGroup) button.getParent();
        if (parent != null) parent.removeView(button);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, Dim.dp36, buttonWidths.get(i));

        // Set margins based on button type and combination.
        if (buttons.size() == 2) {
            // Neutral + OK or Cancel + OK.
            params.setMargins(i == 0 ? 0 : Dim.dp4, 0, i == 0 ? Dim.dp4 : 0, 0);
        } else if (buttons.size() == 3) {
            // Neutral.
            // Cancel.
            // OK.
            params.setMargins(i == 0 ? 0 : Dim.dp4, 0, i == 2 ? 0 : Dim.dp4, 0);
        }

        button.setLayoutParams(params);
        return button;
    }

    /**
     * Arranges buttons in separate rows, ordered OK, Cancel, Neutral.
     *
     * @param buttonContainer The container for the buttons.
     * @param buttons         The list of buttons to arrange.
     */
    private void layoutButtonsInColumns(LinearLayout buttonContainer, List<Button> buttons) {
        // Reorder: OK, Cancel, Neutral.
        List<Button> reorderedButtons = new ArrayList<>();
        if (buttons.size() == 3) {
            reorderedButtons.add(buttons.get(2)); // OK
            reorderedButtons.add(buttons.get(1)); // Cancel
            reorderedButtons.add(buttons.get(0)); // Neutral
        } else if (buttons.size() == 2) {
            reorderedButtons.add(buttons.get(1)); // OK or Cancel
            reorderedButtons.add(buttons.get(0)); // Neutral or Cancel
        }

        for (int i = 0; i < reorderedButtons.size(); i++) {
            Button button = reorderedButtons.get(i);
            LinearLayout singleContainer = new LinearLayout(context);
            singleContainer.setOrientation(LinearLayout.HORIZONTAL);
            singleContainer.setGravity(Gravity.CENTER);
            singleContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Dim.dp36));

            ViewGroup parent = (ViewGroup) button.getParent();
            if (parent != null) parent.removeView(button);

            button.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Dim.dp36));
            singleContainer.addView(button);
            buttonContainer.addView(singleContainer);

            // Add a spacer between the buttons (except the last one).
            if (i < reorderedButtons.size() - 1) {
                View spacer = new View(context);
                LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        Dim.dp8);
                spacer.setLayoutParams(spacerParams);
                buttonContainer.addView(spacer);
            }
        }
    }

    /**
     * Creates a styled modern search bar with a functional clear button.
     *
     * @param context Context used to create the EditText.
     * @param hint The placeholder text to display.
     * @param onQueryChanged Callback triggered when the text changes.
     * @return The configured EditText search bar.
     */
    @SuppressLint("ClickableViewAccessibility")
    public static EditText createSearchBar(Context context, String hint, Consumer<String> onQueryChanged) {
        EditText searchBar = new EditText(context);
        searchBar.setTextSize(16);
        searchBar.setHint(hint);
        searchBar.setSingleLine(true);
        searchBar.setTextColor(ThemeUtils.getAppForegroundColor());
        searchBar.setHapticFeedbackEnabled(false);
        searchBar.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        searchBar.setCompoundDrawablePadding(Dim.dp8);
        searchBar.setBackground(createRoundedBackground(20, ThemeUtils.getEditTextBackground()));

        int searchIconResId = ResourceUtils.getIdentifierOrThrow(
                ResourceType.DRAWABLE, "morphe_settings_search_icon_bold");
        int clearIconResId = ResourceUtils.getIdentifierOrThrow(
                ResourceType.DRAWABLE, "morphe_settings_search_remove_bold");

        Drawable searchIcon = context.getDrawable(searchIconResId);
        if (searchIcon != null) {
            searchIcon.setBounds(0, 0, Dim.dp20, Dim.dp20);
            searchIcon.setTint(ThemeUtils.getAppForegroundColor());
        }

        Drawable clearIcon = context.getDrawable(clearIconResId);
        if (clearIcon != null) {
            clearIcon.setBounds(0, 0, Dim.dp20, Dim.dp20);
            clearIcon.setTint(ThemeUtils.getAppForegroundColor());
        }

        searchBar.setCompoundDrawables(searchIcon, null, null, null);

        searchBar.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchBar.setCompoundDrawables(searchIcon, null,
                        TextUtils.isEmpty(s) ? null : clearIcon, null);
                if (onQueryChanged != null) {
                    onQueryChanged.accept(s.toString());
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        searchBar.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                Drawable[] drawables = searchBar.getCompoundDrawables();
                if (drawables[2] != null && event.getRawX() >=
                        (searchBar.getRight() - drawables[2].getBounds().width() - searchBar.getPaddingRight())) {
                    searchBar.setText("");
                    return true;
                }
            }
            return false;
        });

        return searchBar;
    }

    /**
     * Creates a rounded solid color background drawable.
     *
     * @param radiusDp The corner radius in dp.
     * @param color The solid color for the background.
     * @return The configured ShapeDrawable.
     */
    public static ShapeDrawable createRoundedBackground(int radiusDp, int color) {
        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(radiusDp), null, null));
        background.getPaint().setColor(color);
        return background;
    }
}
