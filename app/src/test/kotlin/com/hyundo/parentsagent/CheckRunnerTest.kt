package com.hyundo.parentsagent

import com.hyundo.parentsagent.android.*
import com.hyundo.parentsagent.core.*
import com.hyundo.parentsagent.net.Escalator
import com.hyundo.parentsagent.net.SendOutcome
import com.hyundo.parentsagent.net.Sleeper
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

/** CheckRunner 흐름 테스트. 안드로이드 없이 포트 가짜로 돌린다. */
class CheckRunnerTest {
    private val now: LocalDateTime = at(2026, 11, 13, 12) // 낮 12시: 야간 방해금지 규칙에 안 걸린다

    private fun normal(): MutableMap<SettingKey, SettingValue> = mutableMapOf(
        SettingKey.AIRPLANE_MODE to flag(false),
        SettingKey.WIFI to flag(true),
        SettingKey.MOBILE_DATA to flag(true),
        SettingKey.RINGER_MODE to SettingValue.Mode("NORMAL"),
        SettingKey.VOLUME_RING to SettingValue.Level.current(5),
        SettingKey.VOLUME_MEDIA to SettingValue.Level.current(8),
        SettingKey.DND to flag(false),
        SettingKey.NOTIF_PHONE_APP to flag(true),
        SettingKey.NOTIF_SMS_APP to flag(true),
        SettingKey.SIM_READY to flag(true),
    )

    /** 장비 + 복원 수단 가짜. events 에 호출 순서를 남긴다. */
    private inner class Fake : DeviceReader, ScreenGate, RestoreExecutor, CheckStore, Escalator, Speaker, StatusSink, StopSignal, Sleeper {
        val events = mutableListOf<String>()
        val current = normal()
        var roaming = RoamingState.HOME
        var blocker: String? = null
        var failKeys = setOf<SettingKey>()
        var onRestore: (Deviation) -> Unit = {}
        var stopAfterRestore = false

        val hist = RestoreHistory()
        val logs = mutableListOf<String>()
        val sent = mutableListOf<String>()
        var sendOutcome = SendOutcome.SENT
        override var lastCheckMillis = 0L
        override val consented = true
        var stopped = false

        override fun readAll(deep: Boolean) = current.toMap()
        override fun roamingState(): RoamingState { events += "roaming"; return roaming }
        override fun accessibilityBlocker() = blocker
        override fun restore(dev: Deviation): RestoreAttempt {
            events += "restore:${dev.key}"
            val before = current[dev.key]!!
            if (dev.key !in failKeys) current[dev.key] = if (dev.expected is SettingValue.Level) SettingValue.Level.current(dev.expected.value) else dev.expected
            onRestore(dev)
            if (stopAfterRestore) stopped = true
            val after = current[dev.key]!!
            return RestoreAttempt(dev.key, "가짜", before, after, if (dev.expected.matches(after)) AttemptResult.SUCCESS else AttemptResult.FAILED)
        }
        override fun snapshot() = baseline()
        override fun history() = hist
        override fun saveHistory(h: RestoreHistory) { events += "saveHistory" }
        override fun appendLog(text: String) { logs += text }
        override fun send(raw: String): SendOutcome { sent += raw; return sendOutcome }
        override fun speakAndWait(text: String) = true
        override fun speak(text: String) = true
        override fun fixing(text: String) {}
        override fun idle() {}
        override val requested get() = stopped
        override fun reset() { stopped = false }
        override fun sleep(ms: Long) {}

        fun runner() = CheckRunner(this, this, this, { this }, this, this, this, this, this, { now }, { 5_000L })
        fun restored() = events.filter { it.startsWith("restore:") }.map { it.removePrefix("restore:") }
    }

    // --- 차단 1: 로밍 ---

