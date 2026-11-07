package app.morphe.patches.music.layout.buttons.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object TabLayoutTextFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        anyInstruction(
            string("FEmusic_search"), // 8.49 and lower.
            string("FEsearch"), // 8.50+
            newInstance("Ljava/util/ArrayList;") // 9.21+
        ),

        // Hide navigation label.
        resourceLiteral(
            type = ResourceType.ID,
            name = "text1"
        ),
        methodCall(
            smali = "Landroid/view/View;->findViewById(I)Landroid/view/View;",
            location = MatchAfterWithin(5)
        ),
        checkCast(
            type = "Landroid/widget/TextView;",
            location = MatchAfterWithin(5)
        ),

        // Set navigation enum.
        anyInstruction(
            opcode(Opcode.SGET_OBJECT),
            opcode(Opcode.IGET_OBJECT)
        ),
        fieldAccess(
            opcode = Opcode.IGET,
            type = "I",
            location = MatchAfterWithin(5)
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "L",
            parameters = listOf("I"),
            location = MatchAfterWithin(5)
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),

        // Hide navigation buttons.
        methodCall(
            name = "getVisibility"
        )
    )
)

/**
 * Matches the onMeasure method of the watch-while layout, which gives the view the pages are in
 * its height, the height of the layout without the height of the navigation bar.
 * The height is the third argument of the first static call that takes a view and two sizes
 * after the log of a navigation bar without height.
 */
internal object WatchWhileLayoutOnMeasureFingerprint : Fingerprint(
    definingClass = "WatchWhileLayout;",
    name = "onMeasure",
    returnType = "V",
    parameters = listOf("I", "I"),
    filters = listOf(
        string("visiblePivotBarHeight is 0"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "V",
            parameters = listOf("Landroid/view/View;", "I", "I")
        )
    )
)

/**
 * Matches every method that reads the feature flag of the floating miniplayer,
 * a rounded card with room on its sides and below it. 9.39+
 */
internal object FloatingMiniplayerFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45823962L)
    )
)
