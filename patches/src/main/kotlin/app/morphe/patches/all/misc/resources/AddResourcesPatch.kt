package app.morphe.patches.all.misc.resources

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.Document
import app.morphe.util.*
import app.morphe.util.resource.ArrayResource
import app.morphe.util.resource.BaseResource
import app.morphe.util.resource.StringResource
import org.w3c.dom.Node

private typealias AppId = String
private typealias AppResources = MutableSet<BaseResource>
private typealias Value = String

private val locales = mapOf(
    "af-rZA" to "af",
    "am-rET" to "am",
    "ar-rSA" to "ar",
    "as-rIN" to "as",
    "az-rAZ" to "az",
    "be-rBY" to "be",
    "bg-rBG" to "bg",
    "bn-rBD" to "bn",
    "bs-rBA" to "bs",
    "ca-rES" to "ca",
    "cs-rCZ" to "cs",
    "da-rDK" to "da",
    "de-rDE" to "de",
    "el-rGR" to "el",
    "es-rES" to "es",
    "et-rEE" to "et",
    "eu-rES" to "eu",
    "fa-rIR" to "fa",
    "fi-rFI" to "fi",
    "fil-rPH" to "tl",
    "fr-rFR" to "fr",
    "ga-rIE" to "ga",
    "gl-rES" to "gl",
    "gu-rIN" to "gu",
    "hi-rIN" to "hi",
    "hr-rHR" to "hr",
    "hu-rHU" to "hu",
    "hy-rAM" to "hy",
    "in-rID" to "in",
    "is-rIS" to "is",
    "it-rIT" to "it",
    "iw-rIL" to "iw",
    "ja-rJP" to "ja",
    "ka-rGE" to "ka",
    "kk-rKZ" to "kk",
    "km-rKH" to "km",
    "kn-rIN" to "kn",
    "ko-rKR" to "ko",
    "ky-rKG" to "ky",
    "lo-rLA" to "lo",
    "lt-rLT" to "lt",
    "lv-rLV" to "lv",
    "mk-rMK" to "mk",
    "ml-rIN" to "ml",
    "mn-rMN" to "mn",
    "mr-rIN" to "mr",
    "ms-rMY" to "ms",
    "my-rMM" to "my",
    "nb-rNO" to "nb",
    "ne-rIN" to "ne",
    "nl-rNL" to "nl",
    "or-rIN" to "or",
    "pa-rIN" to "pa",
    "pl-rPL" to "pl",
    "pt-rBR" to "pt-rBR",
    "pt-rPT" to "pt-rPT",
    "ro-rRO" to "ro",
    "ru-rRU" to "ru",
    "si-rLK" to "si",
    "sk-rSK" to "sk",
    "sl-rSI" to "sl",
    "sq-rAL" to "sq",
    "sr-rCS" to "b+sr+Latn",
    "sr-rSP" to "sr",
    "sv-rSE" to "sv",
    "sw-rKE" to "sw",
    "ta-rIN" to "ta",
    "te-rIN" to "te",
    "th-rTH" to "th",
    "tl-rPH" to "tl",
    "tr-rTR" to "tr",
    "uk-rUA" to "uk",
    "ur-rIN" to "ur",
    "uz-rUZ" to "uz",
    "vi-rVN" to "vi",
    "zh-rCN" to "zh-rCN",
    "zh-rTW" to "zh-rTW",
    "zu-rZA" to "zu",
)

/**
 * Apps to include in finalize.
 */
private val appsToInclude = mutableSetOf<AppId>()

/**
 * Stage a single resource for a given value.
 */
private fun addResource(resources: MutableMap<Value, AppResources>, value: Value, resource: BaseResource) {
    resources.getOrPut(value, ::mutableSetOf).add(resource)
    println("Staged resource for value=$value: $resource")
}

internal val addResourcesPatch = resourcePatch(
    description = "Add resources such as strings or arrays to the app."
) {

    finalize {
        val resources: MutableMap<Value, AppResources> = mutableMapOf()

        /**
         * Add all resources from a single XML file
         */
        fun addResourcesFromFile(
            resources: MutableMap<Value, AppResources>,
            appId: AppId,
            locale: Value,
            resourceType: String,
            transform: (Node) -> BaseResource
        ) {
            val resourceSubPath = "$locale/$appId/$resourceType.xml"
            println("Reading resources from $resourceSubPath")

            inputStreamFromBundledResource("addresources", resourceSubPath)?.use { stream ->
                document(stream).use { doc ->
                    doc.getElementsByTagName("resources").item(0)?.forEachChildElement { node ->
                        addResource(resources, locale, transform(node))
                    }
                }
            } ?: throw IllegalArgumentException("Could not find: $resourceSubPath")
        }

        appsToInclude.forEach { app ->
            // Default English.
            addResourcesFromFile(resources, app, "values", "strings", StringResource::fromNode)
            addResourcesFromFile(resources, app, "values", "arrays", ArrayResource::fromNode)

            // Localized.
            locales.forEach { (src, _) ->
                addResourcesFromFile(resources, app, "values-$src", "strings", StringResource::fromNode)
                addResourcesFromFile(resources, app, "values-$src", "arrays", ArrayResource::fromNode)
            }
        }


        operator fun MutableMap<String, Pair<Document, Node>>.invoke(value: Value, resource: BaseResource) {
            val resourceFileName =
                when (resource) {
                    is StringResource -> "strings"
                    is ArrayResource -> "arrays"
                    else -> throw NotImplementedError("Unsupported resource type")
                }

            getOrPut(resourceFileName) {
                val fileName = "res/$value/$resourceFileName.xml"
                this@finalize[fileName].also {
                    it.parentFile?.mkdirs()
                    if (it.createNewFile()) {
                        it.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>")
                    }
                }

                val doc = document(fileName)
                doc to doc.getNode("resources")
            }.let { (_, targetNode) ->
                targetNode.addResource(resource) { invoke(value, it) }
                println("Written resource to $value/$resourceFileName: $resource")
            }
        }

        // Write resources to disk using the original operator pattern.
        val documents = mutableMapOf<String, Pair<Document, Node>>()

        resources.forEach { (value, appResources) ->
            appResources.forEach { resource ->
                documents(value, resource)
            }
        }

        documents.values.forEach { (doc, _) -> doc.close() }
        println("Finalize complete. Total resources written: ${resources.values.sumOf { it.size }}")
    }
}

/**
 * Add all resources for the given app.
 */
fun addAppResources(appId: String) {
    appsToInclude.add(appId)
}


@Deprecated("Use addAppResources instead.")
fun addResources(appId: String, ignoredPatchId: String?) { // TODO: delete this
//    appsToInclude.add(appId)
}
