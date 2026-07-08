@file:Suppress("ktlint:standard:property-naming")

package app.morphe.patches.music.misc.playservice

import app.morphe.patcher.patch.bytecodePatch
import kotlin.properties.Delegates

// Use notNull delegate so an exception is thrown if these fields are accessed before they are set.

var is_7_16_or_greater: Boolean by Delegates.notNull()
    private set
var is_7_33_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_03_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_05_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_10_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_11_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_15_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_40_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_41_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_50_or_greater: Boolean by Delegates.notNull()
    private set
var is_8_51_or_greater: Boolean by Delegates.notNull()
    private set
var is_9_00_or_greater: Boolean by Delegates.notNull()
    private set
var is_9_03_or_greater: Boolean by Delegates.notNull()
    private set
var is_9_10_or_greater: Boolean by Delegates.notNull()
    private set
var is_9_19_or_greater: Boolean by Delegates.notNull()
    private set
var is_9_20_or_greater: Boolean by Delegates.notNull()
    private set
var is_9_24_or_greater: Boolean by Delegates.notNull()
    private set
var is_9_26_or_greater: Boolean by Delegates.notNull()
    private set

val versionCheckPatch = bytecodePatch {
    execute {
        val versionName = packageMetadata.versionName
        fun isEqualsOrGreaterThan(version: String): Boolean {
            return versionName >= version
        }

        is_7_16_or_greater = isEqualsOrGreaterThan("7.16.00")
        is_7_33_or_greater = isEqualsOrGreaterThan("7.33.00")
        is_8_03_or_greater = isEqualsOrGreaterThan("8.03.00")
        is_8_05_or_greater = isEqualsOrGreaterThan("8.05.00")
        is_8_10_or_greater = isEqualsOrGreaterThan("8.10.00")
        is_8_11_or_greater = isEqualsOrGreaterThan("8.11.00")
        is_8_15_or_greater = isEqualsOrGreaterThan("8.15.00")
        is_8_40_or_greater = isEqualsOrGreaterThan("8.40.00")
        is_8_41_or_greater = isEqualsOrGreaterThan("8.41.00")
        is_8_50_or_greater = isEqualsOrGreaterThan("8.50.00")
        is_8_51_or_greater = isEqualsOrGreaterThan("8.51.00")
        is_9_00_or_greater = isEqualsOrGreaterThan("9.00.00")
        is_9_03_or_greater = isEqualsOrGreaterThan("9.03.00")
        is_9_10_or_greater = isEqualsOrGreaterThan("9.10.00")
        is_9_19_or_greater = isEqualsOrGreaterThan("9.19.00")
        is_9_20_or_greater = isEqualsOrGreaterThan("9.20.00")
        is_9_24_or_greater = isEqualsOrGreaterThan("9.24.00")
        is_9_26_or_greater = isEqualsOrGreaterThan("9.26.00")
    }
}
