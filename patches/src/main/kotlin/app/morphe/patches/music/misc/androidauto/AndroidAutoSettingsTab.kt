package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.cloneMutable
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val TAB = "Lapp/morphe/extension/music/patches/AndroidAutoSettingsTab"
private const val SETTINGS_SOURCE = "$TAB\$StockSettingsSource;"
private const val ROW_TITLE = "$TAB\$RowTitle;"
private const val CONTEXT = "Landroid/content/Context;"
private const val MODEL = "Landroidx/car/app/model/"
private const val SETTINGS_SERVICE =
    "Lcom/google/android/apps/youtube/music/mediabrowser/androidautoprojected/AndroidAutoCarAppServices;"

/**
 * Android Auto checks YouTube Music's car app service before opening the stock Settings screen.
 * If Android Auto rejects the patched app's service, YouTube Music crashes. The extension's
 * `AndroidAutoSettingsTab` serves the same settings through `MusicBrowserService` as a tab alongside Home and Library.
 */
internal fun replaceSettingsButton() {
    AndroidAutoSettingsIntentFingerprint.let {
        // Android Auto adds the stock button when the browser root contains YouTube Music's Settings PendingIntent.
        it.method.removeInstruction(it.instructionMatches.last().index)
    }

    val template = StockSettingsTemplateFingerprint
    val contextField = template.instructionMatches.first().fieldReference
    val controllerField = template.instructionMatches[4].fieldReference
    addBrowseSettingsTemplate(contextField, controllerField)
    hookMusicBrowserServiceDependency(
        controllerField.type, SETTINGS_SOURCE, "$TAB;->setSettingsSource($SETTINGS_SOURCE)V",
        // At startup MusicBrowserService reuses the provider that the car settings service reads in its superclass.
        // The controller type alone matches several providers in 9.44.52 through 9.46.51.
        injector = classDefBy(SETTINGS_SERVICE).superclass!!,
    )

    // Choosing On or Off opens a media item. The extension calls the original toggle listener so YouTube Music
    // applies the change. These interfaces expose that listener through the toggle's obfuscated delegate type.
    val toggle = mutableClassDefBy("${MODEL}Toggle;")
    toggle.addPatchInterface("${TAB}\$StockToggle;", "patch_getDelegate",
        toggle.fields.single { it.name == "mOnCheckedChangeDelegate" })
    val delegate = mutableClassDefBy("${MODEL}OnCheckedChangeDelegateImpl;")
    delegate.addPatchInterface("${TAB}\$StockToggleDelegate;", "patch_getListener",
        delegate.fields.single { it.type == "${MODEL}IOnCheckedChangeListener;" })
}

/**
 * The stock car screen owns the method that builds the settings rows. Copying it onto the settings
 * controller lets `MusicBrowserService` build those rows using its own `Context` for resource lookups.
 */
private fun addBrowseSettingsTemplate(contextField: FieldReference, controllerField: FieldReference) {
    val template = StockSettingsTemplateFingerprint
    val getString = template.instructionMatches[2].methodReference
    val setRowTitle = template.instructionMatches[3].methodReference
    val render = template.originalMethod.cloneMutable(
        name = "patch_getSettingsTemplate",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        parameters = listOf(CONTEXT, ROW_TITLE).map { ImmutableMethodParameter(it, null, null) },
        returnType = "${MODEL}ListTemplate;",
        additionalRegisters = 2,
    ).apply { setDefiningClass(controllerField.type) }
    val instructions = render.instructions.toList()
    instructions.forEachIndexed { index, instruction ->
        val field = instruction.getReference<FieldReference>()
        val call = instruction.getReference<MethodReference>()
        val registers = instruction.registersUsed
        when {
            field == contextField -> render.replaceInstruction(index, "move-object/from16 v${registers.first()}, p1")
            field == controllerField -> render.replaceInstruction(index, "move-object/from16 v${registers.first()}, p0")
            call == getString && instructions.getOrNull(index + 2)?.getReference<MethodReference>() == setRowTitle -> {
                // RowTitle returns the resource name and keeps the translated title for display.
                // Using that name in the media ID lets Android Auto reopen the row after a language change.
                render.replaceInstruction(index,
                    "invoke-interface { p2, v${registers[1]} }, ${ROW_TITLE}->get(I)Ljava/lang/String;")
            }
            call?.definingClass == contextField.type -> {
                check(call.name == "getString" || call.name == "getResources")
                render.replaceInstruction(index, "invoke-virtual { ${registers.joinToString { "v$it" }} }, " +
                        call.toString().replaceBefore("->", CONTEXT))
            }
        }
    }
    mutableClassDefBy(controllerField.type).apply {
        interfaces.add(SETTINGS_SOURCE)
        methods.add(render)
    }
}
