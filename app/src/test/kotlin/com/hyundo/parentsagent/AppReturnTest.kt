package com.hyundo.parentsagent

import com.hyundo.parentsagent.android.*
import com.hyundo.parentsagent.core.*
import com.hyundo.parentsagent.net.Escalator
import com.hyundo.parentsagent.net.SendOutcome
import com.hyundo.parentsagent.net.Sleeper
import org.junit.Assert.*
import org.junit.Test

/** 점검 뒤 앱 화면으로 돌아오기: 판정·반복 규칙(순수 함수)과 CheckRunner 호출 위치. */
class AppReturnTest {
    private val app = "com.hyundo.parentsagent"

    // --- 판정 ---

    @Test fun `시스템 화면 3종이면 뒤로 가기를 누른다`() {
        listOf("com.android.systemui", "com.android.settings", "com.samsung.android.settings").forEach {
            assertEquals(it, ReturnStep.PRESS_BACK, AppReturn.decide(it, app))
        }
    }

    @Test fun `앱 화면이면 이미 돌아온 것이라 중단한다`() {
        assertEquals(ReturnStep.ARRIVED, AppReturn.decide(app, app))
    }

    @Test fun `그 외 패키지면 건드리지 않고 중단한다`() {
        listOf("com.kakao.talk", "com.sec.android.app.launcher", "com.android.settings.fake").forEach {
            assertEquals(it, ReturnStep.LEAVE_ALONE, AppReturn.decide(it, app))
        }
    }

    @Test fun `최상위 창을 못 읽으면 다시 시도한다`() {
        assertEquals(ReturnStep.RETRY, AppReturn.decide(null, app))
    }

    // --- 최상위 창 ---

    private fun w(pkg: String?, h: Int = 100, ignorable: Boolean = false) = WindowSnap(pkg, h, ignorable)
    private val systemUi = w("com.android.systemui", 80)
    private val appWin = w(app)

    @Test fun `창 목록에 큰 시스템 패널이 맨 위면 앱이 뒤에 있어도 시스템 화면이다`() {
        assertEquals("com.android.systemui", AppReturn.topPackage(listOf(systemUi, appWin)))
        assertEquals(ReturnStep.PRESS_BACK, AppReturn.decide(AppReturn.topPackage(listOf(systemUi, appWin)), app))
    }

    @Test fun `상태 표시줄 같은 작은 systemui 창과 키보드는 맨 위로 안 친다`() {
        val bars = listOf(w("com.android.systemui", 4), w("com.android.inputmethod", 40, ignorable = true), w(null), appWin)
        assertEquals(app, AppReturn.topPackage(bars))
    }

    @Test fun `창 목록이 비면 최상위는 모른다`() {
        assertNull(AppReturn.topPackage(emptyList()))
        assertNull(AppReturn.topPackage(listOf(w(null))))
    }

    // --- 사이드 제스처 패드 ---

    private val sideGesture = w("com.samsung.android.sidegesturepad", 100)

    @Test fun `사이드 제스처 패드가 맨 위여도 건너뛰고 아래 앱 창을 본다`() {
        assertEquals(app, AppReturn.topPackage(listOf(sideGesture, appWin)))
        assertEquals(ReturnStep.ARRIVED, AppReturn.decide(AppReturn.topPackage(listOf(sideGesture, appWin)), app))
    }

    @Test fun `사이드 제스처 패드가 맨 위여도 아래가 설정이면 뒤로 가기를 누른다`() {
        val top = AppReturn.topPackage(listOf(sideGesture, w("com.android.settings")))
        assertEquals("com.android.settings", top)
        assertEquals(ReturnStep.PRESS_BACK, AppReturn.decide(top, app))
    }

    @Test fun `사이드 제스처 패드 아래가 진짜 다른 앱이면 건드리지 않는다`() {
        val s = Screen(listOf(sideGesture, w("com.kakao.talk"), appWin))
        val r = AppReturn.run(app, s::read, s::back) {}
        assertEquals(ReturnResult.LEFT_ALONE, r.result)
        assertEquals(0, s.backs)
        assertEquals("com.kakao.talk", r.lastPackage)
    }

