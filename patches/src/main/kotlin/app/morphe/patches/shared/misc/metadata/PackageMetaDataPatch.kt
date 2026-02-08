package app.morphe.patches.shared.misc.metadata

import app.morphe.patcher.PackageMetadata
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import kotlin.properties.Delegates
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

var packageName : String by Delegates.notNull()
    private set
var packageVersion : String by Delegates.notNull()
    private set
var packageVersionSimplified : Int by Delegates.notNull()
    private set

fun isGreaterThan(targetVersion: Int) =
    targetVersion <= packageVersionSimplified

// TODO: Change the access modifier for packageMetadata in ResourcePatchContext and no longer use Kotlin reflection.
val packageMetaDataPatch = resourcePatch(
    description = "Get PackageMetadata using Kotlin reflection."
) {
    execute {
        val property = ResourcePatchContext::class.memberProperties
            .find { it.name == "packageMetadata" }

        property!!.isAccessible = true
        val packageMetadata = property.get(this) as PackageMetadata

        // com.google.android.youtube
        packageName = packageMetadata.packageName

        // 20.40.45
        packageVersion = packageMetadata.packageVersion

        // 2040
        packageVersionSimplified = packageVersion.split(".")
            .take(2)
            .joinToString("")
            .toInt()
    }
}
