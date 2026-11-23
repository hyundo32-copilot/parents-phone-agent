package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.HeartbeatMessage
import com.hyundo.parentsagent.core.HeartbeatSchedule
import com.hyundo.parentsagent.core.StepsReading
import com.hyundo.parentsagent.net.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HeartbeatTest {
    private val hour = 9

    private class Mem(var last: LocalDate? = null)

    private fun sender(t: FakeTransport, mem: Mem, token: String = "TOKEN", healthy: () -> Boolean = { true }) =
        HeartbeatSender(TelegramClient(token, "CHAT", t), hour, { mem.last }, { mem.last = it }, healthy)

    private fun sentText(t: FakeTransport, i: Int = 0) = JSONObject(t.calls[i].body).getString("text")

    // --- 메시지 조립 ---

    @Test fun `정상 메시지는 정상과 보낸 시각과 걸음수만 담는다`() {
        val m = HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 9, 5), StepsReading.UNKNOWN)
        assertEquals("[부모님 폰 안부 신호] 정상\n보낸 시각: 2026-11-13 09:05\n걸음수(24시간): 알 수 없음", m)
        assertFalse(m.contains("지연됨"))
    }

    @Test fun `1시간 미만 늦으면 지연 표시가 없다`() {
        val m = HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 9, 59), StepsReading.UNKNOWN)
        assertFalse(m.contains("지연됨"))
    }

    @Test fun `정확히 1시간 늦으면 지연됨 표시`() {
        val m = HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 10), StepsReading.UNKNOWN)
        assertEquals("[부모님 폰 안부 신호] 정상 (지연됨)\n보낸 시각: 2026-11-13 10:00\n걸음수(24시간): 알 수 없음", m)
    }

    @Test fun `걸음수 정상은 머리말 그대로에 걸음수 줄만 추가된다`() {
        assertEquals(
            "[부모님 폰 안부 신호] 정상\n보낸 시각: 2026-11-13 09:05\n걸음수(24시간): 1,234보",
            HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 9, 5), StepsReading(1234)),
        )
        assertEquals(
            "[부모님 폰 안부 신호] 정상 (지연됨)\n보낸 시각: 2026-11-13 10:00\n걸음수(24시간): 1,234보",
            HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 10), StepsReading(1234)),
        )
    }

    @Test fun `걸음수 이상은 머리말이 걸음수 적음으로 바뀐다`() {
        assertEquals(
            "[부모님 폰 안부 신호] 정상 — 걸음수 적음\n보낸 시각: 2026-11-13 09:05\n걸음수(24시간): 123보",
            HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 9, 5), StepsReading(123)),
        )
        assertEquals(
            "[부모님 폰 안부 신호] 정상 (지연됨) — 걸음수 적음\n보낸 시각: 2026-11-13 10:00\n걸음수(24시간): 0보",
            HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 10), StepsReading(0)),
        )
    }

    @Test fun `걸음수 알 수 없음은 이상 표시 없이 머리말 그대로`() {
        assertEquals(
            "[부모님 폰 안부 신호] 정상 (지연됨)\n보낸 시각: 2026-11-13 10:00\n걸음수(24시간): 알 수 없음",
            HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 10), StepsReading.UNKNOWN),
        )
    }

    @Test fun `500보는 정상이고 499보는 적음`() {
        assertFalse(HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 9, 5), StepsReading(500)).contains("걸음수 적음"))
        assertTrue(HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 9, 5), StepsReading(499)).contains("걸음수 적음"))
    }

    @Test fun `큰 걸음수도 천 단위 구분자로 표기`() {
        assertTrue(HeartbeatMessage.build(at(2026, 11, 13, 9), at(2026, 11, 13, 9, 5), StepsReading(1234567)).endsWith("걸음수(24시간): 1,234,567보"))
    }

    // --- 걸음수 연결 ---

    private class StepMem(var last: Long?, var read: Long?) { val saves = mutableListOf<Long>() }

    private fun stepSender(t: FakeTransport, mem: Mem, steps: StepMem) =
        HeartbeatSender(
            TelegramClient("TOKEN", "CHAT", t), hour, { mem.last }, { mem.last = it },
            readStepCount = { steps.read }, loadLastStepCount = { steps.last },
            saveStepCount = { steps.last = it; steps.saves += it },
        )

    @Test fun `전송 성공하면 새 만보기 값을 저장하고 델타를 메시지에 담는다`() {
        val t = FakeTransport(mutableListOf({ ok("{}") }))
        val steps = StepMem(last = 1000, read = 1200)
        assertEquals(HeartbeatOutcome.SENT, stepSender(t, Mem(), steps).trySend(at(2026, 11, 13, 9, 1)))
        assertTrue(sentText(t).contains("정상 — 걸음수 적음"))
        assertTrue(sentText(t).endsWith("걸음수(24시간): 200보"))
        assertEquals(listOf(1200L), steps.saves)
    }

    @Test fun `전송 실패하면 만보기 값을 저장하지 않는다`() {
        val t = FakeTransport(mutableListOf(netDown()))
        val steps = StepMem(last = 1000, read = 9000)
        assertEquals(HeartbeatOutcome.DEFERRED, stepSender(t, Mem(), steps).trySend(at(2026, 11, 13, 9, 1)))
        assertTrue(steps.saves.isEmpty())
    }

    @Test fun `읽기에 실패해도 신호는 나가고 저장된 값은 그대로다`() {
        val t = FakeTransport(mutableListOf({ ok("{}") }))
        val steps = StepMem(last = 1000, read = null)
        assertEquals(HeartbeatOutcome.SENT, stepSender(t, Mem(), steps).trySend(at(2026, 11, 13, 9, 1)))
        assertTrue(sentText(t).endsWith("걸음수(24시간): 알 수 없음"))
        assertTrue(steps.saves.isEmpty())
        assertEquals(1000L, steps.last)
    }

    @Test fun `재부팅으로 값이 줄면 알 수 없음으로 보내고 현재 값을 새 기준으로 저장`() {
        val t = FakeTransport(mutableListOf({ ok("{}") }))
        val steps = StepMem(last = 50000, read = 300)
        stepSender(t, Mem(), steps).trySend(at(2026, 11, 13, 9, 1))
        assertTrue(sentText(t).endsWith("걸음수(24시간): 알 수 없음"))
        assertEquals(listOf(300L), steps.saves)
    }

    @Test fun `이전 값이 없으면 알 수 없음이고 현재 값을 저장한다`() {
        val t = FakeTransport(mutableListOf({ ok("{}") }))
        val steps = StepMem(last = null, read = 4000)
        stepSender(t, Mem(), steps).trySend(at(2026, 11, 13, 9, 1))
        assertTrue(sentText(t).endsWith("걸음수(24시간): 알 수 없음"))
        assertEquals(listOf(4000L), steps.saves)
    }

    @Test fun `미리보기는 전송도 저장도 하지 않는다`() {
        val t = FakeTransport(mutableListOf())
        val mem = Mem(LocalDate.of(2026, 11, 12))
        val steps = StepMem(last = 1000, read = 3500)
        val text = stepSender(t, mem, steps).previewText(at(2026, 11, 13, 9, 5))
        assertEquals("[부모님 폰 안부 신호] 정상\n보낸 시각: 2026-11-13 09:05\n걸음수(24시간): 2,500보", text)
        assertTrue(t.calls.isEmpty())
        assertTrue(steps.saves.isEmpty())
        assertEquals(LocalDate.of(2026, 11, 12), mem.last)
    }

    // --- 하루 1회 ---

    @Test fun `예정 시각 전에는 보낼 차례가 아니다`() {
        assertNull(HeartbeatSchedule.dueSlot(at(2026, 11, 13, 8, 59), hour, LocalDate.of(2026, 11, 12)))
    }

    @Test fun `이미 보낸 날의 슬롯은 다시 보내지 않는다`() {
        assertNull(HeartbeatSchedule.dueSlot(at(2026, 11, 13, 15), hour, LocalDate.of(2026, 11, 13)))
    }

    @Test fun `오늘 슬롯 전이고 어제 슬롯이 밀렸으면 어제 슬롯을 보낸다`() {
        assertEquals(at(2026, 11, 12, 9), HeartbeatSchedule.dueSlot(at(2026, 11, 13, 7), hour, LocalDate.of(2026, 11, 11)))
    }

    @Test fun `같은 날 두 번 시도해도 한 번만 전송`() {
        val t = FakeTransport(mutableListOf({ ok("{}") }))
        val mem = Mem()
        val s = sender(t, mem)
        assertEquals(HeartbeatOutcome.SENT, s.trySend(at(2026, 11, 13, 9, 1)))
        assertEquals(HeartbeatOutcome.NOT_DUE, s.trySend(at(2026, 11, 13, 9, 2)))
        assertEquals(HeartbeatOutcome.NOT_DUE, s.trySend(at(2026, 11, 13, 20)))
        assertEquals(1, t.calls.size)
        assertEquals(LocalDate.of(2026, 11, 13), mem.last)
    }

    @Test fun `다음 날 슬롯에는 다시 보낸다`() {
        val t = FakeTransport(mutableListOf({ ok("{}") }, { ok("{}") }))
        val s = sender(t, Mem())
        s.trySend(at(2026, 11, 13, 9, 1))
        assertEquals(HeartbeatOutcome.SENT, s.trySend(at(2026, 11, 14, 9, 1)))
        assertEquals(2, t.calls.size)
    }

    @Test fun `지연 전송은 SENT_DELAYED이고 메시지에 지연됨`() {
        val t = FakeTransport(mutableListOf({ ok("{}") }))
        assertEquals(HeartbeatOutcome.SENT_DELAYED, sender(t, Mem()).trySend(at(2026, 11, 13, 11, 30)))
        assertTrue(sentText(t).contains("지연됨"))
    }

    // --- 망 끊김 대기 ---

    @Test fun `망이 끊기면 보류하고 전송 완료로 기록하지 않는다`() {
        val t = FakeTransport(mutableListOf(netDown()))
        val mem = Mem(LocalDate.of(2026, 11, 12))
        assertEquals(HeartbeatOutcome.DEFERRED, sender(t, mem).trySend(at(2026, 11, 13, 9, 1)))
        assertEquals(LocalDate.of(2026, 11, 12), mem.last)
    }

    @Test fun `망이 복구되면 실제 보낸 시각과 지연됨으로 한 번만 보낸다`() {
        val t = FakeTransport(mutableListOf(netDown(), { ok("{}") }))
        val mem = Mem(LocalDate.of(2026, 11, 12))
        val s = sender(t, mem)
        s.trySend(at(2026, 11, 13, 9, 1))
        assertEquals(HeartbeatOutcome.SENT_DELAYED, s.trySend(at(2026, 11, 13, 14, 20)))
        assertEquals("[부모님 폰 안부 신호] 정상 (지연됨)\n보낸 시각: 2026-11-13 14:20\n걸음수(24시간): 알 수 없음", sentText(t, 1))
        assertEquals(HeartbeatOutcome.NOT_DUE, s.trySend(at(2026, 11, 13, 14, 21)))
        assertEquals(2, t.calls.size)
    }

    @Test fun `여러 번 실패해도 대기열이 쌓이지 않고 복구 때 한 통만 나간다`() {
        val t = FakeTransport(mutableListOf(netDown(), netDown(), netDown(), { ok("{}") }))
        val s = sender(t, Mem())
        repeat(3) { assertEquals(HeartbeatOutcome.DEFERRED, s.trySend(at(2026, 11, 13, 9 + it, 5))) }
        assertEquals(HeartbeatOutcome.SENT_DELAYED, s.trySend(at(2026, 11, 13, 12)))
        assertEquals(HeartbeatOutcome.NOT_DUE, s.trySend(at(2026, 11, 13, 13)))
    }

    // --- 건강 게이트 (접근성이 꺼지면 안부 신호가 끊겨야 현도가 안다) ---

    @Test fun `건강하지 않으면 전송 호출도 기록도 없다`() {
        val t = FakeTransport(mutableListOf())
        val mem = Mem(LocalDate.of(2026, 11, 12))
        assertEquals(HeartbeatOutcome.UNHEALTHY, sender(t, mem, healthy = { false }).trySend(at(2026, 11, 13, 9, 1)))
        assertTrue(t.calls.isEmpty())
        assertEquals(LocalDate.of(2026, 11, 12), mem.last)
    }

    @Test fun `접근성이 꺼진 동안 며칠이 지나도 안부 신호는 안 나간다`() {
        val t = FakeTransport(mutableListOf())
        val s = sender(t, Mem(), healthy = { false })
        repeat(3) { assertEquals(HeartbeatOutcome.UNHEALTHY, s.trySend(at(2026, 11, 13 + it, 9, 5))) }
        assertTrue(t.calls.isEmpty())
    }

    @Test fun `다시 건강해지면 한 통만 지연됨으로 나간다`() {
        val t = FakeTransport(mutableListOf({ ok("{}") }))
        var healthy = false
        val s = sender(t, Mem(), healthy = { healthy })
        assertEquals(HeartbeatOutcome.UNHEALTHY, s.trySend(at(2026, 11, 13, 9, 5)))
        healthy = true
        assertEquals(HeartbeatOutcome.SENT_DELAYED, s.trySend(at(2026, 11, 13, 15)))
        assertEquals(1, t.calls.size)
    }

    @Test fun `건강하면 기존처럼 정상 전송`() {
        val t = FakeTransport(mutableListOf({ ok("{}") }))
        assertEquals(HeartbeatOutcome.SENT, sender(t, Mem(), healthy = { true }).trySend(at(2026, 11, 13, 9, 1)))
    }

    @Test fun `텔레그램 설정이 비면 호출 없이 보류`() {
        val t = FakeTransport(mutableListOf())
        assertEquals(HeartbeatOutcome.DEFERRED, sender(t, Mem(), token = "").trySend(at(2026, 11, 13, 10)))
        assertTrue(t.calls.isEmpty())
    }
}