    @Test fun `데이터 항목 판정 직전에 로밍을 다시 읽는다 - 점검 중 로밍으로 바뀌면 복원 안 함`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.current[SettingKey.MOBILE_DATA] = flag(false)
        // 와이파이를 고치는 사이에 로밍 상태가 된 것을 흉내. 점검 시작 때 읽은 값(HOME)을 쓰면 데이터가 복원돼 버린다.
        f.onRestore = { if (it.key == SettingKey.WIFI) f.roaming = RoamingState.ROAMING }
        f.runner().run(Trigger.SCHEDULED)
        assertEquals(listOf("WIFI"), f.restored())
        assertEquals(1, f.events.count { it == "roaming" })
        assertTrue(f.events.indexOf("roaming") > f.events.indexOf("restore:WIFI"))
        assertEquals(flag(false), f.current[SettingKey.MOBILE_DATA])
    }

    @Test fun `비행기 모드를 복원한 점검에서는 데이터 복원을 다음 점검으로 미룬다`() {
        val f = Fake()
        f.current[SettingKey.AIRPLANE_MODE] = flag(true)
        f.current[SettingKey.MOBILE_DATA] = flag(false)
        // 낡은 판정(HOME)이 남아 있어도 데이터는 건드리면 안 된다
        f.roaming = RoamingState.HOME
        val outcome = f.runner().run(Trigger.SCHEDULED) as CheckOutcome.Done
        assertEquals(listOf("AIRPLANE_MODE"), f.restored())
        assertFalse("로밍 판정 자체를 하지 않는다 (망이 붙기 전이라 모름)", f.events.contains("roaming"))
        assertEquals(flag(false), f.current[SettingKey.MOBILE_DATA])
        assertTrue(f.sent.isEmpty()) // 미룬 항목은 알리지 않는다
        assertEquals(0L, f.lastCheckMillis) // 미룬 항목이 있으니 점검 완료로 치지 않는다
        assertEquals(0, f.hist.restoreCount(SettingKey.MOBILE_DATA, now.toLocalDate()))
        assertTrue(outcome.summary.contains("모바일 데이터"))
    }

    @Test fun `다음 점검에서 로밍이 아니면 데이터를 복원한다`() {
        val f = Fake()
        f.current[SettingKey.MOBILE_DATA] = flag(false)
        f.runner().run(Trigger.SCHEDULED)
        assertEquals(listOf("MOBILE_DATA"), f.restored())
        assertEquals(5_000L, f.lastCheckMillis)
    }

    @Test fun `로밍이거나 로밍을 모르면 데이터를 복원하지 않고 현도에게 알린다 (fail-closed)`() {
        for (state in listOf(RoamingState.ROAMING, RoamingState.UNKNOWN)) {
            val f = Fake()
            f.current[SettingKey.MOBILE_DATA] = flag(false)
            f.roaming = state
            f.runner().run(Trigger.SCHEDULED)
            assertTrue("$state", f.restored().isEmpty())
            assertEquals("$state", 1, f.sent.size)
            assertTrue(f.sent[0].contains(SkipReason.ROAMING_DATA.text))
        }
    }

    // --- 중요 4: 화면 꺼짐·잠금·통화 중 ---

    @Test fun `잠금 상태에서는 접근성 항목을 미루고 시도 횟수·알림·점검 시각에 반영하지 않는다`() {
        val f = Fake()
        f.blocker = "잠금 상태"
        f.current[SettingKey.AIRPLANE_MODE] = flag(true)
        f.current[SettingKey.WIFI] = flag(false)
        f.current[SettingKey.DND] = flag(true)
        f.current[SettingKey.NOTIF_PHONE_APP] = flag(false)
        val outcome = f.runner().run(Trigger.SCHEDULED) as CheckOutcome.Done
        assertTrue(f.restored().isEmpty())
        assertTrue(f.sent.isEmpty())
        assertEquals(0L, f.lastCheckMillis)
        listOf(SettingKey.AIRPLANE_MODE, SettingKey.WIFI, SettingKey.DND, SettingKey.NOTIF_PHONE_APP).forEach {
            assertEquals(it.name, 0, f.hist.restoreCount(it, now.toLocalDate()))
            assertEquals(it.name, 0, f.hist.consecutiveDeviationDays(it, now.toLocalDate()))
        }
        assertTrue(outcome.summary.contains("잠금"))
    }

    @Test fun `잠겨 있어도 일반 API 항목(볼륨·벨소리 모드)은 고친다`() {
        val f = Fake()
        f.blocker = "화면 꺼짐"
        f.current[SettingKey.VOLUME_RING] = SettingValue.Level.current(0)
        f.current[SettingKey.RINGER_MODE] = SettingValue.Mode("SILENT")
        f.current[SettingKey.WIFI] = flag(false)
        f.runner().run(Trigger.SCHEDULED)
        assertEquals(listOf("RINGER_MODE", "VOLUME_RING"), f.restored())
        assertEquals(0L, f.lastCheckMillis) // 와이파이가 미뤄졌으므로
    }

    @Test fun `통화 중에도 같은 방식으로 미루고 잠금이 풀리면 따라잡는다`() {
        val f = Fake()
        f.blocker = "통화 중"
        f.current[SettingKey.WIFI] = flag(false)
        f.runner().run(Trigger.SCHEDULED)
        assertTrue(f.restored().isEmpty())
        assertEquals(0L, f.lastCheckMillis)
        f.blocker = null
        f.runner().run(Trigger.UNLOCK)
        assertEquals(listOf("WIFI"), f.restored())
        assertEquals(5_000L, f.lastCheckMillis)
    }

    @Test fun `미룬 항목이 없으면 점검 시각을 갱신하고 정상이면 알리지 않는다`() {
        val f = Fake()
        f.blocker = "잠금 상태" // 이탈이 없으면 막혀도 상관없다
        val outcome = f.runner().run(Trigger.SCHEDULED) as CheckOutcome.Done
        assertEquals("모두 정상이에요", outcome.summary)
        assertEquals(5_000L, f.lastCheckMillis)
        assertTrue(f.sent.isEmpty())
    }

    // --- 나머지 흐름 (리뷰 '테스트 커버리지 공백') ---

    @Test fun `복원 실패는 에스컬레이션하고 기록을 저장한다`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.failKeys = setOf(SettingKey.WIFI)
        val outcome = f.runner().run(Trigger.SCHEDULED) as CheckOutcome.Done
        assertEquals(1, f.sent.size)
        assertTrue(outcome.summary.startsWith("현도에게 알렸어요"))
        assertTrue(f.events.contains("saveHistory"))
        assertEquals(1, f.hist.restoreCount(SettingKey.WIFI, now.toLocalDate()))
    }

    @Test fun `부모님이 중단하면 에스컬레이션하지 않고 남은 항목을 더 고치지 않는다`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.current[SettingKey.VOLUME_RING] = SettingValue.Level.current(0)
        f.stopAfterRestore = true
        val outcome = f.runner().run(Trigger.SCHEDULED) as CheckOutcome.Done
        assertEquals(listOf("WIFI"), f.restored())
        assertTrue(f.sent.isEmpty())
        assertEquals("부모님이 중단해서 멈췄어요", outcome.summary)
    }

    @Test fun `일시정지된 항목은 조용하고 remaining 으로 알리지 않는다`() {
        val f = Fake()
        f.hist.pause(SettingKey.WIFI)
        f.current[SettingKey.WIFI] = flag(false)
        f.runner().run(Trigger.SCHEDULED)
        assertTrue(f.restored().isEmpty())
        assertTrue(f.sent.isEmpty())
    }

    @Test fun `텔레그램이 거부하면 연락했다고 안내하지 않고 설정 오류로 기록`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.failKeys = setOf(SettingKey.WIFI)
        f.sendOutcome = SendOutcome.REJECTED
        val outcome = f.runner().run(Trigger.SCHEDULED) as CheckOutcome.Done
        assertEquals("알림 설정 오류로 현도에게 못 알렸어요", outcome.summary)
        assertTrue(f.logs.any { it.startsWith("알림 설정 오류") })
    }
}
