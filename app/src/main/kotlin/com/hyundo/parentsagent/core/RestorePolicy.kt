package com.hyundo.parentsagent.core

import java.time.LocalDateTime

enum class SkipReason(val text: String) {
    NOT_AUTO_RESTORABLE("자동으로 고치지 않는 항목이라 직접 확인이 필요합니다"),
    PAUSED_STREAK("같은 이탈이 3일 연속 반복돼 자동 복원을 멈춘 상태입니다"),
    STREAK_STOPPED("같은 이탈이 3일 연속 반복돼 의도된 변경인지 확인이 필요해 자동 복원을 멈췄습니다"),
    NIGHT_DND("야간 시간대라 방해금지는 복원하지 않았습니다"),
    ROAMING_DATA("해외 로밍 중이라 모바일 데이터는 복원하지 않았습니다"),
    DAILY_LIMIT("오늘 이 항목의 자동 복원이 이미 2회라 더 되돌리지 않았습니다"),
    RUN_LIMIT("이번 점검의 수정 시도 3회를 다 써서 더 시도하지 않았습니다"),
}

sealed interface Decision {
    val deviation: Deviation

    data class Restore(override val deviation: Deviation) : Decision

    /**
     * @param notify 현도에게 알려야 하는 건지
     * @param pause 이 항목의 자동 복원을 멈춤 상태로 바꿀지 (3일 연속 이탈)
     */
    data class Skip(
        override val deviation: Deviation,
        val reason: SkipReason,
        val notify: Boolean,
        val pause: Boolean = false,
    ) : Decision
}

data class PolicyContext(
    val now: LocalDateTime,
    val nightStartMin: Int,
    val nightEndMin: Int,
    val roaming: Boolean,
    /** 이번 점검에서 이미 시도한 복원 횟수. */
    val attemptsThisRun: Int,
    val maxAttemptsPerRun: Int = MAX_ATTEMPTS_PER_RUN,
) {
    companion object {
        const val MAX_ATTEMPTS_PER_RUN = 3
    }
}

/** A등급 자동 복원 판정 (설계 §5 공통 안전 규칙). 조작 결정은 이 규칙 엔진만 한다. */
class RestorePolicy(private val history: RestoreHistory) {

    /**
     * 부수효과: 야간 방해금지가 아닌 이탈은 오늘의 이탈로 기록한다 (연속 일수 계산용).
     * 야간 방해금지는 취침 일정일 수 있어 이탈로 세지 않는다.
     */
    fun decide(dev: Deviation, ctx: PolicyContext): Decision {
        val key = dev.key
        val today = ctx.now.toLocalDate()

        if (!key.autoRestorable) {
            history.recordDeviation(key, today)
            return Decision.Skip(dev, SkipReason.NOT_AUTO_RESTORABLE, notify = true)
        }
        if (key == SettingKey.DND && isNight(ctx.now.hour * 60 + ctx.now.minute, ctx.nightStartMin, ctx.nightEndMin)) {
            return Decision.Skip(dev, SkipReason.NIGHT_DND, notify = false)
        }

        history.recordDeviation(key, today)

        if (history.isPaused(key)) {
            return Decision.Skip(dev, SkipReason.PAUSED_STREAK, notify = false)
        }
        if (history.consecutiveDeviationDays(key, today) >= STREAK_LIMIT_DAYS) {
            return Decision.Skip(dev, SkipReason.STREAK_STOPPED, notify = true, pause = true)
        }
        if (key == SettingKey.MOBILE_DATA && ctx.roaming) {
            return Decision.Skip(dev, SkipReason.ROAMING_DATA, notify = true)
        }
        if (history.restoreCount(key, today) >= DAILY_RESTORE_LIMIT) {
            return Decision.Skip(dev, SkipReason.DAILY_LIMIT, notify = true)
        }
        if (ctx.attemptsThisRun >= ctx.maxAttemptsPerRun) {
            return Decision.Skip(dev, SkipReason.RUN_LIMIT, notify = true)
        }
        return Decision.Restore(dev)
    }

    companion object {
        const val DAILY_RESTORE_LIMIT = 2
        const val STREAK_LIMIT_DAYS = 3

        /** start > end 이면 자정을 넘기는 시간대. start == end 이면 야간 없음. */
        fun isNight(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean = when {
            startMin == endMin -> false
            startMin < endMin -> minuteOfDay in startMin until endMin
            else -> minuteOfDay >= startMin || minuteOfDay < endMin
        }
    }
}
