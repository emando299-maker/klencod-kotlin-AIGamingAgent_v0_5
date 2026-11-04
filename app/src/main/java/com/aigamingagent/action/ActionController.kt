package com.aigamingagent.action

import com.aigamingagent.ludo.DecisionEngine
import com.aigamingagent.ludo.LudoRules
import com.aigamingagent.ludo.PlayerColor
import com.aigamingagent.ludo.ScoredMove
import com.aigamingagent.vision.VisionResult

interface Tapper {
    /** Returns true only if the gesture was actually dispatched. */
    fun tap(x: Float, y: Float): Boolean
}

enum class ActionKind {
    NO_BOARD, UNCERTAIN, NOT_MY_TURN, WAIT_NEW_ROLL, NEED_ROLL, NO_LEGAL_MOVE,
    COOLDOWN, DRY_RUN, EXECUTING, VERIFIED, FAILED, HALTED
}

data class ActionStatus(val kind: ActionKind, val text: String, val decision: ScoredMove? = null)

data class ActionConfig(
    val minConfidence: Double = 0.8,
    val verifyTimeoutMs: Long = 4000,
    val maxConsecutiveFailures: Int = 2,
    val minIntervalMs: Long = 1500,
    /** Tap the dice plate when it is my turn and no number is shown. Unverified game behaviour. */
    val rollTap: Boolean = false
)

/**
 * Safety-gated act/verify loop. Rules enforced here:
 *  - never acts unless the board state is fully understood and confidence >= minConfidence;
 *  - never acts on a screen-read dice unless it is my turn (green ring) and the number was stable;
 *  - after a move made from a screen-read dice, that same number is ignored until the plate changes
 *    or my turn ends (prevents moving twice on one roll);
 *  - never acts again until the previous move was verified (my pieces moved as predicted) or timed out;
 *  - halts for good after [ActionConfig.maxConsecutiveFailures] unverified actions.
 * A manual roll (testing) bypasses the turn/dice-reset checks but not the others.
 */
class ActionController(
    private val tapper: Tapper,
    private val rules: LudoRules = LudoRules(),
    private val engine: DecisionEngine = DecisionEngine(rules),
    private val config: ActionConfig = ActionConfig()
) {
    private class Awaiting(val expectedMine: List<Int>, val deadline: Long, val autoDice: Int?)

    var moves = 0
        private set
    var halted = false
        private set
    private var failures = 0
    private var awaiting: Awaiting? = null
    private var awaitingRollUntil: Long? = null
    private var lastActionAt: Long? = null
    private var consumedDice: Int? = null
    private var needReset = false

    fun halt() { halted = true; awaiting = null; awaitingRollUntil = null }

    /** @param roll manual (test) dice value; null = use the value read from the screen. */
    fun onFrame(
        result: VisionResult,
        roll: Int?,
        me: PlayerColor,
        frameToScreen: Double,
        live: Boolean,
        now: Long
    ): ActionStatus {
        if (halted) return ActionStatus(ActionKind.HALTED, "متوقف نهائيًا بعد فشل التحقق — أعد التشغيل")

        awaiting?.let { a ->
            val mine = result.state?.takeIf { me in it.pieces }?.positions(me)?.sorted()
            if (mine == a.expectedMine) {
                awaiting = null; failures = 0; moves++
                if (a.autoDice != null) { consumedDice = a.autoDice; needReset = true }
                return ActionStatus(ActionKind.VERIFIED, "تم التحقق من الحركة")
            }
            if (now < a.deadline) return ActionStatus(ActionKind.EXECUTING, "بانتظار التحقق من الحركة")
            awaiting = null
            return fail("لم تتغير اللوحة كما هو متوقع")
        }

        val rollDeadline = awaitingRollUntil
        if (rollDeadline != null) {
            if (result.dice != null) {
                awaitingRollUntil = null
                failures = 0
            } else if (now < rollDeadline) {
                return ActionStatus(ActionKind.EXECUTING, "ضغطت النرد — بانتظار الرقم")
            } else {
                awaitingRollUntil = null
                return fail("لم يظهر رقم النرد بعد الضغط")
            }
        }

        val board = result.board ?: return ActionStatus(ActionKind.NO_BOARD, "لوحة لودو غير ظاهرة")
        val state = result.state
        if (state == null || result.confidence < config.minConfidence || me !in state.pieces) {
            return ActionStatus(ActionKind.UNCERTAIN, "حالة غير مؤكدة — لا قرار")
        }

        val manual = roll != null
        if (needReset && (result.dice == null || !result.myTurn || result.dice != consumedDice)) needReset = false
        if (!manual) {
            if (!result.myTurn) return ActionStatus(ActionKind.NOT_MY_TURN, "ليس دورك")
            if (needReset) return ActionStatus(ActionKind.WAIT_NEW_ROLL, "بانتظار رمية جديدة")
        }

        val value = roll ?: result.dice
        if (value == null) return maybeRollTap(result, frameToScreen, live, now)

        val decision = engine.choose(state, me, value)
            ?: return ActionStatus(ActionKind.NO_LEGAL_MOVE, "لا حركة قانونية برقم $value")
        if (!live) return ActionStatus(ActionKind.DRY_RUN, "تجربة (بدون تنفيذ)", decision)

        if (onCooldown(now)) return ActionStatus(ActionKind.COOLDOWN, "انتظار بين الحركات", decision)
        val points = ActionPlanner.plan(result, decision.move, frameToScreen)
        if (points.isEmpty() || board.cell <= 0) return fail("تعذّر تحديد مكان الضغط")
        lastActionAt = now
        for ((x, y) in points) {
            if (!tapper.tap(x, y)) return fail("فشل إرسال اللمسة (خدمة إمكانية الوصول غير مفعّلة؟)")
        }
        val expected = rules.apply(state, decision.move).positions(me).sorted()
        awaiting = Awaiting(expected, now + config.verifyTimeoutMs, if (manual) null else value)
        return ActionStatus(ActionKind.EXECUTING, "تم الضغط — بانتظار التحقق", decision)
    }

    private fun maybeRollTap(result: VisionResult, frameToScreen: Double, live: Boolean, now: Long): ActionStatus {
        if (!live || !config.rollTap) return ActionStatus(ActionKind.NEED_ROLL, "اللوحة مفهومة — النرد غير معروف")
        if (onCooldown(now)) return ActionStatus(ActionKind.COOLDOWN, "انتظار قبل ضغط النرد")
        val point = ActionPlanner.planRoll(result, frameToScreen) ?: return fail("موضع زر النرد غير معروف")
        lastActionAt = now
        if (!tapper.tap(point.first, point.second)) return fail("فشل إرسال اللمسة (خدمة إمكانية الوصول غير مفعّلة؟)")
        awaitingRollUntil = now + config.verifyTimeoutMs
        return ActionStatus(ActionKind.EXECUTING, "ضغطت النرد — بانتظار الرقم")
    }

    private fun onCooldown(now: Long): Boolean = lastActionAt?.let { now - it < config.minIntervalMs } ?: false

    private fun fail(reason: String): ActionStatus {
        failures++
        if (failures >= config.maxConsecutiveFailures) {
            halted = true
            return ActionStatus(ActionKind.HALTED, "إيقاف: $reason ($failures محاولات فاشلة)")
        }
        return ActionStatus(ActionKind.FAILED, reason)
    }
}
