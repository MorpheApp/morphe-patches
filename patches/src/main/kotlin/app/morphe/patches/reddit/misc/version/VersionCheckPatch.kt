@file:Suppress("ktlint:standard:property-naming")

package app.morphe.patches.reddit.misc.version

import app.morphe.patcher.patch.bytecodePatch
import kotlin.properties.Delegates

// Use notNull delegate so an exception is thrown if these fields are accessed before they are set.

var is_2025_48_0_or_greater: Boolean by Delegates.notNull()
    private set
var is_2026_11_0_or_greater: Boolean by Delegates.notNull()
    private set

val versionCheckPatch = bytecodePatch {
    execute {
        val versionName = packageMetadata.versionName
        fun isEqualsOrGreaterThan(version: String): Boolean {
            return versionName >= version
        }

        is_2025_48_0_or_greater = isEqualsOrGreaterThan("2025.48.0")
        is_2026_11_0_or_greater = isEqualsOrGreaterThan("2026.11.0")
    }
}
