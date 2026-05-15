package app.morphe.patches.music.misc.sponsorblock

import app.morphe.patcher.fingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

/**
 * The resource ID for 'inline_time_bar_ad_break_marker_color'.
 * This is set in SponsorBlockPatch.kt's execute block (via resourceMappingPatch)
 * BEFORE the seekBarConstructorFingerprint is ever accessed.
 * Because fingerprints are resolved lazily on first access, this works correctly.
 */
internal var inlineTimeBarAdBreakMarkerColorId = -1L

/**
 * Identifies the YTM inline time bar class (the seekbar shown in fullscreen/queue view).
 * The constructor of this class contains a wide literal for the
 * 'inline_time_bar_ad_break_marker_color' resource, which uniquely identifies it.
 *
 * NOTE: inlineTimeBarAdBreakMarkerColorId must be set from the resource mapping
 * before this fingerprint is resolved.
 */
internal val seekBarConstructorFingerprint = fingerprint {
    returns("V")
    custom { methodDef, _ ->
        inlineTimeBarAdBreakMarkerColorId != -1L &&
                methodDef.name == "<init>" &&
                methodDef.implementation?.instructions?.any { instruction ->
                    when (instruction.opcode) {
                        Opcode.CONST,
                        Opcode.CONST_HIGH16 ->
                            (instruction as? NarrowLiteralInstruction)
                                ?.narrowLiteral?.toLong() == inlineTimeBarAdBreakMarkerColorId
                        Opcode.CONST_WIDE,
                        Opcode.CONST_WIDE_16,
                        Opcode.CONST_WIDE_32 ->
                            (instruction as? WideLiteralInstruction)
                                ?.wideLiteral == inlineTimeBarAdBreakMarkerColorId
                        else -> false
                    }
                } == true
    }
}

/**
 * Identifies a method inside the seekbar class that obtains a Rect field and then
 * calls View.invalidate(). The Rect field name found here is passed to the extension
 * so it can read the bar bounds via reflection.
 *
 * Resolved within the class found by seekBarConstructorFingerprint.
 */
internal val rectangleFieldInvalidatorFingerprint = fingerprint {
    returns("V")
    opcodes(
        Opcode.IGET_OBJECT,   // load Rect field
        Opcode.INVOKE_VIRTUAL, // method call on Rect (e.g. left/right)
        Opcode.INVOKE_VIRTUAL, // invalidate()
    )
}

/**
 * Identifies the onDraw(Canvas) method of the seekbar class.
 * SponsorBlock segment colors are injected here so they appear on the seekbar.
 *
 * Resolved within the class found by seekBarConstructorFingerprint.
 */
internal val seekbarOnDrawFingerprint = fingerprint {
    returns("V")
    custom { methodDef, _ -> methodDef.name == "onDraw" }
}

/**
 * Identifies the draw(Canvas) method of MusicPlaybackControlsTimeBar.
 * This is the timebar in the regular (collapsed) music player view.
 * SponsorBlock segments are drawn here as colored rectangles.
 */
internal val musicPlaybackControlsTimeBarDrawFingerprint = fingerprint {
    returns("V")
    custom { methodDef, _ ->
        methodDef.definingClass.endsWith("/MusicPlaybackControlsTimeBar;") &&
                methodDef.name == "draw"
    }
}

/**
 * Identifies the onMeasure(int, int) method of MusicPlaybackControlsTimeBar.
 * Used to obtain the Rect field name that holds the timebar drawing bounds.
 * This field name is then passed to the extension via reflection.
 */
internal val musicPlaybackControlsTimeBarOnMeasureFingerprint = fingerprint {
    returns("V")
    parameters("I", "I")
    opcodes(
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_VIRTUAL,
        Opcode.RETURN_VOID,
    )
    custom { methodDef, _ ->
        methodDef.definingClass.endsWith("/MusicPlaybackControlsTimeBar;") &&
                methodDef.name == "onMeasure"
    }
}
