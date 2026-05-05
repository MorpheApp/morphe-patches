package app.morphe.patches.shared.misc.settings.preference

/**
 * A switch preference.
 *
 * @param key The preference key. If null, other parameters must be specified.
 * @param titleKey The preference title key.
 * @param icon The preference icon resource name.
 * @param layout Layout declaration.
 * @param tag The preference tag.
 * @param summaryKey The preference summary key.
 */
@Suppress("MemberVisibilityCanBePrivate")
class SwitchPreference(
    key: String? = null,
    titleKey: String = "${key}_title",
    summaryKey: String? = "${key}_summary",
    tag: String = "SwitchPreference",
    icon: String? = null,
    iconBold: String? = null,
    layout: String? = null
) : BasePreference(key, titleKey, summaryKey, icon, iconBold, layout, tag)
