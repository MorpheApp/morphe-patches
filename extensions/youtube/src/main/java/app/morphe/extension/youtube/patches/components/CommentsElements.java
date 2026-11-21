/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5097
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.CarouselItemDatas;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.ChipBarModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.FilterOptions;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.Model;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.NewElement;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SortOptions;

/**
 * Changes the elements of the comments, such as the filter bar and the carousel below the video.
 */
final class CommentsElements {

    private CommentsElements() {
    }

    /**
     * Removes the 'Topics' option of the comments filter bar, so the comments are never grouped by topic.
     * The filter bar is the element itself or one of its child elements.
     *
     * @return The element without the option, or null if it has no such option.
     */
    @Nullable
    static NewElement removeTopicsFilter(NewElement element) {
        Model model = element.getType().getComponentType().getModel();
        if (model.hasChipBarModel()) {
            ChipBarModel chipBar = removeTopicsFilter(model.getChipBarModel());
            return chipBar == null
                    ? null
                    : setModel(element, model.toBuilder().setChipBarModel(chipBar).build());
        }

        NewElement.Builder builder = null;
        for (int i = 0, count = element.getChildElementsCount(); i < count; i++) {
            NewElement child = removeTopicsFilter(element.getChildElements(i));
            if (child != null) {
                if (builder == null) {
                    builder = element.toBuilder();
                }
                builder.setChildElements(i, child);
            }
        }
        return builder == null ? null : builder.build();
    }

    @Nullable
    private static ChipBarModel removeTopicsFilter(ChipBarModel chipBar) {
        final int defaultIndex = chipBar.getDefaultFilterIndex();
        int newDefaultIndex = defaultIndex;
        // Index of the option over all sort options, as the default index counts them all.
        int optionIndex = 0;
        ChipBarModel.Builder builder = null;

        for (int i = 0, sortCount = chipBar.getSortOptionsCount(); i < sortCount; i++) {
            SortOptions sortOptions = chipBar.getSortOptions(i);
            SortOptions.Builder sortOptionsBuilder = null;
            int removed = 0;

            for (int j = 0, optionCount = sortOptions.getFilterOptionsCount(); j < optionCount; j++, optionIndex++) {
                if (!isTopicsOption(sortOptions.getFilterOptions(j))) {
                    continue;
                }

                Logger.printDebug(() -> "Removing comments Topics filter");
                if (sortOptionsBuilder == null) {
                    sortOptionsBuilder = sortOptions.toBuilder();
                }
                sortOptionsBuilder.removeFilterOptions(j - removed);
                removed++;

                if (optionIndex == defaultIndex) {
                    // Use the first option, usually 'Top'.
                    newDefaultIndex = 0;
                } else if (optionIndex < defaultIndex) {
                    newDefaultIndex--;
                }
            }

            if (sortOptionsBuilder != null) {
                if (builder == null) {
                    builder = chipBar.toBuilder();
                }
                builder.setSortOptions(i, sortOptionsBuilder);
            }
        }

        return builder == null
                ? null
                : builder.setDefaultFilterIndex(newDefaultIndex).build();
    }

    private static boolean isTopicsOption(FilterOptions option) {
        return option.getOnSelected().getApplyFilterCommand().getFilterFormData()
                .getCommentsFilterFormData().hasCommentsTopicParams();
    }

    /**
     * Removes the 'Topic' previews from the comment previews of the carousel below the video,
     * and the carousel items whose title includes any of the hidden titles.
     *
     * @return The changed element, or null if nothing is removed.
     */
    @Nullable
    static NewElement filterCarousel(NewElement element, boolean hideTopicPreviews, List<String> hiddenTitles) {
        Model model = element.getType().getComponentType().getModel();
        if (!model.hasVideoMetadataCarouselModel()) {
            return null;
        }

        var carousel = model.getVideoMetadataCarouselModel();
        var data = carousel.getData().toBuilder();
        boolean modified = false;

        if (hideTopicPreviews) {
            for (int i = 0, count = data.getCarouselItemDatasCount(); i < count; i++) {
                CarouselItemDatas item = removeTopicPreviews(data.getCarouselItemDatas(i));
                if (item != null) {
                    data.setCarouselItemDatas(i, item);
                    modified = true;
                }
            }
        }

        if (!hiddenTitles.isEmpty()) {
            // The items and their titles have the same index.
            for (int i = data.getCarouselTitleDatasCount() - 1; i >= 0; i--) {
                String title = data.getCarouselTitleDatas(i).getTitle();
                Logger.printDebug(() -> "Comments carousel title: " + title);

                for (String hiddenTitle : hiddenTitles) {
                    if (title.contains(hiddenTitle)) {
                        data.removeCarouselItemDatas(i);
                        data.removeCarouselTitleDatas(i);
                        modified = true;
                        break;
                    }
                }
            }
        }

        if (!modified) {
            return null;
        }

        return setModel(element, model.toBuilder()
                .setVideoMetadataCarouselModel(carousel.toBuilder().setData(data))
                .build());
    }

    /**
     * Tapping a 'Topic' preview opens the comments grouped by that topic.
     *
     * @return The item without its 'Topic' previews, or null if it has none.
     */
    @Nullable
    private static CarouselItemDatas removeTopicPreviews(CarouselItemDatas item) {
        var viewModel = item.getCarouselItemViewModel();
        if (!viewModel.hasCommentTeaserCarouselItemViewModel()) {
            return null;
        }

        var teaserItem = viewModel.getCommentTeaserCarouselItemViewModel().toBuilder();
        final int teaserCount = teaserItem.getTeasersCount();
        for (int i = teaserCount - 1; i >= 0; i--) {
            // Unlike the comment previews, a 'Topic' preview has no author.
            if (!teaserItem.getTeasers(i).getCommentsEntryPointTeaserViewModel().hasAuthor()) {
                teaserItem.removeTeasers(i);
            }
        }

        // Keep previews without an author when they are all there is, as they are then no
        // 'Topic' previews shown next to comments, and the item would be empty without them.
        final int newTeaserCount = teaserItem.getTeasersCount();
        if (newTeaserCount == teaserCount || newTeaserCount == 0) {
            return null;
        }

        Logger.printDebug(() -> "Removing " + (teaserCount - newTeaserCount) + " comments Topic previews");
        return item.toBuilder().setCarouselItemViewModel(viewModel.toBuilder()
                .setCommentTeaserCarouselItemViewModel(teaserItem)).build();
    }

    private static NewElement setModel(NewElement element, Model model) {
        var type = element.getType();
        return element.toBuilder().setType(type.toBuilder().setComponentType(
                type.getComponentType().toBuilder().setModel(model))).build();
    }
}
