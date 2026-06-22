package app.morphe.patches.shared.misc.settings.preference

/**
 * A switch preference.
 *
 * @param key The preference key. If null, other parameters must be specified.
 * @param titleKey The preference title key.
 * @param icon The preference icon resource name.
 * @param layout Layout declaration.
 * @param tag The preference tag.
 * @param summary Whether to use a summary.
 */
@Suppress("MemberVisibilityCanBePrivate")
class SwitchPreference(
    key: String? = null,
    titleKey: String = "${key}_title",
    summary: Boolean = false,
    tag: String = "SwitchPreference",
    icon: String? = null,
    iconBold: String? = null,
    layout: String? = null
) : BasePreference(key, titleKey, if (summary) "${key}_summary" else null, icon, iconBold, layout, tag)
