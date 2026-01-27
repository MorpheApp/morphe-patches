package app.morphe.patches.shared

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.getLastAttributeId
import app.morphe.util.getNode

internal val modifyIds = resourcePatch(
    name = "Modify resource IDs. Temporary patch to experiment with arsclib."
) {
    execute {
        // Didn't feel like parsing all the XMLs, so just hardcoding the IDs for now.
        val ids = listOf(
            "morphe_settings_search_tips_summary",
            "morphe_video_quality_dialog_button_container",
            "morphe_sb_skip_sponsor_button_container",
            "morphe_sb_skip_sponsor_button_text",
            "morphe_sb_skip_sponsor_button_icon",
            "morphe_sb_skip_highlight_button",
            "morphe_sb_skip_sponsor_button",
            "morphe_sb_new_segment_view",
            "morphe_sb_voting_button",
            "morphe_sb_create_segment_button",
            "morphe_sb_new_segment_container",
            "morphe_sb_new_segment_rewind",
            "morphe_sb_new_segment_forward",
            "morphe_sb_new_segment_adjust",
            "morphe_sb_new_segment_compare",
            "morphe_sb_new_segment_edit",
            "morphe_sb_new_segment_publish",
            "preference_title",
            "preference_summary",
            "preference_path",
            "morphe_toolbar_parent",
            "morphe_toolbar",
            "morphe_search_view_container",
            "morphe_search_view",
            "morphe_settings_fragments",
            "morphe_check_icon",
            "morphe_check_icon_placeholder",
            "morphe_item_text",
            "preference_color_dot",
            "history_icon",
            "history_text",
            "delete_icon",
            "empty_history_title",
            "empty_history_summary",
            "search_history_header",
            "search_history_list",
            "clear_history_button",
            "search_tips_card",
            "preference_switch",
            "morphe_color_picker_view",
            "youtube_controls_bottom_ui_container",
            "morphe_playback_speed_dialog_button_container",
            "morphe_playback_speed_dialog_button",
            "morphe_playback_speed_dialog_button_text",
            "morphe_video_quality_dialog_button",
            "morphe_video_quality_dialog_button_text",
            "morphe_external_download_button",
            "morphe_copy_video_url_button",
            "morphe_copy_video_url_timestamp_button",
            "morphe_loop_video_button",
            "action_search",

        )

        document("resources/package_1/res/values/public.xml").use { publicDoc ->
            val publicNode = publicDoc.getNode("resources")
            fun addAttributeReference(idName: String, id: Int) {
                val item = publicDoc.createElement("public")
                item.setAttribute("id", "0x${id.toString(16)}")
                item.setAttribute("type", "id")
                item.setAttribute("name", idName)
                publicNode.appendChild(item)
            }

            var attributeId = publicNode.getLastAttributeId("id") + 1
            ids.forEachIndexed { index, id ->
                addAttributeReference(id, attributeId + 1 + index)
            }
        }

        document("resources/package_1/res/values/ids.xml").use { idsDoc ->
            val resourcesNode = idsDoc.getNode("resources")
            ids.forEach { idName ->
                val item = idsDoc.createElement("id")
                item.setAttribute("name", idName)
                resourcesNode.appendChild(item)
            }
        }
    }
}