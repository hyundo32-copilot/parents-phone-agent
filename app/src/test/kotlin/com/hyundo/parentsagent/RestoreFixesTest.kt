package com.hyundo.parentsagent

import android.media.AudioManager
import com.hyundo.parentsagent.android.Restorer
import com.hyundo.parentsagent.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RestoreFixesTest {
    // --- 3: 벨소리 모드는 기준값으로 ---

    @Test fun `벨소리 모드는 기준 이름대로 복원한다 - 진동 기준이면 진동`() {
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, Restorer.ringerModeFor("VIBRATE"))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, Restorer.ringerModeFor("NORMAL"))
        assertEquals(AudioManager.RINGER_MODE_SILENT, Restorer.ringerModeFor("SILENT"))
    }

    // --- 5: 멈춘 항목 다시 켜기 ---

    private val today = LocalDate.of(2026, 11, 13)
    private val d = dev(SettingKey.WIFI, flag(true), flag(false))

    private fun pauseByStreak(h: RestoreHistory) {
        listOf(0L, 1L, 2L).forEach { h.recordDeviation(SettingKey.WIFI, today.minusDays(it)) }
        val r = RestorePolicy(h).decide(d, ctx(at(2026, 11, 13, 12)))
        assertTrue(r is Decision.Skip && r.pause)
        h.pause(SettingKey.WIFI)
    }

    @Test fun `일시정지 뒤 resume 하면 다시 복원 판정이 나온다`() {
        val h = RestoreHistory()
        pauseByStreak(h)
        assertTrue(RestorePolicy(h).decide(d, ctx(at(2026, 11, 13, 13))) is Decision.Skip)
        h.resume(SettingKey.WIFI)
        assertFalse(h.isPaused(SettingKey.WIFI))
        assertTrue(RestorePolicy(h).decide(d, ctx(at(2026, 11, 13, 14))) is Decision.Restore)
    }

    @Test fun `멈춤 상태는 JSON 왕복 뒤에도 유지되고 resume 결과도 저장된다`() {
        val h = RestoreHistory()
        pauseByStreak(h)
        val back = RestoreHistory.fromJson(h.toJson(today))
        assertEquals(setOf(SettingKey.WIFI), back.pausedKeys())
        back.resume(SettingKey.WIFI)
        assertTrue(RestoreHistory.fromJson(back.toJson(today)).pausedKeys().isEmpty())
    }

    @Test fun `resumeAll 은 멈춘 항목 전부와 이탈 기록을 지운다`() {
        val h = RestoreHistory()
        h.pause(SettingKey.WIFI); h.pause(SettingKey.AIRPLANE_MODE)
        h.recordDeviation(SettingKey.WIFI, today)
        h.resumeAll()
        assertTrue(h.pausedKeys().isEmpty())
        assertEquals(0, h.consecutiveDeviationDays(SettingKey.WIFI, today))
    }
}
