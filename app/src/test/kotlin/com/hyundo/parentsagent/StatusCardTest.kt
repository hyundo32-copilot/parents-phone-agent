package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StatusCardTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 11, 15, 15, 30, 0, 0, zone).toInstant().toEpochMilli()
    private fun ago(h: Long, min: Long = 0) = now - (h * 60 + min) * 60_000L

    private fun input(
        acc: Boolean = true, policy: Boolean = true, baseline: Boolean = true, paused: Int = 0,
        last: Long = ago(0, 20), grade: LastCheckGrade = LastCheckGrade.OK, queued: Int = 0,
    ) = StatusInput(acc, policy, baseline, paused, last, grade, queued, now, zone)

    private fun level(i: StatusInput) = StatusCard.evaluate(i).level

    @Test fun `아무 문제 없으면 초록이고 마지막 확인 시각을 보여 준다`() {
        val s = StatusCard.evaluate(input())
        assertEquals(StatusLevel.GOOD, s.level)
        assertEquals("폰이 괜찮아요", s.title)
        assertEquals("마지막 확인: 오늘 오후 3:10", s.sub)
    }

    @Test fun `마지막 점검에 실패가 있으면 빨강`() {
        val s = StatusCard.evaluate(input(grade = LastCheckGrade.FAILED))
        assertEquals(StatusLevel.BAD, s.level)
        assertEquals("가족에게 연락했어요", s.sub)
    }

    @Test fun `실패인데 못 보낸 알림이 있으면 연락을 못 보냈다고 알린다`() {
        val s = StatusCard.evaluate(input(grade = LastCheckGrade.FAILED, queued = 2))
        assertEquals(StatusLevel.BAD, s.level)
        assertTrue(s.sub.contains("못 보냈어요"))
    }

    @Test fun `빨강은 다른 노랑 사유보다 우선한다`() {
        assertEquals(StatusLevel.BAD, level(input(acc = false, grade = LastCheckGrade.FAILED)))
    }

    @Test fun `권한이 꺼져 있으면 노랑`() {
        assertEquals(StatusLevel.WARN, level(input(acc = false)))
        assertEquals(StatusLevel.WARN, level(input(policy = false)))
    }

    @Test fun `기준이 없으면 노랑`() = assertEquals(StatusLevel.WARN, level(input(baseline = false)))

    @Test fun `멈춘 항목이 있으면 노랑`() = assertEquals(StatusLevel.WARN, level(input(paused = 1)))

    @Test fun `대기열에 못 보낸 알림이 있으면 노랑`() = assertEquals(StatusLevel.WARN, level(input(queued = 1)))

    @Test fun `마지막 점검이 24시간보다 오래되면 노랑이고 딱 24시간은 아직 초록`() {
        assertEquals(StatusLevel.WARN, level(input(last = ago(24, 1))))
        assertEquals(StatusLevel.GOOD, level(input(last = ago(24))))
    }

    @Test fun `점검 기록이 없으면 알 수 없음이라 노랑 (저장 형식 호환)`() {
        assertEquals(StatusLevel.WARN, level(input(last = 0L, grade = LastCheckGrade.UNKNOWN)))
    }

    @Test fun `권한 안내가 기준 없음보다 먼저 나온다`() {
        val s = StatusCard.evaluate(input(acc = false, baseline = false))
        assertTrue(s.sub.contains("준비가 덜"))
    }

    @Test fun `등급은 복원 실패나 남은 이탈이 있을 때만 FAILED`() {
        val ok = RestoreAttempt(SettingKey.WIFI, "m", flag(false), flag(true), AttemptResult.SUCCESS)
        val bad = RestoreAttempt(SettingKey.WIFI, "m", flag(false), flag(false), AttemptResult.FAILED)
        val left = dev(SettingKey.WIFI, flag(true), flag(false))
        assertEquals(LastCheckGrade.OK, LastCheckGrade.of(emptyList(), emptyList()))
        assertEquals(LastCheckGrade.OK, LastCheckGrade.of(listOf(ok), emptyList()))
        assertEquals(LastCheckGrade.FAILED, LastCheckGrade.of(listOf(bad), emptyList()))
        assertEquals(LastCheckGrade.FAILED, LastCheckGrade.of(listOf(ok), listOf(left)))
    }

    @Test fun `저장된 문자열이 없거나 이상하면 UNKNOWN`() {
        assertEquals(LastCheckGrade.UNKNOWN, LastCheckGrade.parse(null))
        assertEquals(LastCheckGrade.UNKNOWN, LastCheckGrade.parse("???"))
        assertEquals(LastCheckGrade.FAILED, LastCheckGrade.parse("FAILED"))
    }

    @Test fun `시각 표기 - 어제와 이전 날짜와 정오 자정`() {
        fun t(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("어제 오전 9:05", StatusCard.friendlyTime(t(14, 9, 5), now, zone))
        assertEquals("11월 3일 오후 11:59", StatusCard.friendlyTime(t(3, 23, 59), now, zone))
        assertEquals("오늘 오후 12:00", StatusCard.friendlyTime(t(15, 12, 0), now, zone))
        assertEquals("오늘 오전 12:07", StatusCard.friendlyTime(t(15, 0, 7), now, zone))
    }
}
