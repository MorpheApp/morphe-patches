package app.morphe.util

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.io.File
import java.net.URLClassLoader
import java.util.jar.Manifest

typealias PackageName = String
typealias VersionName = String

internal fun main() {
    val patchFiles = setOf(
        File("build/libs/").listFiles { file ->
            val fileName = file.name
            !fileName.contains("javadoc") &&
                    !fileName.contains("sources") &&
                    fileName.endsWith(".mpp")
        }!!.first()
    )
    val loadedPatches = loadPatchesFromJar(patchFiles)
    val patchClassLoader = URLClassLoader(patchFiles.map { it.toURI().toURL() }.toTypedArray())
    val manifest = patchClassLoader.getResources("META-INF/MANIFEST.MF")

    while (manifest.hasMoreElements()) {
        Manifest(manifest.nextElement().openStream())
            .mainAttributes
            .getValue("Version")
            ?.let {
                generatePatchList(it, loadedPatches)
            }
    }
}

@Suppress("DEPRECATION")
private fun generatePatchList(version: String, patches: Set<Patch<*>>) {
    val listJson = File("../list.json")

    val patchesMap = patches.sortedBy { it.name }.map {
        JsonPatch(
            it.name!!,
            it.description,
            it.use,
            it.dependencies.map { dependency -> dependency.javaClass.simpleName },
            it.compatiblePackages?.associate { (packageName, versions) -> packageName to versions },
            it.options.values.map { option ->
                JsonPatch.Option(
                    option.key,
                    option.title,
                    option.description,
                    option.required,
                    option.type.toString(),
                    option.default,
                    option.values,
                )
            },
        )
    }

    val gsonBuilder = GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    val jsonObject = JsonObject()
    jsonObject.addProperty("version", "v$version")
    jsonObject.add("patches", gsonBuilder.toJsonTree(patchesMap))

    listJson.writeText(
        gsonBuilder.toJson(jsonObject)
    )
}

@Suppress("unused")
private class JsonPatch(
    val name: String? = null,
    val description: String? = null,
    val use: Boolean = true,
    val dependencies: List<String>,
    val compatiblePackages: Map<PackageName, Set<VersionName>?>? = null,
    val options: List<Option>,
) {
    class Option(
        val key: String,
        val title: String?,
        val description: String?,
        val required: Boolean,
        val type: String,
        val default: Any?,
        val values: Map<String, Any?>?,
    )
}

