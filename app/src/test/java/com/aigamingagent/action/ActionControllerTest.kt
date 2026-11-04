package com.aigamingagent.action

import com.aigamingagent.ludo.LudoState
import com.aigamingagent.ludo.PlayerColor.RED
import com.aigamingagent.ludo.PlayerColor.YELLOW
import com.aigamingagent.vision.BoardGeometry
import com.aigamingagent.vision.VisionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionControllerTest {
    private class FakeTapper(var works: Boolean = true) : Tapper {
        val taps = mutableListOf<Pair<Float, Float>>()
        override fun tap(x: Float, y: Float): Boolean { if (works) taps.add(x to y); return works }
    }

    private val geo = BoardGeometry(0.0, 0.0, 10.0)
    private fun result(
        red: List<Int>, conf: Double = 0.95, understood: Boolean = true,
        myTurn: Boolean = true, dice: Int? = null, rollButton: Pair<Double, Double>? = null
    ) = VisionResult(
        board = geo, boardConfidence = 1.0, baseCounts = emptyMap(), pieces = emptyList(),
        activeColors = setOf(RED, YELLOW),
        state = if (understood) LudoState(mapOf(RED to red, YELLOW to listOf(-1, -1, -1, -1))) else null,
        confidence = conf, notes = emptyList(), scale = 1,
        myTurn = myTurn, diceRaw = dice, dice = dice, rollButton = rollButton
    )

    private val before = listOf(-1, -1, -1, 10)   // RED rel 10 = cell (6,14)

    private fun controller(t: Tapper) = ActionController(t)

    @Test fun uncertainStateNeverActs() {
        val t = FakeTapper()
        val s = controller(t).onFrame(result(before, understood = false), 3, RED, 2.0, true, 0)
        assertEquals(ActionKind.UNCERTAIN, s.kind); assertTrue(t.taps.isEmpty())
    }

    @Test fun lowConfidenceNeverActs() {
        val t = FakeTapper()
        val s = controller(t).onFrame(result(before, conf = 0.5), 3, RED, 2.0, true, 0)
        assertEquals(ActionKind.UNCERTAIN, s.kind); assertTrue(t.taps.isEmpty())
    }

    @Test fun unknownRollNeverActs() {
        val t = FakeTapper()
        val s = controller(t).onFrame(result(before), null, RED, 2.0, true, 0)
        assertEquals(ActionKind.NEED_ROLL, s.kind); assertTrue(t.taps.isEmpty())
    }

    @Test fun noLegalMoveIsReported() {
        val t = FakeTapper()
        val s = controller(t).onFrame(result(listOf(-1, -1, -1, -1)), 3, RED, 2.0, true, 0)
        assertEquals(ActionKind.NO_LEGAL_MOVE, s.kind); assertTrue(t.taps.isEmpty())
    }

    @Test fun dryRunDecidesButDoesNotTap() {
        val t = FakeTapper()
        val s = controller(t).onFrame(result(before), 3, RED, 2.0, false, 0)
        assertEquals(ActionKind.DRY_RUN, s.kind)
        assertEquals(10, s.decision!!.move.from); assertEquals(13, s.decision!!.move.to)
        assertTrue(t.taps.isEmpty())
    }

    @Test fun liveTapsPieceCellCentreInScreenPixels() {
        val t = FakeTapper()
        val s = controller(t).onFrame(result(before), 3, RED, 2.0, true, 0)
        assertEquals(ActionKind.EXECUTING, s.kind)
        // cell (row 6, col 14), cell size 10 -> centre (145, 65) in frame px, x2 to screen px
        assertEquals(listOf(290f to 130f), t.taps)
    }

    @Test fun doesNotActAgainBeforeVerification() {
        val t = FakeTapper(); val c = controller(t)
        c.onFrame(result(before), 3, RED, 2.0, true, 0)
        val s = c.onFrame(result(before), 3, RED, 2.0, true, 500)
        assertEquals(ActionKind.EXECUTING, s.kind); assertEquals(1, t.taps.size)
    }

    @Test fun verifiesWhenMyPiecesMovedAsPredicted() {
        val t = FakeTapper(); val c = controller(t)
        c.onFrame(result(before), 3, RED, 2.0, true, 0)
        val s = c.onFrame(result(listOf(-1, -1, -1, 13)), 3, RED, 2.0, true, 900)
        assertEquals(ActionKind.VERIFIED, s.kind); assertEquals(1, c.moves)
    }

    @Test fun timeoutCountsFailureAndTwoFailuresHalt() {
        val t = FakeTapper(); val c = controller(t)
        c.onFrame(result(before), 3, RED, 2.0, true, 0)
        assertEquals(ActionKind.FAILED, c.onFrame(result(before), 3, RED, 2.0, true, 5000).kind)
        c.onFrame(result(before), 3, RED, 2.0, true, 7000)          // second attempt
        assertEquals(ActionKind.HALTED, c.onFrame(result(before), 3, RED, 2.0, true, 12000).kind)
        val tapsAtHalt = t.taps.size
        assertEquals(ActionKind.HALTED, c.onFrame(result(before), 3, RED, 2.0, true, 20000).kind)
        assertEquals(tapsAtHalt, t.taps.size)
    }

    @Test fun failedGestureDispatchIsAFailureNotASuccess() {
        val t = FakeTapper(works = false)
        val s = controller(t).onFrame(result(before), 3, RED, 2.0, true, 0)
        assertEquals(ActionKind.FAILED, s.kind); assertEquals(0, controller(t).moves)
    }

    @Test fun exitFromBaseNeedsAFilledSlotToTap() {
        val t = FakeTapper()
        val noSlots = result(listOf(-1, -1, -1, -1))
        val s = controller(t).onFrame(noSlots, 6, RED, 1.0, true, 0)
        assertEquals(ActionKind.FAILED, s.kind); assertTrue(t.taps.isEmpty())
        val withSlots = noSlots.copy(filledSlots = mapOf(RED to listOf(0, 1, 2, 3)))
        val s2 = controller(t).onFrame(withSlots, 6, RED, 1.0, true, 0)
        assertEquals(ActionKind.EXECUTING, s2.kind); assertEquals(1, t.taps.size)
    }

    @Test fun screenReadDiceIsIgnoredWhenNotMyTurn() {
        val t = FakeTapper()
        val s = controller(t).onFrame(result(before, myTurn = false, dice = 3), null, RED, 2.0, true, 0)
        assertEquals(ActionKind.NOT_MY_TURN, s.kind); assertTrue(t.taps.isEmpty())
    }

    @Test fun screenReadDiceActsOnMyTurn() {
        val t = FakeTapper()
        val s = controller(t).onFrame(result(before, dice = 3), null, RED, 2.0, true, 0)
        assertEquals(ActionKind.EXECUTING, s.kind); assertEquals(1, t.taps.size)
    }

    @Test fun sameScreenDiceCannotMoveTwice() {
        val t = FakeTapper(); val c = controller(t)
        c.onFrame(result(before, dice = 3), null, RED, 2.0, true, 0)
        assertEquals(ActionKind.VERIFIED, c.onFrame(result(listOf(-1, -1, -1, 13), dice = 3), null, RED, 2.0, true, 900).kind)
        val blocked = c.onFrame(result(listOf(-1, -1, -1, 13), dice = 3), null, RED, 2.0, true, 2000)
        assertEquals(ActionKind.WAIT_NEW_ROLL, blocked.kind); assertEquals(1, t.taps.size)
        assertEquals(ActionKind.NEED_ROLL, c.onFrame(result(listOf(-1, -1, -1, 13), dice = null), null, RED, 2.0, true, 3000).kind)
        assertEquals(ActionKind.EXECUTING, c.onFrame(result(listOf(-1, -1, -1, 13), dice = 3), null, RED, 2.0, true, 4500).kind)
        assertEquals(2, t.taps.size)
    }

    @Test fun differentDiceValueCountsAsNewRoll() {
        val t = FakeTapper(); val c = controller(t)
        c.onFrame(result(before, dice = 3), null, RED, 2.0, true, 0)
        c.onFrame(result(listOf(-1, -1, -1, 13), dice = 3), null, RED, 2.0, true, 900)
        val s = c.onFrame(result(listOf(-1, -1, -1, 13), dice = 4), null, RED, 2.0, true, 3000)
        assertEquals(ActionKind.EXECUTING, s.kind)
    }

    @Test fun rollTapIsOffByDefault() {
        val t = FakeTapper()
        val s = controller(t).onFrame(result(before, rollButton = 50.0 to 60.0), null, RED, 2.0, true, 0)
        assertEquals(ActionKind.NEED_ROLL, s.kind); assertTrue(t.taps.isEmpty())
    }

    @Test fun rollTapWhenEnabledThenFailsIfNoNumberAppears() {
        val t = FakeTapper()
        val c = ActionController(t, config = ActionConfig(rollTap = true))
        val s = c.onFrame(result(before, rollButton = 50.0 to 60.0), null, RED, 2.0, true, 0)
        assertEquals(ActionKind.EXECUTING, s.kind); assertEquals(listOf(100f to 120f), t.taps)
        assertEquals(ActionKind.FAILED, c.onFrame(result(before), null, RED, 2.0, true, 5000).kind)
    }

    @Test fun rollTapNeverOnOpponentsTurn() {
        val t = FakeTapper()
        val c = ActionController(t, config = ActionConfig(rollTap = true))
        val s = c.onFrame(result(before, myTurn = false, rollButton = 50.0 to 60.0), null, RED, 2.0, true, 0)
        assertEquals(ActionKind.NOT_MY_TURN, s.kind); assertTrue(t.taps.isEmpty())
    }
}
