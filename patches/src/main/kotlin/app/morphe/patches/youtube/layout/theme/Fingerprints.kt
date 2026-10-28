/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4135
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object UseGradientLoadingScreenFingerprint : Fingerprint(
    filters = listOf(
        literal(45412406L)
    )
)

internal object LithoImageColorFilterFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        name = "draw",
        parameters = listOf("Landroid/graphics/Canvas;"),
        returnType = "V",
        filters = listOf(
            methodCall(
                smali = $$"Landroid/graphics/BitmapShader;-><init>(Landroid/graphics/Bitmap;Landroid/graphics/Shader$TileMode;Landroid/graphics/Shader$TileMode;)V"
            ),
            methodCall(
                smali = "Landroid/graphics/Canvas;->drawRect(Landroid/graphics/RectF;Landroid/graphics/Paint;)V"
            )
        )
    ),
    name = "setColorFilter",
    parameters = listOf("Landroid/graphics/ColorFilter;"),
    returnType = "V"
)

/**
 * Rive startup animation, which is played instead of the Lottie animation.
 */
internal object RiveSplashScreenFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(1073830295L)
    )
)

internal const val RIVE_THEMED_SPLASH_SCREEN_FEATURE_FLAG = 1073830411L

/**
 * Rive animation 'rive_themed_startup_animation', of a logo that turns into a play button.
 */
internal object RiveThemedSplashScreenFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(RIVE_THEMED_SPLASH_SCREEN_FEATURE_FLAG)
    )
)

/**
 * 21.39+ Rive animation 'rive_phantom', of a logo that comes into focus out of a glow.
 * In earlier versions this flag selects a different Rive view.
 */
internal object RiveGlowSplashScreenFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(1073840124L)
    )
)

/**
 * Creates the startup animation view, and sets the field that the Rive animation is played.
 */
internal object RiveSplashScreenViewFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    filters = listOf(
        literal(1073830295L),
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            type = "Z"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            type = "Z",
            location = MatchAfterImmediately()
        ),
        resourceLiteral(
            ResourceType.LAYOUT,
            "main_activity_modern_rive_startup_animation",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * 21.30 - 21.31 and 21.35+ sets the view that the Rive animation is drawn in.
 */
internal object RiveSplashScreenAnimationViewFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "rive_animation"),
        methodCall(
            name = "findViewById",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),
        checkCast(
            "Landroid/widget/ImageView;",
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Landroid/widget/ImageView;",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * 21.32 - 21.34 sets the view that the Rive animation is drawn in,
 * with a resource id that was loaded earlier for another view.
 */
internal object RiveSplashScreenAnimationViewLegacyFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(name = "findViewById"),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),
        checkCast(
            "Landroid/widget/ImageView;",
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Landroid/widget/ImageView;",
            location = MatchAfterImmediately()
        ),
        literal(
            RIVE_THEMED_SPLASH_SCREEN_FEATURE_FLAG,
            location = MatchAfterWithin(2)
        )
    )
)

/**
 * Closes the startup animation as soon as the home feed is shown.
 */
internal object SplashScreenOnStartFingerprint : Fingerprint(
    classFingerprint = RiveSplashScreenViewFingerprint,
    name = "onStart",
    returnType = "V",
    filters = listOf(
        literal(1073815019L),
        literal(1073828809L)
    )
)

internal object SplashScreenOnStopFingerprint : Fingerprint(
    classFingerprint = RiveSplashScreenViewFingerprint,
    name = "onStop",
    returnType = "V"
)

/**
 * Removes the splash screen.
 */
internal object SplashScreenRemoveFingerprint : Fingerprint(
    classFingerprint = RiveSplashScreenViewFingerprint,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("SAU_REMOVE_SPLASH_ANIMATION|ANIMATION_SHOWN")
    )
)

/**
 * Creates the source of a Rive file that is a raw resource,
 * with the key the app keeps the file in memory with.
 */
internal object RiveRawResourceSourceFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("I"),
    filters = listOf(
        string("raw_res_"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(3)
        )
    )
)

/**
 * Reads the Rive file of a raw resource.
 */
internal object RiveReadRawResourceFingerprint : Fingerprint(
    returnType = "[B",
    parameters = listOf("I"),
    filters = listOf(
        string("readRawResource"),
        methodCall(smali = "Landroid/content/res/Resources;->openRawResource(I)Ljava/io/InputStream;"),
        opcode(
            opcode = Opcode.RETURN_OBJECT,
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Stores each frame the Rive renderer draws.
 */
internal object RiveCanvasSessionSetLatestBitmapFingerprint : Fingerprint(
    definingClass = "Lapp/rive/RiveCanvasSession;",
    name = "access\$setLatestBitmap\$p",
    returnType = "V",
    parameters = listOf("Lapp/rive/RiveCanvasSession;", "Landroid/graphics/Bitmap;")
)

internal object SplashAnimationIsRiveIncludedFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/shared/patches/SplashAnimationPatch;",
    name = "isRiveIncluded",
    returnType = "Z",
    parameters = listOf()
)

internal object LottieLayerColorFingerprint : Fingerprint(
    parameters = listOf(
        "Landroid/graphics/Canvas;",
        "Landroid/graphics/Matrix;",
        "I"
    ),
    returnType = "V",
    filters = listOf(
        methodCall(smali = "Landroid/graphics/Paint;->setColor(I)V")
    )
)

internal object CarbonColorThemeFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45760313)
    )
)

internal object SplashScreenStyleFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        anyInstruction(
            literal(1074339245), // 20.30+
            literal(269032877L) // 20.29 and lower.
        )
    )
)

/**
 * Matches to the same method as [SplashScreenStyleFingerprint].
 */
internal object ShowSplashScreenFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "Z",
            parameters = listOf("I")
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT,
            location = MatchAfterImmediately()
        ),
        anyInstruction(
            opcode(Opcode.IF_EQZ),
            opcode(Opcode.IF_NEZ),
            location = MatchAfterImmediately()
        ),
        anyInstruction(
            opcode(Opcode.CONST_4),
            opcode(Opcode.CONST_16),
            location = MatchAfterWithin(3)
        ),
        opcode(
            opcode = Opcode.IF_NE,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * The system draws the splash screen with the theme of the launcher activity, so the extension is
 * given the activity as soon as it is created.
 */
internal object MainActivityOnCreateFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;")
)

/**
 * The pivot bar creates the view stub of the new content dot, and of the count next to it.
 * The count is matched as well because the effects picker uses the same dot id.
 */
internal object PivotBarNewContentDotFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "new_content_dot"),
        methodCall(
            name = "findViewById",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(3)
        ),
        resourceLiteral(
            ResourceType.ID,
            "new_content_count",
            location = MatchAfterWithin(30)
        ),
        opcode(opcode = Opcode.CHECK_CAST)
    )
)

/**
 * The top bar creates the view stub of the new content count of the notification button, and of
 * the dot next to it. The count comes first, which is the other way around than the pivot bar,
 * and that is what keeps both fingerprints from matching the same method.
 */
internal object TopBarNewContentCountFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "new_content_count"),
        methodCall(
            name = "findViewById",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(3)
        ),
        resourceLiteral(
            ResourceType.ID,
            "new_content_dot",
            location = MatchAfterWithin(30)
        ),
        methodCall(
            name = "findViewById",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(3)
        )
    )
)

internal object SpinnerThemeHookFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "spinner"),
        methodCall(
            name = "findViewById",
            location = MatchAfterWithin(2)
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(3)
        )
    )
)
