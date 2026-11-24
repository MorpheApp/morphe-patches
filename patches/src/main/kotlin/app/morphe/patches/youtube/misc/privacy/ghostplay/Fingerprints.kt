/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.privacy.ghostplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * VideoStats2Client sendPing. Sends or queues the playback, watchtime and delayplay pings.
 */
internal object VideoStats2SendPingFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("Final ping for playback", comparison = StringComparisonType.CONTAINS)
    )
)

/**
 * VideoStats2Client method that adds the common query parameters of the pings.
 * The String field read right before "docid" is the video id.
 */
internal object VideoStats2DocIdFingerprint : Fingerprint(
    classFingerprint = VideoStats2SendPingFingerprint,
    returnType = "V",
    filters = listOf(
        string("cpn"),
        string("ns", location = MatchAfterWithin(6)),
        string("yt", location = MatchAfterWithin(3)),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(6)
        ),
        string("docid", location = MatchAfterWithin(3)),
        string("ver", location = MatchAfterWithin(6))
    )
)

/**
 * VideoStats2Client method that logs the watchtime ping as a log event.
 * Older app versions log it at the end of the watchtime flush method, newer versions in a method of its own.
 */
internal object VideoStats2LogEventFingerprint : Fingerprint(
    classFingerprint = VideoStats2SendPingFingerprint,
    returnType = "V",
    filters = listOf(
        methodCall(name = "createBuilder"),
        // Log event field number of the watchtime ping.
        literal(0x1eb)
    )
)

/**
 * VideoStats3Client finishWatchTimeSegment.
 */
internal object VideoStats3FinishSegmentFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z", "J"),
    filters = listOf(
        string("finishWatchTimeSegment called after client was already released."),
        string("Attempting to finish a WatchTimeSegment with an unset start time")
    )
)

/**
 * VideoStats3Client method that sets the CPN and the video id of the WatchTimeSegment.
 */
internal object VideoStats3SegmentIdsFingerprint : Fingerprint(
    classFingerprint = VideoStats3FinishSegmentFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        // CPN.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        methodCall(
            smali = "Ljava/lang/Object;->getClass()Ljava/lang/Class;",
            location = MatchAfterImmediately()
        ),
        // Video id.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(15)
        ),
        methodCall(
            smali = "Ljava/lang/Object;->getClass()Ljava/lang/Class;",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * PtrackingClient method that sends the playback tracking ping.
 */
internal object PtrackingSendFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "J"),
    filters = listOf(
        string("cmt"),
        string("conn"),
        string("cpn"),
        string("remarketing")
    )
)
