package com.hyundo.parentsagent

import com.hyundo.parentsagent.net.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EscalationSenderTest {
    private val tg = { t: FakeTransport -> TelegramClient("TOKEN", "CHAT", t) }
    private val raw = "[부모님 폰 알림]\n증상: 정기 점검"

    @Test fun `요약 성공이면 요약문을 텔레그램으로`() {
        val liner = FakeTransport(mutableListOf({ chat("요약문") }))
        val tgt = FakeTransport(mutableListOf({ ok("{}") }))
        val outbox = Outbox()
        val r = EscalationSender(LinerClient("k", liner, RecordingSleeper()), tg(tgt), outbox).send(raw)
        assertEquals(SendOutcome.SENT, r)
        val body = JSONObject(tgt.calls[0].body)
        assertEquals("요약문", body.getString("text"))
        assertEquals("CHAT", body.getString("chat_id"))
        assertTrue(tgt.calls[0].url.endsWith("/botTOKEN/sendMessage"))
        assertEquals(0, outbox.size)
    }

    @Test fun `Liner가 안 되면 원문을 보낸다`() {
        val liner = FakeTransport(mutableListOf(netDown()))
        val tgt = FakeTransport(mutableListOf({ ok("{}") }))
        val r = EscalationSender(LinerClient("k", liner, RecordingSleeper()), tg(tgt), Outbox()).send(raw)
        assertEquals(SendOutcome.SENT_RAW, r)
        assertEquals(raw, JSONObject(tgt.calls[0].body).getString("text"))
    }

    @Test fun `Liner 키가 없어도 원문을 보낸다`() {
        val tgt = FakeTransport(mutableListOf({ ok("{}") }))
        assertEquals(SendOutcome.SENT_RAW, EscalationSender(null, tg(tgt), Outbox()).send(raw))
    }

    @Test fun `망이 끊기면 원문을 대기열에 넣고 저장 콜백 호출`() {
        val tgt = FakeTransport(mutableListOf(netDown()))
        val outbox = Outbox()
        var persisted = 0
        val r = EscalationSender(null, tg(tgt), outbox) { persisted++ }.send(raw)
        assertEquals(SendOutcome.QUEUED, r)
        assertEquals(raw, outbox.peek())
        assertEquals(1, persisted)
    }

    @Test fun `텔레그램 설정이 비면 요약 호출 없이 대기열`() {
        val liner = FakeTransport(mutableListOf())
        val outbox = Outbox()
        val r = EscalationSender(LinerClient("k", liner, RecordingSleeper()), TelegramClient("", ""), outbox).send(raw)
        assertEquals(SendOutcome.QUEUED, r)
        assertTrue(liner.calls.isEmpty())
        assertEquals(1, outbox.size)
    }

    @Test fun `망 복구 후 flush는 순서대로 보내고 실패하면 멈춘다`() {
        val outbox = Outbox()
        listOf("A", "B", "C").forEach { outbox.enqueue(it) }
        val tgt = FakeTransport(mutableListOf({ ok("{}") }, { ok("{}") }, netDown()))
        val sent = EscalationSender(null, tg(tgt), outbox).flush()
        assertEquals(2, sent)
        assertEquals("C", outbox.peek())
        assertEquals(1, outbox.size)
        assertEquals("A", JSONObject(tgt.calls[0].body).getString("text"))
    }

    @Test fun `Outbox JSON 왕복과 상한`() {
        val o = Outbox(maxSize = 2)
        listOf("1", "2", "3").forEach { o.enqueue(it) }
        assertEquals("2", o.peek())
        assertEquals(2, Outbox(o.toJson()).size)
    }

    @Test fun `텔레그램 HTTP 오류는 예외`() {
        val tgt = FakeTransport(mutableListOf({ HttpResponse(400, emptyMap(), "") }))
        assertThrows(HttpStatusException::class.java) { tg(tgt).send("x") }
    }
}
