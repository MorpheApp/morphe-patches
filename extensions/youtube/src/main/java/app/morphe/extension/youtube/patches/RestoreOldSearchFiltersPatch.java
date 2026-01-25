package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.NonNull;

import com.google.protobuf.MessageLite;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.SearchResponseOuterClass;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class RestoreOldSearchFiltersPatch {

    /**
     * Search filters to restore (label and formValue).
     */
    private static final String[][] OLD_SEARCH_FILTERS = {
            { str("morphe_old_search_filters_sort_by_rating"), "sort_by=sort_by_rating" },
            { str("morphe_old_search_filters_sort_by_upload_date"), "sort_by=sort_by_upload_date" },
    };

    private static final boolean RESTORE_OLD_SEARCH_FILTERS = Settings.RESTORE_OLD_SEARCH_FILTERS.get();

    /**
     * The last formValue used in a request to the '/search' endpoint.
     * Currently, the patch only targets the 'Prioritize' group, so only values starting with 'sort_by' are stored.
     */
    @NonNull
    private static volatile String lastFormValue = "";

    /**
     * Injection point.
     *
     * @param renderer  'header.searchMobileHeaderRenderer.filters.simpleSearchFilterGroupRenderer' in the '/search' endpoint response.
     *                  In the Android YouTube app, it is handled as protobuf and the data is parsed by the proto builder.
     * @return          Byte array of modified renderer.
     */
    public static byte[] getSearchFilterGroupRenderer(Object renderer) {
        if (RESTORE_OLD_SEARCH_FILTERS
                && renderer instanceof MessageLite messageLite) {
            try {
                var rootBuilder =
                        SearchResponseOuterClass.SimpleSearchFilterGroupRenderer.parseFrom(messageLite.toByteArray()).toBuilder();
                boolean modified = false;

                for (int i = 0; i < rootBuilder.getSearchFilterGroupsCount(); i++) {
                    var groupBuilder = rootBuilder.getSearchFilterGroups(i).toBuilder();
                    boolean isPrioritizeGroup = false;

                    // Check if the group is 'Prioritize' (a group that includes 'sort_by').
                    for (var option : groupBuilder.getSearchFilterOptionsList()) {
                        String formValue = option.getFormValue();
                        if (formValue != null && formValue.startsWith("sort_by")) {
                            isPrioritizeGroup = true;
                            break;
                        }
                    }

                    // If the 'Prioritize' group is found, add old search filters.
                    if (isPrioritizeGroup) {
                        for (String[] searchFilter : OLD_SEARCH_FILTERS) {
                            String simpleText = searchFilter[0];
                            String formValue = searchFilter[1];

                            boolean exists = groupBuilder.getSearchFilterOptionsList().stream()
                                    .anyMatch(o -> formValue.equals(o.getFormValue()));

                            // Add old search filters only if they do not exist in the 'Prioritize' group.
                            if (!exists) {
                                var label = SearchResponseOuterClass.Label.newBuilder()
                                        .setSimpleText(simpleText)
                                        .build();

                                groupBuilder.addSearchFilterOptions(SearchResponseOuterClass.SearchFilterOption.newBuilder()
                                        .setLabel(label)
                                        .setStatus(SearchResponseOuterClass.FilterStatus.SEARCH_FILTER_STATUS_DESELECTED)
                                        .setFormValue(formValue)
                                        .build());

                                Logger.printDebug(() -> "Added search filter: " + simpleText);
                            }
                        }

                        // Since the original renderer is derived from the endpoint response, the selected filter may be incorrect.
                        // Change the filter's selection state based on the last used formValue.
                        if (!lastFormValue.isEmpty()) {
                            for (int j = 0; j < groupBuilder.getSearchFilterOptionsCount(); j++) {
                                var optionBuilder = groupBuilder.getSearchFilterOptions(j).toBuilder();
                                String formValue = optionBuilder.getFormValue();

                                // SELECTED if it matches the last used formValue, otherwise DESELECTED.
                                var filterState = lastFormValue.equals(formValue)
                                        ? SearchResponseOuterClass.FilterStatus.SEARCH_FILTER_STATUS_SELECTED
                                        : SearchResponseOuterClass.FilterStatus.SEARCH_FILTER_STATUS_DESELECTED;

                                optionBuilder.setStatus(filterState);
                                groupBuilder.setSearchFilterOptions(j, optionBuilder.build());
                            }
                        }

                        rootBuilder.setSearchFilterGroups(i, groupBuilder.build());
                        modified = true;
                        break;
                    }
                }

                if (modified) {
                    // Returns a byte array to re-parse the renderer if a search filter is added.
                    return rootBuilder.build().toByteArray();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to restore old search filters", ex);
            }
        }

        return null;
    }

    /**
     * Injection point.
     * <p>
     * If the search filter hasn't been modified or all values are default,
     * {@link #setLastFormValue(byte[])} may not be invoked.
     * To prevent unintended behavior, set lastFormValue to an empty value
     * when the search request parameter builder is initialized.
     */
    public static void setLastFormValue() {
        lastFormValue = "";
    }

    /**
     * Injection point.
     *
     * @param data  Byte array of searchFormData.
     */
    public static void setLastFormValue(byte[] data) {
        if (RESTORE_OLD_SEARCH_FILTERS && data != null) {
            try {
                var searchFormData = SearchResponseOuterClass.SearchFormData.parseFrom(data);
                if (searchFormData != null) {
                    for (String formValue : searchFormData.getFormValueList()) {
                        if (formValue != null && formValue.startsWith("sort_by")) {
                            lastFormValue = formValue;
                            return;
                        }
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to parse formValues", ex);
            }
        }
    }
}
