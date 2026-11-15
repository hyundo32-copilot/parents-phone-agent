package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RestorePolicyTest {
    private val history = RestoreHistory()
    private val policy = RestorePolicy(history)
    private val noon = at(2026, 11, 20, 12)

    private val airplane = dev(SettingKey.AIRPLANE_MODE, flag(false), flag(true))
    private val dnd = dev(SettingKey.DND, flag(false), flag(true))
    private val data = dev(SettingKey.MOBILE_DATA, flag(true), flag(false))
    private val wifi = dev(SettingKey.WIFI, flag(true), flag(false))

    @Test fun `안전 조건이 없으면 복원`() {
        assertTrue(policy.decide(airplane, ctx(noon)) is Decision.Restore)
    }

    @Test fun `야간에는 방해금지를 복원하지 않고 알리지도 않는다`() {
        val d = policy.decide(dnd, ctx(at(2026, 11, 20, 23, 30))) as Decision.Skip
        assertEquals(SkipReason.NIGHT_DND, d.reason)
        assertFalse(d.notify)
    }

    @Test fun `자정 넘긴 야간 구간 새벽도 야간`() {
        val d = policy.decide(dnd, ctx(at(2026, 11, 21, 3)))
        assertTrue(d is Decision.Skip && d.reason == SkipReason.NIGHT_DND)
        assertTrue(policy.decide(dnd, ctx(at(2026, 11, 21, 7))) is Decision.Restore)
    }

    @Test fun `야간이어도 방해금지 외 항목은 복원한다`() {
        assertTrue(policy.decide(airplane, ctx(at(2026, 11, 20, 23))) is Decision.Restore)
    }

    @Test fun `야간 방해금지는 연속 이탈 일수에 세지 않는다`() {
        repeat(3) { i -> policy.decide(dnd, ctx(at(2026, 11, 18 + i, 23))) }
        assertEquals(0, history.consecutiveDeviationDays(SettingKey.DND, LocalDate.of(2026, 11, 20)))
    }

    @Test fun `해외 로밍 중에는 데이터를 복원하지 않고 현도에게 알린다`() {
        val d = policy.decide(data, ctx(noon, roaming = true)) as Decision.Skip
        assertEquals(SkipReason.ROAMING_DATA, d.reason)
        assertTrue(d.notify)
        // 로밍이어도 와이파이는 복원
        assertTrue(policy.decide(wifi, ctx(noon, roaming = true)) is Decision.Restore)
    }

    @Test fun `같은 항목 하루 2회까지만 복원하고 3번째부터 멈추고 알린다`() {
        val day = noon.toLocalDate()
        assertTrue(policy.decide(airplane, ctx(noon)) is Decision.Restore)
        history.recordRestoreAttempt(SettingKey.AIRPLANE_MODE, day)
        assertTrue(policy.decide(airplane, ctx(noon.plusHours(2))) is Decision.Restore)
        history.recordRestoreAttempt(SettingKey.AIRPLANE_MODE, day)
        val d = policy.decide(airplane, ctx(noon.plusHours(4))) as Decision.Skip
        assertEquals(SkipReason.DAILY_LIMIT, d.reason)
        assertTrue(d.notify)
    }

    @Test fun `수동 점검은 일일 한도와 점검당 한도를 적용하지 않는다`() {
        val day = noon.toLocalDate()
        repeat(2) { history.recordRestoreAttempt(SettingKey.AIRPLANE_MODE, day) }
        // 자동은 막히지만 수동은 복원한다
        assertTrue(policy.decide(airplane, ctx(noon, manual = true)) is Decision.Restore)
        assertTrue(policy.decide(airplane, ctx(noon, attempts = 3, manual = true)) is Decision.Restore)
        val d = policy.decide(airplane, ctx(noon)) as Decision.Skip
        assertEquals(SkipReason.DAILY_LIMIT, d.reason)
    }

    @Test fun `일일 한도는 항목별이고 다음 날 초기화된다`() {
        val day = noon.toLocalDate()
        repeat(2) { history.recordRestoreAttempt(SettingKey.AIRPLANE_MODE, day) }
        assertTrue(policy.decide(wifi, ctx(noon)) is Decision.Restore)
        assertTrue(policy.decide(airplane, ctx(noon.plusDays(1))) is Decision.Restore)
    }

    @Test fun `3일 연속 이탈이면 멈추고 현도에게 묻는다`() {
        assertTrue(policy.decide(airplane, ctx(at(2026, 11, 18, 12))) is Decision.Restore)
        assertTrue(policy.decide(airplane, ctx(at(2026, 11, 19, 12))) is Decision.Restore)
        val d = policy.decide(airplane, ctx(at(2026, 11, 20, 12))) as Decision.Skip
        assertEquals(SkipReason.STREAK_STOPPED, d.reason)
        assertTrue(d.notify)
        assertTrue(d.pause)
    }

    @Test fun `하루 건너뛰면 연속이 끊긴다`() {
        policy.decide(airplane, ctx(at(2026, 11, 17, 12)))
        policy.decide(airplane, ctx(at(2026, 11, 19, 12)))
        assertTrue(policy.decide(airplane, ctx(at(2026, 11, 20, 12))) is Decision.Restore)
    }

    @Test fun `멈춘 항목은 이후 점검에서 조용히 건너뛰고 resume하면 풀린다`() {
        history.pause(SettingKey.AIRPLANE_MODE)
        val d = policy.decide(airplane, ctx(noon)) as Decision.Skip
        assertEquals(SkipReason.PAUSED_STREAK, d.reason)
        assertFalse(d.notify)
        history.resume(SettingKey.AIRPLANE_MODE)
        assertTrue(policy.decide(airplane, ctx(noon)) is Decision.Restore)
    }

    @Test fun `한 번의 점검에서 시도는 3회까지`() {
        val d = policy.decide(airplane, ctx(noon, attempts = 3)) as Decision.Skip
        assertEquals(SkipReason.RUN_LIMIT, d.reason)
        assertTrue(policy.decide(airplane, ctx(noon, attempts = 2)) is Decision.Restore)
    }

    @Test fun `자동 복원 대상이 아닌 항목(SIM)은 알리기만`() {
        val sim = dev(SettingKey.SIM_READY, flag(true), flag(false))
        val d = policy.decide(sim, ctx(noon)) as Decision.Skip
        assertEquals(SkipReason.NOT_AUTO_RESTORABLE, d.reason)
        assertTrue(d.notify)
    }

    @Test fun `isNight 경계`() {
        assertTrue(RestorePolicy.isNight(22 * 60, 22 * 60, 7 * 60))
        assertFalse(RestorePolicy.isNight(7 * 60, 22 * 60, 7 * 60))
        assertFalse(RestorePolicy.isNight(12 * 60, 22 * 60, 7 * 60))
        assertTrue(RestorePolicy.isNight(60, 0, 6 * 60)) // 자정 안 넘기는 구간
        assertFalse(RestorePolicy.isNight(0, 5, 5)) // 시작==끝이면 야간 없음
    }

    @Test fun `통지 중복 방지와 JSON 왕복`() {
        val day = LocalDate.of(2026, 11, 20)
        assertTrue(history.markNotified(SettingKey.WIFI, day, "DAILY_LIMIT"))
        assertFalse(history.markNotified(SettingKey.WIFI, day, "DAILY_LIMIT"))
        history.recordRestoreAttempt(SettingKey.WIFI, day)
        history.recordDeviation(SettingKey.WIFI, day)
        history.pause(SettingKey.DND)
        val back = RestoreHistory.fromJson(history.toJson(day))
        assertEquals(1, back.restoreCount(SettingKey.WIFI, day))
        assertEquals(1, back.consecutiveDeviationDays(SettingKey.WIFI, day))
        assertTrue(back.isPaused(SettingKey.DND))
        assertFalse(back.markNotified(SettingKey.WIFI, day, "DAILY_LIMIT"))
    }
}
