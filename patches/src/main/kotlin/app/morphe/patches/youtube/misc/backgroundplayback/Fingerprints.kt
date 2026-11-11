/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.backgroundplayback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object BackgroundPlaybackManagerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST_4,
        Opcode.IF_EQZ,
        Opcode.IGET,
        Opcode.AND_INT_LIT16,
        Opcode.IF_EQZ,
        Opcode.IGET_OBJECT,
        Opcode.IF_NEZ,
        Opcode.SGET_OBJECT,
        Opcode.IGET,
        Opcode.CONST,
        Opcode.IF_NE,
        Opcode.IGET_OBJECT,
        Opcode.IF_NEZ,
        Opcode.SGET_OBJECT,
        Opcode.IGET,
        Opcode.IF_NE,
        Opcode.IGET_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.GOTO,
        Opcode.SGET_OBJECT,
        Opcode.GOTO,
        Opcode.CONST_4,
        Opcode.IF_EQZ,
        Opcode.IGET_BOOLEAN,
        Opcode.IF_EQZ,
    )
)

/**
 * 21.23+
 */
internal object BackgroundPlaybackSettingsFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        // Background playback allowed.
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, returnType = "Z", parameters = listOf()),
        methodCall(
            smali = "Ljava/lang/Boolean;->booleanValue()Z",
            location = MatchAfterWithin(2)
        ),
        resourceLiteral(
            ResourceType.STRING,
            "pref_background_and_offline_category",
            location = MatchAfterWithin(10)
        ),
        resourceLiteral(ResourceType.STRING, "pref_background_category")
    )
)

/**
 * 21.22 and lower.
 */
internal object BackgroundPlaybackSettingsLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        methodCall(returnType = "Z"),
        // Background playback allowed.
        methodCall(returnType = "Z"),
        resourceLiteral(ResourceType.STRING, "pref_background_and_offline_category"),
        resourceLiteral(ResourceType.STRING, "pref_background_category")
    )
)

internal const val AUTOMATIC_FOREGROUND_PLAYBACK_RESUME_FLAG = 45770945L

internal object AutomaticForegroundPlaybackResumeFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(AUTOMATIC_FOREGROUND_PLAYBACK_RESUME_FLAG)
    )
)

/**
 * Saves and restores if the video was playing when the app went to the background. Its class and
 * the class of the main activity that restores it read the automatic foreground playback resume flag
 * when the app goes to the background and returns to the foreground.
 */
internal object PlayingWhenBackgroundedFingerprint : Fingerprint(
    filters = listOf(
        string("was_playing_when_backgrounded")
    )
)

internal object AutomaticPlaybackPausedInFlyoutFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45741823L)
    )
)

internal object KidsBackgroundPlaybackPolicyControllerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "L", "L"),
    filters = listOf(
        literal(5),
    ) + OpcodesFilter.opcodesToFilters(
        Opcode.CONST_4,
        Opcode.IF_NE,
        Opcode.SGET_OBJECT,
        Opcode.IF_NE,
        Opcode.IGET,
        Opcode.CONST_4,
        Opcode.IF_NE,
        Opcode.IGET_OBJECT,
    )
)

internal object ShortsBackgroundPlaybackFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45415425)
    )
)

// Fix 'E/InputDispatcher: Window handle pip_input_consumer has no registered input channel'
internal object PipInputConsumerFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        // PiP input consumer feature flag.
        literal(45638483L)
    )
)

internal object NewPlayerTypeEnumFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45698813L)
    )
)

/**
 * Sets the artwork of the state of the playback media session, such as the thumbnail of the playback notification,
 * and returns the change of the state, or 0 if there is no artwork and there was none before.
 */
internal object MediaSessionArtworkStateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
    parameters = listOf("Landroid/graphics/Bitmap;", "Landroid/graphics/Bitmap;"),
    filters = listOf(
        opcode(Opcode.IF_NEZ),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Landroid/graphics/Bitmap;", location = MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Landroid/graphics/Bitmap;", location = MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        // No change.
        opcode(Opcode.CONST_4, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Landroid/graphics/Bitmap;"),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Landroid/graphics/Bitmap;", location = MatchAfterImmediately()),
        // Change of the artwork.
        opcode(Opcode.CONST_16, location = MatchAfterImmediately())
    ),
    // The state class implements the interface of the state. Some versions have a class
    // with the same method for a second state, which implements nothing.
    custom = { _, classDef ->
        classDef.interfaces.isNotEmpty()
    }
)
