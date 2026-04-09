/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.youtube.shared.ConversionContext.ELEMENT_IDENTIFIER_COMPONENT;
import static app.morphe.extension.youtube.shared.ConversionContext.ELEMENT_IDENTIFIER_CONTAINER;

import android.view.View;
import android.widget.ListView;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.NewElement;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class HidePlayerFlyoutMenuPatch {
    /**
     * Advanced video quality bottom sheet body.
     */
    private static final String ADVANCED_VIDEO_QUALITY_BODY_PATH = "advanced_quality_sheet_content.e";
    /**
     * Advanced video quality bottom sheet header.
     */
    private static final String ADVANCED_VIDEO_QUALITY_HEADER_PATH = "quality_sheet_header.e";
    /**
     * Base video quality bottom sheet header.
     */
    private static final String BASE_VIDEO_QUALITY_HEADER_PATH = "quick_quality_sheet_content.e";
    /**
     * Captions bottom sheet body.
     */
    private static final String CAPTIONS_BODY_PATH = "captions_sheet_content.e";
    /**
     * Captions bottom sheet header.
     */
    private static final String CAPTIONS_HEADER_PATH = "bottom_sheet_header.e";

    private static final String[] PLAYER_FLYOUT_HEADER_IDENTIFIERS = new String[]{
            ADVANCED_VIDEO_QUALITY_HEADER_PATH,
            BASE_VIDEO_QUALITY_HEADER_PATH,
            CAPTIONS_HEADER_PATH,
    };

    private static final boolean HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER = Settings.HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER.get();
    private static final boolean HIDE_PLAYER_FLYOUT_CAPTIONS_HEADER = Settings.HIDE_PLAYER_FLYOUT_CAPTIONS_HEADER.get();
    private static final boolean HIDE_PLAYER_FLYOUT_QUALITY_FOOTER = Settings.HIDE_PLAYER_FLYOUT_QUALITY_FOOTER.get();
    private static final boolean HIDE_PLAYER_FLYOUT_QUALITY_HEADER = Settings.HIDE_PLAYER_FLYOUT_QUALITY_HEADER.get();

    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    /**
     * Injection point.
     */
    public static void hideNativeBottomSheetFooter(String path, List<Object> treeNodeResultList) {
        if (HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER || HIDE_PLAYER_FLYOUT_QUALITY_FOOTER) {
            try {
                final int size = treeNodeResultList.size();
                if (size > 2) {
                    boolean shouldRemove = false;
                    if (path.startsWith(CAPTIONS_BODY_PATH) && HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER) {
                        int i = 0;
                        for (Object object : treeNodeResultList) {
                            if (!ELEMENT_IDENTIFIER_COMPONENT.equals(object.toString())) {
                                if (i == size - 1 && ELEMENT_IDENTIFIER_CONTAINER.equals(object.toString())) {
                                    continue;
                                } else {
                                    return;
                                }
                            }
                            i++;
                        }

                        shouldRemove = true;
                    } else if (path.startsWith(ADVANCED_VIDEO_QUALITY_BODY_PATH) && HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER) {
                        for (Object object : treeNodeResultList) {
                            if (!ELEMENT_IDENTIFIER_COMPONENT.equals(object.toString())) {
                                return;
                            }
                        }

                        shouldRemove = true;
                    }

                    if (shouldRemove) {
                        // Hide bottom sheet footer.
                        treeNodeResultList.remove(size - 1);
                        // Hide bottom sheet divider.
                        treeNodeResultList.remove(size - 2);
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "hideNativeBottomSheetFooter failure", ex);
            }
        }
    }

    /**
     * Injection point.
     */
    public static byte[] hideNativeBottomSheetHeader(byte[] bytes) {
        if (HIDE_PLAYER_FLYOUT_CAPTIONS_HEADER || HIDE_PLAYER_FLYOUT_QUALITY_HEADER) {
            try {
                var newElement = NewElement.parseFrom(bytes).toBuilder();
                var identifier = newElement.getProperties().getIdentifierProperties().getIdentifier();

                if (Utils.containsAny(identifier, PLAYER_FLYOUT_HEADER_IDENTIFIERS)) {
                    var type = newElement.getType().toBuilder();
                    var componentType = type.getComponentType().toBuilder();
                    var model = componentType.getModel().toBuilder();
                    if (HIDE_PLAYER_FLYOUT_QUALITY_HEADER && model.hasYoutubeModel()) {
                        var youtubeModel = model.getYoutubeModel().toBuilder();
                        var viewModel = youtubeModel.getViewModel().toBuilder();

                        if (viewModel.hasQuickQualitySheetContentViewModel()) {
                            var quickQualitySheetContentViewModel = viewModel
                                    .getQuickQualitySheetContentViewModel().toBuilder();

                            quickQualitySheetContentViewModel.clearQualityHeader();

                            var newQuickQualitySheetContentViewModel = quickQualitySheetContentViewModel.build();
                            viewModel.clearQuickQualitySheetContentViewModel();
                            viewModel.setQuickQualitySheetContentViewModel(newQuickQualitySheetContentViewModel);

                            var newViewModel = viewModel.build();
                            youtubeModel.clearViewModel();
                            youtubeModel.setViewModel(newViewModel);

                            var newYoutubeModel = youtubeModel.build();
                            model.clearYoutubeModel();
                            model.setYoutubeModel(newYoutubeModel);

                            var newModel = model.build();
                            componentType.clearModel();
                            componentType.setModel(newModel);

                            var newComponentType = componentType.build();
                            type.clearComponentType();
                            type.setComponentType(newComponentType);

                            var newType = type.build();
                            newElement.clearType();
                            newElement.setType(newType);

                            return newElement.build().toByteArray();
                        } else if (viewModel.hasQualitySheetHeaderViewModel()) {
                            return EMPTY_BYTE_ARRAY;
                        }
                    } else if (HIDE_PLAYER_FLYOUT_CAPTIONS_HEADER && model.hasBottomSheetHeaderModel()) {
                        return EMPTY_BYTE_ARRAY;
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "hideNativeBottomSheetHeader failure", ex);
            }
        }

        return bytes;
    }

    /**
     * Injection point.
     */
    public static void hideCaptionsOldBottomSheetFooter(ListView listView, View view, Object object, boolean bool) {
        if (HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER) {
            view = new View(listView.getContext());
        }

        listView.addFooterView(view, object, bool);
    }

    /**
     * Injection point.
     */
    public static View hideCaptionsOldBottomSheetHeader(View parentView, int resId) {
        View headerView = parentView.findViewById(resId);
        Utils.hideViewByRemovingFromParentUnderCondition(
                HIDE_PLAYER_FLYOUT_CAPTIONS_HEADER,
                headerView
        );

        return headerView;
    }

    /**
     * Injection point.
     */
    public static void hideQualityOldBottomSheetFooter(ListView listView, View view, Object object, boolean bool) {
        if (HIDE_PLAYER_FLYOUT_QUALITY_FOOTER) {
            view = new View(listView.getContext());
        }

        listView.addFooterView(view, object, bool);
    }

    /**
     * Injection point.
     */
    public static void hideQualityOldBottomSheetHeader(ListView listView, View view, Object object, boolean bool) {
        if (HIDE_PLAYER_FLYOUT_QUALITY_HEADER) {
            view = new View(listView.getContext());
        }

        listView.addHeaderView(view, object, bool);
    }
}