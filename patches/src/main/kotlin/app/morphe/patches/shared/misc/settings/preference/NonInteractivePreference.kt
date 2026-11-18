package app.morphe.patches.shared.misc.settings.preference

import app.morphe.patches.util.resource.BaseResource
import app.morphe.patcher.xml.XmlElement

/**
 * A non-interactive preference.
 *
 * Typically used to present static text, but also used for custom extension code that responds to taps.
 *
 * @param key The preference key.
 * @param summaryKey The preference summary key.
 * @param icon The preference icon resource name.
 * @param layout Layout declaration.
 * @param tag The tag or full class name of the preference.
 * @param selectable If the preference is selectable and responds to tap events.
 */
class NonInteractivePreference(
    key: String,
    titleKey: String? = "${key}_title",
    summaryKey: String? = "${key}_summary",
    icon: String? = null,
    iconBold: String? = null,
    layout: String? = null,
    tag: String = "Preference",
    val selectable: Boolean = false,
    val dependency: String? = null,
) : BasePreference(key, titleKey, summaryKey, icon, iconBold, layout, tag) {
    override fun serialize(parent: XmlElement, index: Int, resourceCallback: (BaseResource) -> Unit) =
        super.serialize(parent, index, resourceCallback).apply {
            setAttribute("android:selectable", selectable.toString())
            dependency?.let { setAttribute("android:dependency", it) }
        }
}
