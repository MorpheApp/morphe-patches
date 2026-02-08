@file:Suppress("ktlint:standard:property-naming")

package app.morphe.patches.youtube.misc.playservice

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.metadata.isGreaterThan
import app.morphe.patches.shared.misc.metadata.packageMetaDataPatch
import kotlin.properties.Delegates

// Use notNull delegate so an exception is thrown if these fields are accessed before they are set.

@Deprecated("20.14.43 is the lowest supported version")
var is_19_17_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_18_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_23_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_25_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_26_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_29_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_32_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_33_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_34_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_35_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_36_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_41_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_43_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_46_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_47_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_19_49_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_02_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_03_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_05_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_06_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_07_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_09_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_10_or_greater : Boolean by Delegates.notNull()
    private set
@Deprecated("20.14.43 is the lowest supported version")
var is_20_14_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_15_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_19_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_20_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_21_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_22_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_26_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_28_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_29_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_30_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_31_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_34_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_37_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_38_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_39_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_40_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_41_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_43_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_45_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_46_or_greater : Boolean by Delegates.notNull()
    private set
var is_20_49_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_02_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_03_or_greater : Boolean by Delegates.notNull()
    private set
var is_21_05_or_greater : Boolean by Delegates.notNull()
    private set

val versionCheckPatch = resourcePatch(
    description = "Find the major/minor version of the YouTube target app.",
) {
    dependsOn(packageMetaDataPatch)

    execute {
        is_19_17_or_greater = isGreaterThan(1917)
        is_19_18_or_greater = isGreaterThan(1918)
        is_19_23_or_greater = isGreaterThan(1923)
        is_19_25_or_greater = isGreaterThan(1925)
        is_19_26_or_greater = isGreaterThan(1926)
        is_19_29_or_greater = isGreaterThan(1929)
        is_19_32_or_greater = isGreaterThan(1932)
        is_19_33_or_greater = isGreaterThan(1933)
        is_19_34_or_greater = isGreaterThan(1934)
        is_19_35_or_greater = isGreaterThan(1935)
        is_19_36_or_greater = isGreaterThan(1936)
        is_19_41_or_greater = isGreaterThan(1941)
        is_19_43_or_greater = isGreaterThan(1943)
        is_19_46_or_greater = isGreaterThan(1946)
        is_19_47_or_greater = isGreaterThan(1947)
        is_19_49_or_greater = isGreaterThan(1949)
        is_20_02_or_greater = isGreaterThan(2002)
        is_20_03_or_greater = isGreaterThan(2003)
        is_20_05_or_greater = isGreaterThan(2005)
        is_20_06_or_greater = isGreaterThan(2006)
        is_20_07_or_greater = isGreaterThan(2007)
        is_20_09_or_greater = isGreaterThan(2009)
        is_20_10_or_greater = isGreaterThan(2010)
        is_20_14_or_greater = isGreaterThan(2014)
        is_20_15_or_greater = isGreaterThan(2015)
        is_20_19_or_greater = isGreaterThan(2019)
        is_20_20_or_greater = isGreaterThan(2020)
        is_20_21_or_greater = isGreaterThan(2021)
        is_20_22_or_greater = isGreaterThan(2022)
        is_20_26_or_greater = isGreaterThan(2026)
        is_20_28_or_greater = isGreaterThan(2028)
        is_20_29_or_greater = isGreaterThan(2029)
        is_20_30_or_greater = isGreaterThan(2030)
        is_20_31_or_greater = isGreaterThan(2031)
        is_20_34_or_greater = isGreaterThan(2034)
        is_20_37_or_greater = isGreaterThan(2037)
        is_20_38_or_greater = isGreaterThan(2038)
        is_20_39_or_greater = isGreaterThan(2039)
        is_20_40_or_greater = isGreaterThan(2040)
        is_20_41_or_greater = isGreaterThan(2041)
        is_20_43_or_greater = isGreaterThan(2043)
        is_20_45_or_greater = isGreaterThan(2045)
        is_20_46_or_greater = isGreaterThan(2046)
        is_20_49_or_greater = isGreaterThan(2049)
        is_21_02_or_greater = isGreaterThan(2102)
        is_21_03_or_greater = isGreaterThan(2103)
        is_21_05_or_greater = isGreaterThan(2105)
    }
}
