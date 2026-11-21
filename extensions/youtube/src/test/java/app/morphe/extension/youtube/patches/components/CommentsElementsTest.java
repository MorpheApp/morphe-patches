/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5097
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.youtube.innertube.NextResponseOuterClass.ApplyFilterCommand;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.CarouselItemDatas;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.CarouselItemViewModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.CarouselTitleDatas;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.ChipBarModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.CommentTeaserAuthor;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.CommentTeaserCarouselItemViewModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.CommentTeasers;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.CommentsEntryPointTeaserViewModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.CommentsFilterFormData;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.CommentsTopicParams;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.ComponentType;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.Data;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.FilterFormData;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.FilterOnSelected;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.FilterOptions;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.Model;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.NewElement;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SortOptions;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.Type;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.VideoMetadataCarouselModel;

public class CommentsElementsTest {

    private static FilterOptions option(boolean topics) {
        var formData = CommentsFilterFormData.newBuilder();
        if (topics) {
            formData.setCommentsTopicParams(CommentsTopicParams.getDefaultInstance());
        }
        return FilterOptions.newBuilder().setOnSelected(FilterOnSelected.newBuilder()
                .setApplyFilterCommand(ApplyFilterCommand.newBuilder().setFilterFormData(
                        FilterFormData.newBuilder().setCommentsFilterFormData(formData)))).build();
    }

    private static NewElement element(Model model) {
        return NewElement.newBuilder().setType(Type.newBuilder().setComponentType(
                ComponentType.newBuilder().setModel(model))).build();
    }

    private static NewElement chipBar(int defaultIndex, FilterOptions... options) {
        var sortOptions = SortOptions.newBuilder();
        for (FilterOptions option : options) {
            sortOptions.addFilterOptions(option);
        }
        return element(Model.newBuilder().setChipBarModel(ChipBarModel.newBuilder()
                .addSortOptions(sortOptions).setDefaultFilterIndex(defaultIndex)).build());
    }

    private static ChipBarModel chipBarOf(NewElement element) {
        return element.getType().getComponentType().getModel().getChipBarModel();
    }

    @Test
    public void removesTopicsOptionBeforeDefault() throws Exception {
        var element = chipBar(2, option(false), option(true), option(false));

        var result = CommentsElements.removeTopicsFilter(NewElement.parseFrom(element.toByteArray()));

        assertNotNull(result);
        var chipBar = chipBarOf(result);
        assertEquals(2, chipBar.getSortOptions(0).getFilterOptionsCount());
        assertEquals(1, chipBar.getDefaultFilterIndex());
    }

    @Test
    public void defaultTopicsOptionFallsBackToFirst() {
        var result = CommentsElements.removeTopicsFilter(chipBar(1, option(false), option(true)));

        assertNotNull(result);
        assertEquals(1, chipBarOf(result).getSortOptions(0).getFilterOptionsCount());
        assertEquals(0, chipBarOf(result).getDefaultFilterIndex());
    }

    @Test
    public void removesTopicsOptionOfNestedChild() {
        var header = NewElement.newBuilder()
                .addChildElements(element(Model.getDefaultInstance()))
                .addChildElements(NewElement.newBuilder().addChildElements(chipBar(0, option(false), option(true))))
                .build();

        var result = CommentsElements.removeTopicsFilter(header);

        assertNotNull(result);
        assertEquals(1, chipBarOf(result.getChildElements(1).getChildElements(0))
                .getSortOptions(0).getFilterOptionsCount());
    }

    @Test
    public void keepsChipBarWithoutTopics() {
        assertNull(CommentsElements.removeTopicsFilter(chipBar(0, option(false), option(false))));
    }

    private static CommentTeasers teaser(boolean author) {
        var viewModel = CommentsEntryPointTeaserViewModel.newBuilder();
        if (author) {
            viewModel.setAuthor(CommentTeaserAuthor.getDefaultInstance());
        }
        return CommentTeasers.newBuilder().setCommentsEntryPointTeaserViewModel(viewModel).build();
    }

    private static NewElement carousel(String[] titles, CommentTeasers... teasers) {
        var data = Data.newBuilder();
        for (String title : titles) {
            data.addCarouselTitleDatas(CarouselTitleDatas.newBuilder().setTitle(title));
            var teaserItem = CommentTeaserCarouselItemViewModel.newBuilder();
            if (title.equals("Comments")) {
                for (CommentTeasers teaser : teasers) {
                    teaserItem.addTeasers(teaser);
                }
            }
            data.addCarouselItemDatas(CarouselItemDatas.newBuilder().setCarouselItemViewModel(
                    CarouselItemViewModel.newBuilder().setCommentTeaserCarouselItemViewModel(teaserItem)));
        }
        return element(Model.newBuilder().setVideoMetadataCarouselModel(
                VideoMetadataCarouselModel.newBuilder().setData(data)).build());
    }

    private static Data dataOf(NewElement element) {
        return element.getType().getComponentType().getModel().getVideoMetadataCarouselModel().getData();
    }

    @Test
    public void removesTopicPreviews() {
        var element = carousel(new String[]{"Comments"}, teaser(true), teaser(false), teaser(true));

        var result = CommentsElements.filterCarousel(element, true, Collections.emptyList());

        assertNotNull(result);
        assertEquals(2, dataOf(result).getCarouselItemDatas(0).getCarouselItemViewModel()
                .getCommentTeaserCarouselItemViewModel().getTeasersCount());
    }

    @Test
    public void keepsPreviewsWithoutAnyAuthor() {
        var element = carousel(new String[]{"Comments"}, teaser(false));

        assertNull(CommentsElements.filterCarousel(element, true, Collections.emptyList()));
    }

    @Test
    public void filtersTitlesAndTopicPreviews() {
        var element = carousel(new String[]{"Comments", "Ask"}, teaser(true), teaser(false));

        var result = CommentsElements.filterCarousel(element, true, List.of("Ask"));

        assertNotNull(result);
        var data = dataOf(result);
        assertEquals(1, data.getCarouselTitleDatasCount());
        assertEquals(1, data.getCarouselItemDatasCount());
        assertEquals("Comments", data.getCarouselTitleDatas(0).getTitle());
        assertEquals(1, data.getCarouselItemDatas(0).getCarouselItemViewModel()
                .getCommentTeaserCarouselItemViewModel().getTeasersCount());
    }

    @Test
    public void keepsTopicPreviewsWhenSettingIsOff() {
        var element = carousel(new String[]{"Comments"}, teaser(true), teaser(false));

        assertNull(CommentsElements.filterCarousel(element, false, List.of("Ask")));
    }
}
