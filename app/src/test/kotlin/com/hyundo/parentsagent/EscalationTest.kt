package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.*
import org.junit.Assert.*
import org.junit.Test

class EscalationTest {
    private val air = dev(SettingKey.AIRPLANE_MODE, flag(false), flag(true))
    private val wifi = dev(SettingKey.WIFI, flag(true), flag(false))

    private fun attempt(d: Deviation, r: AttemptResult) = RestoreAttempt(
        d.key, "접근성 빠른 설정 탭", d.actual, if (r == AttemptResult.SUCCESS) d.expected else d.actual, r,
    )

    private fun report(
        symptom: Symptom? = null,
        deviations: List<Deviation> = listOf(air),
        attempts: List<RestoreAttempt> = emptyList(),
        skipped: List<SkippedItem> = emptyList(),
        remaining: List<Deviation> = emptyList(),
    ) = EscalationReport(symptom, deviations, attempts, skipped, remaining)

    @Test fun `메시지에 증상, 이탈, 시도와 전후 수치, 재부팅, 남은 증상이 들어간다`() {
        val msg = EscalationMessage.build(
            report(
                symptom = Symptom.CALL_NOT_MADE,
                attempts = listOf(attempt(air, AttemptResult.FAILED)),
                remaining = listOf(air),
            ),
        )
        assertTrue(msg.contains("전화가 안 걸려요"))
        assertTrue(msg.contains("비행기 모드: 기준 꺼짐 → 현재 켜짐"))
        assertTrue(msg.contains("1) 비행기 모드 — 접근성 빠른 설정 탭: 켜짐 → 켜짐 (실패)"))
        assertTrue(msg.contains("재부팅: 하지 않음"))
        assertTrue(msg.contains("남은 이탈:"))
    }

    @Test fun `시도 내역은 최대 3개만`() {
        val many = List(5) { attempt(air, AttemptResult.FAILED) }
        val msg = EscalationMessage.build(report(attempts = many))
        assertTrue(msg.contains("3) "))
        assertFalse(msg.contains("4) "))
    }

    @Test fun `복원하지 않은 항목과 사유가 들어간다`() {
        val msg = EscalationMessage.build(report(skipped = listOf(SkippedItem(wifi, SkipReason.DAILY_LIMIT))))
        assertTrue(msg.contains("복원하지 않은 항목:"))
        assertTrue(msg.contains("와이파이: ${SkipReason.DAILY_LIMIT.text}"))
    }

    @Test fun `정기 점검 증상 표기와 한 줄짜리가 아니다`() {
        val msg = EscalationMessage.build(report())
        assertTrue(msg.contains("증상: 정기 점검"))
        assertTrue(msg.lines().size > 5)
    }

    @Test fun `분류 불가 증상은 그대로 표기`() {
        val msg = EscalationMessage.build(report(symptom = Symptom.OTHER))
        assertTrue(msg.contains("분류 불가"))
    }

    @Test fun `전부 성공하면 에스컬레이션하지 않는다`() {
        assertFalse(report(attempts = listOf(attempt(air, AttemptResult.SUCCESS))).needsEscalation())
    }

    @Test fun `실패, 남은 이탈, 알림 대상 건너뜀이면 에스컬레이션`() {
        assertTrue(report(attempts = listOf(attempt(air, AttemptResult.FAILED))).needsEscalation())
        assertTrue(report(remaining = listOf(air)).needsEscalation())
        assertTrue(report(skipped = listOf(SkippedItem(wifi, SkipReason.DAILY_LIMIT))).needsEscalation())
    }

    @Test fun `부모님이 중단한 것만으로는 에스컬레이션하지 않는다`() {
        assertFalse(report(attempts = listOf(attempt(air, AttemptResult.STOPPED_BY_USER))).needsEscalation())
    }

    @Test fun `야간 방해금지 건너뜀만 있으면 에스컬레이션하지 않는다`() {
        val d = dev(SettingKey.DND, flag(false), flag(true))
        assertFalse(report(deviations = listOf(d), skipped = listOf(SkippedItem(d, SkipReason.NIGHT_DND))).needsEscalation())
    }

    @Test fun `증상 신고인데 이탈이 없으면 에스컬레이션`() {
        assertTrue(report(symptom = Symptom.SMS_NOT_RECEIVED, deviations = emptyList()).needsEscalation())
        assertFalse(report(symptom = null, deviations = emptyList()).needsEscalation())
    }
}
