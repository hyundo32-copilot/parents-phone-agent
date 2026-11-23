package com.hyundo.parentsagent.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 안부 신호 메시지 (설계 §4). 정상·보낸 시각·24시간 걸음수만 담는다. 설정값·메시지 내용은 넣지 않는다. */
object HeartbeatMessage {
    val DELAY_THRESHOLD: Duration = Duration.ofHours(1)
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun isDelayed(scheduledAt: LocalDateTime, sentAt: LocalDateTime): Boolean =
        Duration.between(scheduledAt, sentAt) >= DELAY_THRESHOLD

    fun build(scheduledAt: LocalDateTime, sentAt: LocalDateTime, steps: StepsReading): String {
        val base = if (isDelayed(scheduledAt, sentAt)) "[부모님 폰 안부 신호] 정상 (지연됨)" else "[부모님 폰 안부 신호] 정상"
        val head = if (steps.abnormal) "$base — 걸음수 적음" else base
        val stepsText = steps.steps?.let { "%,d보".format(Locale.US, it) } ?: "알 수 없음"
        return "$head\n보낸 시각: ${sentAt.format(FMT)}\n걸음수(24시간): $stepsText"
    }
}

/** 하루 1회 슬롯 계산. 슬롯 날짜 하나당 한 번만 보낸다. */
object HeartbeatSchedule {
    /** 지금 보내야 할 가장 최근 슬롯 시각. 보낼 차례가 아니면 null. */
    fun dueSlot(now: LocalDateTime, hour: Int, lastServedSlotDate: LocalDate?): LocalDateTime? {
        var slot = now.toLocalDate().atTime(hour, 0)
        if (slot.isAfter(now)) slot = slot.minusDays(1)
        if (lastServedSlotDate != null && !slot.toLocalDate().isAfter(lastServedSlotDate)) return null
        return slot
    }
}
