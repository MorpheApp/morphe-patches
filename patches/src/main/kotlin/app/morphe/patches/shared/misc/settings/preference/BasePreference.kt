/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.settings.preference

import app.morphe.patches.util.resource.BaseResource
import app.morphe.patcher.xml.XmlElement

/**
 * Base preference class for all preferences.
 *
 * @param key The key of the preference. If null, other parameters must be specified.
 * @param titleKey The key of the preference title.
 * @param icon The preference icon resource name.
 * @param layout Layout declaration.
 * @param summaryKey The key of the preference summary.
 * @param tag The tag or full class name of the preference.
 */
abstract class BasePreference(
    val key: String? = null,
    val titleKey: String? = "${key}_title",
    val summaryKey: String? = "${key}_summary",
    icon: String? = null,
    iconBold: String? = null,
    layout: String? = null,
    val tag: String
) {

    var icon: String? = icon
        internal set

    var iconBold: String? = iconBold
        internal set

    var layout: String? = layout
        internal set

    /**
     * Serialize preference element to XML.
     * Overriding methods should invoke super and operate on its return value.
     *
     * @param parent The element to add the preference to.
     * @param index The position among the child elements of [parent] to add the preference at.
     * @param resourceCallback A callback for additional resources.
     *
     * @return The serialized element.
     */
    open fun serialize(parent: XmlElement, index: Int, resourceCallback: (BaseResource) -> Unit): XmlElement =
        parent.insertChild(index, tag) {
            key?.let { setAttribute("android:key", it) }
            titleKey?.let { setAttribute("android:title", "@string/$it") }
            summaryKey?.let { setAttribute("android:summary", "@string/$it") }

            (icon ?: iconBold)?.let { icon ->
                setAttribute("android:icon", icon)
                setAttribute("app:iconSpaceReserved", "true")
            }
            layout?.let { setAttribute("android:layout", it) }
        }

}