    @Test fun `사이드 제스처 패드와 앱뿐이면 뒤로 가기 없이 도착이다`() {
        val s = Screen(listOf(sideGesture, appWin))
        val r = AppReturn.run(app, s::read, s::back) { s.sleeps += it }
        assertEquals(ReturnReport(ReturnResult.ARRIVED, 0, app), r)
        assertTrue(s.sleeps.isEmpty())
    }

    @Test fun `사이드 제스처 패드 위에서도 작은 systemui 창은 건너뛴다`() {
        assertEquals(app, AppReturn.topPackage(listOf(w("com.android.systemui", 4), sideGesture, appWin)))
    }

    // --- 반복 ---

    private class Screen(vararg states: List<WindowSnap>) {
        private val seq = states.toMutableList()
        var backs = 0
        val sleeps = mutableListOf<Long>()
        fun read() = if (seq.size > 1) seq.first() else seq.first()
        fun back() { backs++; if (seq.size > 1) seq.removeAt(0) }
    }

    @Test fun `시스템 화면이 계속되면 뒤로 가기를 최대 5번만 누르고 포기한다`() {
        val s = Screen(listOf(systemUi, appWin))
        val r = AppReturn.run(app, s::read, s::back) { s.sleeps += it }
        assertEquals(ReturnResult.GAVE_UP, r.result)
        assertEquals(5, r.backs)
        assertEquals("com.android.systemui", r.lastPackage)
        // 창이 안 바뀌면 시도마다 폴링을 끝까지 한다
        assertEquals(5 * AppReturn.MAX_POLLS, s.sleeps.count { it == AppReturn.POLL_MS })
    }

    @Test fun `패널이 떠 있고 활성 창이 앱이어도 뒤로 가기를 누른다`() {
        val s = Screen(listOf(systemUi, appWin), listOf(appWin))
        val r = AppReturn.run(app, s::read, s::back) { s.sleeps += it }
        assertEquals(ReturnResult.ARRIVED, r.result)
        assertEquals(1, r.backs)
    }

    @Test fun `뒤로 가기 두 번 만에 앱이 나오면 거기서 멈춘다`() {
        val s = Screen(listOf(w("com.android.systemui", 80)), listOf(w("com.android.settings")), listOf(appWin))
        val r = AppReturn.run(app, s::read, s::back) {}
        assertEquals(ReturnResult.ARRIVED, r.result)
        assertEquals(2, r.backs)
    }

    @Test fun `이미 앱이면 뒤로 가기도 대기도 없다`() {
        val s = Screen(listOf(appWin))
        val r = AppReturn.run(app, s::read, s::back) { s.sleeps += it }
        assertEquals(ReturnReport(ReturnResult.ARRIVED, 0, app), r)
        assertTrue(s.sleeps.isEmpty())
    }

    @Test fun `다른 앱이 맨 위면 뒤로 가기를 한 번도 누르지 않는다`() {
        val s = Screen(listOf(w("com.kakao.talk"), appWin))
        val r = AppReturn.run(app, s::read, s::back) {}
        assertEquals(ReturnResult.LEFT_ALONE, r.result)
        assertEquals(0, s.backs)
        assertEquals("com.kakao.talk", r.lastPackage)
    }

    @Test fun `창이 바뀔 때까지 기다린 뒤 다음 판정을 한다`() {
        // 뒤로 가기를 눌러도 두 번 읽을 때까지는 낡은 화면(설정)이 보인다. 낡은 화면을 보고 또 누르면 안 된다.
        val settings = listOf(w("com.android.settings"))
        var reads = 0
        var backs = 0
        var pressedAt = -1
        val read = {
            reads++
            when {
                backs == 0 -> settings
                reads - pressedAt >= 3 -> listOf(appWin)
                else -> settings
            }
        }
        val r = AppReturn.run(app, read, { backs++; pressedAt = reads }) {}
        assertEquals(ReturnResult.ARRIVED, r.result)
        assertEquals(1, r.backs)
    }

