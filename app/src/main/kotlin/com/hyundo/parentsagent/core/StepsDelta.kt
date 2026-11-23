package com.hyundo.parentsagent.core

/** 24시간 걸음수 판정 결과. [steps]가 null이면 알 수 없음이고 이상 판정은 하지 않는다. */
data class StepsReading(val steps: Long?) {
    val abnormal: Boolean get() = steps != null && steps < StepsDelta.STEPS_ABNORMAL_THRESHOLD

    companion object {
        val UNKNOWN = StepsReading(null)
    }
}

/**
 * 만보기(TYPE_STEP_COUNTER) 누적값 두 개로 걸음수를 계산한다.
 * 안부 신호가 하루 1회 슬롯으로 나가므로 시간 간격은 따로 재지 않고 24시간으로 본다.
 */
object StepsDelta {
    const val STEPS_ABNORMAL_THRESHOLD = 500L

    /** 이전 값이 없거나, 현재 값이 더 작으면(재부팅으로 카운터 초기화) 알 수 없음. */
    fun compute(previous: Long?, current: Long?): StepsReading {
        if (previous == null || current == null) return StepsReading.UNKNOWN
        if (current < previous) return StepsReading.UNKNOWN
        return StepsReading(current - previous)
    }
}
