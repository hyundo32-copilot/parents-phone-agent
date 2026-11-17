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
        /** 화면이 꺼진 상태(apiOnly)에서 방해금지 일반 API가 먹히는지. 기본은 안 됨(알림 정책 접근 없음). */
        var dndApiWorks = false

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
        override fun restore(dev: Deviation, apiOnly: Boolean): RestoreAttempt {
            if (apiOnly && dev.key == SettingKey.DND && !dndApiWorks) {
                events += "api-defer:${dev.key}"
                return RestoreAttempt(dev.key, "화면 꺼짐", dev.actual, dev.actual, AttemptResult.DEFERRED)
            }
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
        var savedResult: Pair<Long, LastCheckGrade>? = null
        override fun saveLastResult(atMillis: Long, grade: LastCheckGrade) { savedResult = atMillis to grade }
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

    @Test fun `고친 기록에 복원 수단·진단(method)이 함께 남는다`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.failKeys = setOf(SettingKey.WIFI)
        f.runner().run(Trigger.SCHEDULED)
        assertTrue(f.logs.any { it.contains("(실패)") && it.endsWith(" — 가짜") })
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

    // --- 상태 카드용 마지막 점검 결과 저장 ---

    @Test fun `정상 점검은 시각과 OK 등급을 저장한다`() {
        val f = Fake()
        f.runner().run(Trigger.MANUAL)
        assertEquals(5_000L to LastCheckGrade.OK, f.savedResult)
    }

    @Test fun `복원에 실패하면 FAILED 등급을 저장한다`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.failKeys = setOf(SettingKey.WIFI)
        f.runner().run(Trigger.MANUAL)
        assertEquals(LastCheckGrade.FAILED, f.savedResult?.second)
    }

    @Test fun `고쳐진 점검은 OK 등급이다`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.runner().run(Trigger.MANUAL)
        assertEquals(LastCheckGrade.OK, f.savedResult?.second)
    }

    @Test fun `중단한 점검은 이전 결과를 덮어쓰지 않는다`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.stopAfterRestore = true
        f.runner().run(Trigger.MANUAL)
        assertNull(f.savedResult)
    }

    // --- 08:00 벨소리 복원 실패 수정: 방해금지 먼저, 화면 꺼짐에서도 일반 API ---

    @Test fun `화면이 꺼져도 방해금지 API가 되면 방해금지를 먼저 고치고 벨소리도 고친다`() {
        val f = Fake()
        f.blocker = "화면 꺼짐"
        f.dndApiWorks = true
        f.current[SettingKey.DND] = flag(true)
        f.current[SettingKey.RINGER_MODE] = SettingValue.Mode("SILENT")
        f.runner().run(Trigger.SCHEDULED)
        assertEquals(listOf("DND", "RINGER_MODE"), f.restored())
        assertEquals(flag(false), f.current[SettingKey.DND])
        assertEquals(SettingValue.Mode("NORMAL"), f.current[SettingKey.RINGER_MODE])
        assertEquals(5_000L, f.lastCheckMillis) // 미룬 항목 없음
        assertTrue(f.sent.isEmpty())
        assertEquals(LastCheckGrade.OK, f.savedResult?.second)
    }

    @Test fun `화면이 꺼져 있고 방해금지 API가 안 되면 미룸으로 한 줄만 남기고 횟수·이탈 일수에 안 센다`() {
        val f = Fake()
        f.blocker = "화면 꺼짐"
        f.current[SettingKey.DND] = flag(true)
        val outcome = f.runner().run(Trigger.SCHEDULED) as CheckOutcome.Done
        assertTrue(f.restored().isEmpty())
        assertEquals(listOf("api-defer:DND"), f.events.filter { it.startsWith("api-defer") })
        assertEquals(1, f.logs.count { it == "방해금지: 화면 꺼짐이라 잠금 해제 때 고칠게요" })
        val today = now.toLocalDate()
        assertEquals(0, f.hist.restoreCount(SettingKey.DND, today))
        assertEquals(0, f.hist.consecutiveDeviationDays(SettingKey.DND, today))
        assertEquals(0L, f.lastCheckMillis)
        assertTrue(f.sent.isEmpty())
        assertTrue(outcome.summary.contains("방해금지"))
        assertEquals(LastCheckGrade.OK, f.savedResult?.second)
    }

    @Test fun `미룸이어도 그날 이미 기록된 이탈 일수는 지우지 않는다`() {
        val f = Fake()
        f.blocker = "화면 꺼짐"
        f.current[SettingKey.DND] = flag(true)
        f.hist.recordDeviation(SettingKey.DND, now.toLocalDate())
        f.runner().run(Trigger.SCHEDULED)
        assertEquals(1, f.hist.consecutiveDeviationDays(SettingKey.DND, now.toLocalDate()))
    }

    @Test fun `방해금지를 고친 뒤 벨소리가 이미 정상이면 건드리지 않고 횟수에도 안 센다`() {
        val f = Fake()
        f.current[SettingKey.DND] = flag(true)
        f.current[SettingKey.RINGER_MODE] = SettingValue.Mode("SILENT")
        f.onRestore = { if (it.key == SettingKey.DND) f.current[SettingKey.RINGER_MODE] = SettingValue.Mode("NORMAL") }
        f.runner().run(Trigger.SCHEDULED)
        assertEquals(listOf("DND"), f.restored())
        assertEquals(0, f.hist.restoreCount(SettingKey.RINGER_MODE, now.toLocalDate()))
        assertEquals(0, f.hist.consecutiveDeviationDays(SettingKey.RINGER_MODE, now.toLocalDate()))
        assertEquals(1, f.logs.count { it.startsWith("벨소리 모드:") && it.contains("(이미 정상)") })
        assertTrue(f.sent.isEmpty())
    }

    @Test fun `벨소리 모드 복원이 알림 정책 접근 없음으로 실패해도 다음 항목을 계속 고친다`() {
        val f = Fake()
        f.current[SettingKey.RINGER_MODE] = SettingValue.Mode("SILENT")
        f.current[SettingKey.VOLUME_RING] = SettingValue.Level.current(0)
        f.failKeys = setOf(SettingKey.RINGER_MODE)
        f.runner().run(Trigger.SCHEDULED)
        assertEquals(listOf("RINGER_MODE", "VOLUME_RING"), f.restored())
        assertEquals(SettingValue.Level.current(5), f.current[SettingKey.VOLUME_RING])
        assertEquals(1, f.sent.size)
    }

    // --- Done.detail (부모님 화면 문장의 재료) ---

    private fun detailOf(f: Fake, trigger: Trigger = Trigger.SCHEDULED) = (f.runner().run(trigger) as CheckOutcome.Done).detail!!

    @Test fun `detail - 하나 고치고 끝나면 fixed 1, 알림 없음`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        val d = detailOf(f)
        assertEquals(1, d.fixed); assertEquals(0, d.failed)
        assertFalse(d.escalated); assertFalse(d.familyNotified); assertFalse(d.stopped)
        assertFalse(d.allClear)
        assertTrue(d.deferredLabels.isEmpty())
    }

    @Test fun `detail - 이탈이 없으면 allClear`() {
        val d = detailOf(Fake())
        assertTrue(d.allClear)
        assertEquals(0, d.fixed); assertEquals(0, d.failed)
    }

    @Test fun `detail - 복원 실패와 알림 성공이면 failed 1, familyNotified`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.failKeys = setOf(SettingKey.WIFI)
        val d = detailOf(f)
        assertEquals(0, d.fixed); assertEquals(1, d.failed)
        assertTrue(d.escalated); assertTrue(d.familyNotified)
    }

    @Test fun `detail - 알림이 거부되거나 대기열에 들어가면 알렸다고 하지 않는다`() {
        for (outcome in listOf(SendOutcome.REJECTED, SendOutcome.QUEUED)) {
            val f = Fake()
            f.current[SettingKey.WIFI] = flag(false)
            f.failKeys = setOf(SettingKey.WIFI)
            f.sendOutcome = outcome
            val d = detailOf(f)
            assertTrue("$outcome", d.escalated)
            assertFalse("$outcome", d.familyNotified)
        }
    }

    @Test fun `detail - 미룬 항목은 deferredLabels에 담긴다`() {
        val f = Fake()
        f.blocker = "화면 꺼짐"
        f.current[SettingKey.WIFI] = flag(false)
        val d = detailOf(f)
        assertEquals(listOf(SettingKey.WIFI.label), d.deferredLabels)
        assertEquals(0, d.fixed)
        assertFalse(d.escalated)
    }

    @Test fun `detail - 중단하면 stopped, 알림 없음`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.current[SettingKey.MOBILE_DATA] = flag(false)
        f.stopAfterRestore = true
        val d = detailOf(f)
        assertTrue(d.stopped)
        assertFalse(d.escalated)
    }

    @Test fun `detail - 읽지 못한 항목만 있으면 allClear가 아니다`() {
        val f = Fake()
        f.current.remove(SettingKey.SIM_READY)
        val d = detailOf(f)
        assertFalse(d.allClear)
        assertEquals(0, d.fixed); assertEquals(0, d.failed)
    }

    @Test fun `detail 추가로 summary 문자열은 바뀌지 않는다`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.failKeys = setOf(SettingKey.WIFI)
        val o = f.runner().run(Trigger.SCHEDULED) as CheckOutcome.Done
        assertEquals("현도에게 알렸어요 (SENT)", o.summary)
    }
}