    @Test fun `창이 바뀐 걸 보면 한 번 더 쉰다`() {
        val s = Screen(listOf(w("com.android.settings")), listOf(appWin))
        var sleeps = mutableListOf<Long>()
        // back()은 상태를 바로 넘기므로 첫 폴링에서 바뀜을 본다
        AppReturn.run(app, s::read, s::back) { sleeps += it }
        assertEquals(listOf(AppReturn.POLL_MS, AppReturn.SETTLE_MS), sleeps)
    }

    @Test fun `창을 계속 못 읽으면 횟수를 다 쓰고 포기한다`() {
        var reads = 0
        var backs = 0
        val r = AppReturn.run(app, { reads++; emptyList() }, { backs++ }, {})
        assertEquals(ReturnResult.GAVE_UP, r.result)
        assertEquals(6, reads) // 5번 + 마지막 확인
        assertEquals(0, backs)
        assertNull(r.lastPackage)
    }

    @Test fun `창을 못 읽다가 시스템 화면이 보이면 그때 뒤로 가기를 누른다`() {
        val s = ArrayDeque(listOf(emptyList(), listOf(w("com.android.settings")), listOf(appWin)))
        var backs = 0
        val r = AppReturn.run(app, { if (s.size > 1) s.removeFirst() else s.first() }, { backs++ }, {})
        assertEquals(ReturnResult.ARRIVED, r.result)
        assertEquals(1, backs)
    }

    @Test fun `마지막 뒤로 가기가 먹었으면 포기하지 않고 도착으로 센다`() {
        val s = Screen(listOf(systemUi), listOf(systemUi), listOf(systemUi), listOf(systemUi), listOf(systemUi), listOf(appWin))
        val r = AppReturn.run(app, s::read, s::back) {}
        assertEquals(ReturnResult.ARRIVED, r.result)
        assertEquals(5, r.backs)
    }

    @Test fun `결과 기록 한 줄에 결과·횟수·마지막 패키지가 들어간다`() {
        val line = ReturnReport(ReturnResult.GAVE_UP, 5, "com.android.systemui").logLine()
        assertTrue(line, line.contains("GAVE_UP") && line.contains("5") && line.contains("com.android.systemui"))
    }

    // --- CheckRunner 호출 위치 ---

    private class FakeReturner : AppReturner {
        var calls = 0
        override fun returnToApp() { calls++ }
    }

    private class ReportingReturner(val report: ReturnReport) : AppReturner {
        var calls = 0
        override fun returnToApp() { returnToAppWithReport() }
        override fun returnToAppWithReport(): ReturnReport { calls++; return report }
    }

