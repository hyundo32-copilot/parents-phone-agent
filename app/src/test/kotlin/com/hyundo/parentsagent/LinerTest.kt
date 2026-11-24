package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.*
import com.hyundo.parentsagent.net.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LinerTest {
    @Test fun `분류 요청 본문 스펙`() {
        val o = JSONObject(LinerRequests.classifyBody("  문자가 안 와요  "))
        assertEquals("liner-mark-1.0", o.getString("model"))
        assertEquals("none", o.getString("reasoning_effort"))
        assertTrue(o.has("max_completion_tokens"))
        assertFalse(o.has("max_tokens"))
        assertFalse(o.has("temperature"))
        val msgs = o.getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("문자가 안 와요", msgs.getJSONObject(1).getString("content"))
        val sys = msgs.getJSONObject(0).getString("content")
        Symptom.entries.forEach { assertTrue(sys.contains(it.name)) }
    }

    @Test fun `분류 입력은 길이를 자른다`() {
        val o = JSONObject(LinerRequests.classifyBody("가".repeat(1000)))
        assertEquals(LinerRequests.CLASSIFY_MAX_INPUT_CHARS, o.getJSONArray("messages").getJSONObject(1).getString("content").length)
    }

    @Test fun `요약 요청은 이름 호칭 금지 지시를 담고 원문을 그대로 보낸다`() {
        val raw = EscalationMessage.build(EscalationReport(null, emptyList(), emptyList(), emptyList(), emptyList()))
        val o = JSONObject(LinerRequests.summarizeBody(raw))
        val msgs = o.getJSONArray("messages")
        assertTrue(msgs.getJSONObject(0).getString("content").contains("이름으로 부르지 마라"))
        assertEquals(raw, msgs.getJSONObject(1).getString("content"))
        assertEquals("none", o.getString("reasoning_effort"))
    }

    @Test fun `요청 프롬프트에 사람 이름이나 비밀값이 없다`() {
        val all = LinerRequests.CLASSIFY_SYSTEM_PROMPT + LinerRequests.SUMMARY_SYSTEM_PROMPT
        assertFalse(all.contains("현도"))
    }

    @Test fun `증상 파싱은 목록 밖이면 OTHER`() {
        assertEquals(Symptom.SMS_NOT_RECEIVED, Symptom.parse("SMS_NOT_RECEIVED"))
        assertEquals(Symptom.CALL_NOT_ENDED, Symptom.parse(" `call_not_ended`\n"))
        assertEquals(Symptom.OTHER, Symptom.parse("BATTERY_DRAIN"))
        assertEquals(Symptom.OTHER, Symptom.parse(""))
        assertEquals(Symptom.OTHER, Symptom.parse(null))
        assertEquals(Symptom.OTHER, Symptom.parse("SMS_NOT_RECEIVED 인 것 같아요"))
    }

    @Test fun `분류 호출 - 헤더와 URL, 응답 파싱`() {
        val t = FakeTransport(mutableListOf({ chat("CALL_NOT_RECEIVED") }))
        val c = LinerClient("KEY123", t, RecordingSleeper())
        assertEquals(Symptom.CALL_NOT_RECEIVED, c.classify("전화가 안 받아져"))
        assertEquals("https://platform.liner.com/api/v1/chat/completions", t.calls[0].url)
        assertEquals("Bearer KEY123", t.calls[0].headers["Authorization"])
    }

    @Test fun `키가 비어 있으면 호출하지 않고 예외`() {
        val t = FakeTransport(mutableListOf())
        val c = LinerClient("", t, RecordingSleeper())
        assertFalse(c.configured)
        assertThrows(NotConfiguredException::class.java) { c.classify("x") }
        assertTrue(t.calls.isEmpty())
    }

    @Test fun `429는 Retry-After만큼 기다렸다 재시도`() {
        val sleeper = RecordingSleeper()
        val t = FakeTransport(
            mutableListOf(
                { HttpResponse(429, mapOf("retry-after" to "3"), "") },
                { chat("OTHER") },
            ),
        )
        assertEquals(Symptom.OTHER, LinerClient("k", t, sleeper).classify("x"))
        assertEquals(listOf(3000L), sleeper.slept)
        assertEquals(2, t.calls.size)
    }

    @Test fun `429가 계속되면 두 번 재시도 후 포기`() {
        val sleeper = RecordingSleeper()
        val t = FakeTransport(MutableList(5) { { HttpResponse(429, emptyMap(), "") } })
        assertThrows(RateLimitedException::class.java) { LinerClient("k", t, sleeper).summarize("x") }
        assertEquals(3, t.calls.size)
        assertEquals(2, sleeper.slept.size)
    }

    @Test fun `Retry-After 파싱 - 없거나 이상하면 기본, 상한 30초`() {
        assertEquals(1000L, LinerClient.retryAfterMs(null))
        assertEquals(1000L, LinerClient.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(30_000L, LinerClient.retryAfterMs("999"))
        assertEquals(2000L, LinerClient.retryAfterMs("2"))
    }

    @Test fun `그 밖의 오류 코드는 예외`() {
        val t = FakeTransport(mutableListOf({ HttpResponse(401, emptyMap(), "") }))
        val e = assertThrows(HttpStatusException::class.java) { LinerClient("k", t, RecordingSleeper()).classify("x") }
        assertEquals(401, e.code)
    }

    @Test fun `응답 형식이 이상하면 요약은 예외, 분류는 OTHER`() {
        assertThrows(IOException::class.java) {
            LinerClient("k", FakeTransport(mutableListOf({ ok("{}") })), RecordingSleeper()).summarize("x")
        }
        assertEquals(Symptom.OTHER, LinerClient("k", FakeTransport(mutableListOf({ ok("not json") })), RecordingSleeper()).classify("x"))
    }

    @Test fun `키를 나중에 바꾸면 새 키로 동작한다`() {
        val t = FakeTransport(mutableListOf({ chat("SMS_NOT_RECEIVED") }))
        val c = LinerClient("", t, RecordingSleeper())
        assertFalse(c.configured)
        assertThrows(NotConfiguredException::class.java) { c.classify("x") }
        assertTrue(t.calls.isEmpty())
        c.updateKey("KEY123")
        assertTrue(c.configured)
        assertEquals(Symptom.SMS_NOT_RECEIVED, c.classify("문자가 안 와요"))
        assertEquals("Bearer KEY123", t.calls[0].headers["Authorization"])
    }

    @Test fun `데모 임계값`() {
        assertEquals(30, Thresholds.of(false).longCallMinutes)
        assertEquals(5, Thresholds.of(true).longCallMinutes)
    }
}
