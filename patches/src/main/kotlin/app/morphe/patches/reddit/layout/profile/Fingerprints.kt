/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5189
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.profile

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.Opcode

private val EMPTY_FEED_PARAMETERS = listOf(
    "Ljava/lang/String;", // Title.
    "Ljava/lang/String;", // Description.
    "I", // Icon.
    "Z", // Show button.
    "L", // Button click handler. Kotlin Function0, obfuscated in some app versions.
    "L", // Modifier.
    "L", // Composer.
    "I"
)

internal object ProfileEmptyFeedModelToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf(
        "ProfileEmptyFeedPresentationModel(targetFeed=",
        ", profileName="
    )
)

internal object ProfileEmptyFeedModelConstructorFingerprint : Fingerprint(
    classFingerprint = ProfileEmptyFeedModelToStringFingerprint,
    name = "<init>",
    filters = listOf(
        // Feed type.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Lcom/reddit/profile/model/ProfileFeedType;"
        ),
        // Profile name.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        // Is filtered (posts or comments hidden by the profile settings).
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "this",
            type = "Z"
        )
    )
)

/**
 * Composable of the profile empty feed, with the empty feed model as the first parameter.
 */
internal object ProfileEmptyFeedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L", "L", "L", "I"),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "profile_empty_feed_visitor_comment_filtered_description"),
        resourceLiteral(ResourceType.STRING, "profile_empty_feed_visitor_post_filtered_description"),
        methodCall(
            returnType = "V",
            parameters = EMPTY_FEED_PARAMETERS
        )
    )
)

/**
 * Composable lambda of the empty feed settings button label.
 */
internal object EmptyFeedButtonLabelFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "profile_empty_feed_owner_update_settings_button"),
        methodCall(
            returnType = "Ljava/lang/String;",
            parameters = listOf("L", "I"),
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)