    private inner class Fake : DeviceReader, ScreenGate, RestoreExecutor, CheckStore, Escalator, Speaker, StatusSink, StopSignal, Sleeper {
        val current = mutableMapOf<SettingKey, SettingValue>(
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
        val hist = RestoreHistory()
        var hasBaseline = true
        var stopped = false
        override var lastCheckMillis = 0L
        override var consented = true
        override fun readAll(deep: Boolean) = current.toMap()
        override fun roamingState() = RoamingState.HOME
        override fun accessibilityBlocker(): String? = null
        override fun restore(dev: Deviation, apiOnly: Boolean): RestoreAttempt {
            val before = current[dev.key]!!
            current[dev.key] = dev.expected
            return RestoreAttempt(dev.key, "가짜", before, dev.expected, AttemptResult.SUCCESS)
        }
        override fun snapshot() = if (hasBaseline) baseline() else null
        override fun history() = hist
        override fun saveHistory(h: RestoreHistory) {}
        override fun appendLog(text: String) {}
        override fun send(raw: String) = SendOutcome.SENT
        override fun speakAndWait(text: String) = true
        override fun speak(text: String) = true
        override fun fixing(text: String) {}
        override fun idle() {}
        override val requested get() = stopped
        override fun reset() { stopped = false }
        override fun sleep(ms: Long) {}

        val returner = FakeReturner()
        fun runner() = CheckRunner(this, this, this, { this }, this, this, this, this, this, { at(2026, 11, 13, 12) }, { 5_000L }, returner)
    }

    @Test fun `수동·증상 점검은 끝에 앱으로 돌아온다`() {
        for (t in listOf(Trigger.MANUAL, Trigger.SYMPTOM)) {
            val f = Fake()
            f.current[SettingKey.WIFI] = flag(false)
            assertTrue(f.runner().run(t) is CheckOutcome.Done)
            assertEquals(t.name, 1, f.returner.calls)
        }
    }

    @Test fun `정상일 때도 수동 점검은 돌아오기를 부른다`() {
        val f = Fake()
        f.runner().run(Trigger.MANUAL)
        assertEquals(1, f.returner.calls)
    }

    @Test fun `예약·잠금 해제 점검은 앱으로 돌아오기를 부르지 않는다`() {
        for (t in listOf(Trigger.SCHEDULED, Trigger.UNLOCK)) {
            val f = Fake()
            f.current[SettingKey.WIFI] = flag(false)
            assertTrue(f.runner().run(t) is CheckOutcome.Done)
            assertEquals(t.name, 0, f.returner.calls)
        }
    }

    @Test fun `조기 반환에서는 돌아오기를 부르지 않는다`() {
        val noBaseline = Fake().apply { hasBaseline = false }
        assertEquals(CheckOutcome.NoBaseline, noBaseline.runner().run(Trigger.MANUAL))
        assertEquals(0, noBaseline.returner.calls)

        val noConsent = Fake().apply { consented = false }
        assertEquals(CheckOutcome.NoConsent, noConsent.runner().run(Trigger.MANUAL))
        assertEquals(0, noConsent.returner.calls)
    }

    @Test fun `중단된 수동 점검도 Done이면 돌아오기를 부른다`() {
        val f = Fake()
        f.current[SettingKey.WIFI] = flag(false)
        f.current[SettingKey.MOBILE_DATA] = flag(false)
        val runner = f.runner()
        // 첫 복원 알림을 말하는 사이에 [중단]이 눌린 것을 흉내
        val stopping = object : Speaker by f {
            override fun speakAndWait(text: String): Boolean { f.stopped = true; return true }
        }
        val r = CheckRunner(f, f, f, { f }, f, stopping, f, f, f, { at(2026, 11, 13, 12) }, { 5_000L }, f.returner)
        assertTrue(r.run(Trigger.MANUAL) is CheckOutcome.Done)
        assertEquals(1, f.returner.calls)
    }

    @Test fun `돌아오기가 예외를 던져도 점검 결과는 그대로 나온다`() {
        val f = Fake()
        val boom = object : AppReturner { override fun returnToApp() = error("접근성 꺼짐") }
        val r = CheckRunner(f, f, f, { f }, f, f, f, f, f, { at(2026, 11, 13, 12) }, { 5_000L }, boom)
        assertTrue(r.run(Trigger.MANUAL) is CheckOutcome.Done)
    }

    @Test fun `돌아오기 결과가 관리용 기록에 한 줄 남는다`() {
        val f = Fake()
        val logs = mutableListOf<String>()
        val store = object : CheckStore by f { override fun appendLog(text: String) { logs += text } }
        val rep = ReportingReturner(ReturnReport(ReturnResult.ARRIVED, 2, "com.hyundo.parentsagent"))
        CheckRunner(store, f, f, { f }, f, f, f, f, f, { at(2026, 11, 13, 12) }, { 5_000L }, rep).run(Trigger.MANUAL)
        assertEquals(1, rep.calls)
        assertEquals(1, logs.count { it.startsWith("앱 복귀:") })
    }
}
