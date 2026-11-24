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

    @Test fun `키를 나중에 입력하면 그때부터 요약한다`() {
        val linerClient = LinerClient("", FakeTransport(mutableListOf({ chat("요약문") })), RecordingSleeper())
        val tgt = FakeTransport(mutableListOf({ ok("{}") }, { ok("{}") }))
        val sender = EscalationSender(linerClient, tg(tgt), Outbox())
        assertEquals(SendOutcome.SENT_RAW, sender.send(raw))
        linerClient.updateKey("k")
        assertEquals(SendOutcome.SENT, sender.send(raw))
        assertEquals("요약문", JSONObject(tgt.calls[1].body).getString("text"))
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

    // --- 4xx 영구 오류는 대기열을 막지 않는다 ---

    private fun status(code: Int, body: String = "", headers: Map<String, String> = emptyMap()): () -> HttpResponse =
        { HttpResponse(code, headers, body) }

    @Test fun `400·401·403은 대기열에 넣지 않고 설정 오류로 알린다`() {
        for (code in listOf(400, 401, 403, 404)) {
            val outbox = Outbox()
            val errors = mutableListOf<String>()
            val tgt = FakeTransport(mutableListOf(status(code)))
            val r = EscalationSender(null, tg(tgt), outbox, onPermanentError = { errors += it }).send(raw)
            assertEquals("$code", SendOutcome.REJECTED, r)
            assertEquals("$code", 0, outbox.size)
            assertEquals("$code", 1, errors.size)
            assertTrue(errors[0].startsWith("알림 설정 오류"))
            assertFalse("토큰이 로그에 남으면 안 된다", errors[0].contains("TOKEN"))
        }
    }

    @Test fun `5xx는 일시 오류라 대기열에 넣는다`() {
        val outbox = Outbox()
        val tgt = FakeTransport(mutableListOf(status(502)))
        assertEquals(SendOutcome.QUEUED, EscalationSender(null, tg(tgt), outbox).send(raw))
        assertEquals(1, outbox.size)
    }

    @Test fun `flush는 영구 오류 항목을 버리고 뒤 항목을 계속 보낸다`() {
        val outbox = Outbox()
        listOf("A", "B", "C").forEach { outbox.enqueue(it) }
        val errors = mutableListOf<String>()
        var persisted = 0
        val tgt = FakeTransport(mutableListOf(status(400), { ok("{}") }, { ok("{}") }))
        val sent = EscalationSender(null, tg(tgt), outbox, onPermanentError = { errors += it }, persistOutbox = { persisted++ }).flush()
        assertEquals(2, sent)
        assertEquals(0, outbox.size)
        assertEquals(1, errors.size)
        assertEquals(listOf("A", "B", "C"), tgt.calls.map { JSONObject(it.body).getString("text") })
        assertEquals(3, persisted)
    }

    @Test fun `flush는 5xx에서는 멈추고 항목을 남긴다`() {
        val outbox = Outbox()
        listOf("A", "B").forEach { outbox.enqueue(it) }
        val tgt = FakeTransport(mutableListOf(status(503)))
        assertEquals(0, EscalationSender(null, tg(tgt), outbox).flush())
        assertEquals("A", outbox.peek())
        assertEquals(2, outbox.size)
    }

    // --- 429: retry_after 를 지킨다 ---

    @Test fun `429는 본문 retry_after 만큼 기다렸다 다시 보낸다`() {
        val sleeper = RecordingSleeper()
        val tgt = FakeTransport(mutableListOf(status(429, """{"ok":false,"parameters":{"retry_after":3}}"""), { ok("{}") }))
        val r = EscalationSender(null, tg(tgt), Outbox(), sleeper).send(raw)
        assertEquals(SendOutcome.SENT_RAW, r)
        assertEquals(listOf(3_000L), sleeper.slept)
        assertEquals(2, tgt.calls.size)
    }

    @Test fun `429는 Retry-After 헤더도 따르고 대기는 30초를 넘지 않는다`() {
        val sleeper = RecordingSleeper()
        val tgt = FakeTransport(mutableListOf(status(429, headers = mapOf("Retry-After" to "999")), { ok("{}") }))
        EscalationSender(null, tg(tgt), Outbox(), sleeper).send(raw)
        assertEquals(listOf(30_000L), sleeper.slept)
    }

    @Test fun `429가 계속되면 영구 오류가 아니라 일시 오류로 대기열에 넣는다`() {
        val sleeper = RecordingSleeper()
        val outbox = Outbox()
        val tgt = FakeTransport(MutableList(3) { status(429, """{"parameters":{"retry_after":1}}""") })
        val r = EscalationSender(null, tg(tgt), outbox, sleeper).send(raw)
        assertEquals(SendOutcome.QUEUED, r)
        assertEquals(1, outbox.size)
        assertEquals(3, tgt.calls.size)
        assertEquals(2, sleeper.slept.size)
    }

    @Test fun `flush 중 429도 기다렸다 다시 보낸다`() {
        val sleeper = RecordingSleeper()
        val outbox = Outbox().also { it.enqueue("A") }
        val tgt = FakeTransport(mutableListOf(status(429, """{"parameters":{"retry_after":2}}"""), { ok("{}") }))
        assertEquals(1, EscalationSender(null, tg(tgt), outbox, sleeper).flush())
        assertEquals(listOf(2_000L), sleeper.slept)
    }
}
