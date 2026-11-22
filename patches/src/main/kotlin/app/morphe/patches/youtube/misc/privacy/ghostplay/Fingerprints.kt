/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.privacy.ghostplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Matches VideoStats2Client#sendPing(UriBuilder, PingPolicy).
 * Blocking this method prevents VSS2 playback, watchtime, and delayplay pings
 * from being sent or queued into DelayedPing storage.
 */
internal object VideoStats2SendPingFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("Final ping for playback", comparison = StringComparisonType.CONTAINS)
    )
)

/**
 * Matches VideoStats2Client method that populates common tracking query parameters
 * ("cpn", "ns", "yt", "docid", "ver", "2").
 * The String field read immediately before "docid" is the video ID field.
 */
internal object VideoStats2DocIdFingerprint : Fingerprint(
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
 * Matches VideoStats2Client GEL logging helper method (1-parameter void method building a proto
 * and dispatching to the event logger, extracted in newer 21.x builds).
 */
internal object VideoStats2SendGelFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(name = "createBuilder"),
        methodCall(name = "copyOnWrite", location = MatchAfterWithin(6)),
        methodCall(name = "build", location = MatchAfterWithin(10))
    )
)

/**
 * Matches VideoStats2Client watchtime flush method where GEL watchtime logging is inlined
 * (used on versions where the GEL dispatch helper has not been extracted).
 */
internal object VideoStats2FlushWatchtimeFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I"),
    filters = listOf(
        string("paused"),
        string("playing"),
        string("state"),
        string("final"),
        methodCall(name = "createBuilder")
    )
)

/**
 * Matches VideoStats3Client#finishWatchTimeSegment(boolean, long).
 * Blocking this method before its monitor-enter prevents VSS3 WatchTimeSegment events
 * from being dispatched to YouTube's event logger.
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
 * Matches PtrackingClient's ping dispatch method (builds cmt/conn/cpn/remarketing params).
 * Blocking this method prevents playback tracking pings sent outside VideoStats2Client.
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
