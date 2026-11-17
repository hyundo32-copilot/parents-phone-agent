package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.CheckSummary
import com.hyundo.parentsagent.core.LogKind
import com.hyundo.parentsagent.core.ResultNote
import com.hyundo.parentsagent.core.ResultNotes
import org.junit.Assert.*
import org.junit.Test

class ResultNotesTest {
    private fun sum(
        fixed: Int = 0, failed: Int = 0, deferred: List<String> = emptyList(), stopped: Boolean = false,
        escalated: Boolean = false, notified: Boolean = false, allClear: Boolean = false,
    ) = CheckSummary(fixed, failed, deferred, stopped, escalated, notified, allClear)

    private fun check(s: CheckSummary) = ResultNotes.forCheck(s, 100L)

    // --- 저장 형식 ---

    @Test fun `encode와 decode 왕복`() {
        LogKind.entries.forEach { k ->
            val n = ResultNote(k, "문구 $k", 123L, "덧붙임")
            assertEquals(n, ResultNotes.decode(ResultNotes.encode(n)))
        }
        val plain = ResultNote(LogKind.INFO, "덧붙임 없음", 5L)
        assertEquals(plain, ResultNotes.decode(ResultNotes.encode(plain)))
    }

    @Test fun `null 빈 문자열 깨진 JSON은 null`() {
        assertNull(ResultNotes.decode(null))
        assertNull(ResultNotes.decode(""))
        assertNull(ResultNotes.decode("   "))
        assertNull(ResultNotes.decode("{깨짐"))
        assertNull(ResultNotes.decode("[1,2]"))
        assertNull(ResultNotes.decode("""{"k":"f","t":"문구"}""")) // 시각 없음
        assertNull(ResultNotes.decode("""{"k":"f","a":5}""")) // 문구 없음
    }

    @Test fun `모르는 종류나 종류 없음은 INFO`() {
        assertEquals(LogKind.INFO, ResultNotes.decode("""{"k":"zzz","t":"a","a":1}""")!!.kind)
        assertEquals(LogKind.INFO, ResultNotes.decode("""{"t":"a","a":1}""")!!.kind)
    }

    // --- 점검 결과 우선순위 ---

    @Test fun `중단이 가장 먼저`() {
        val n = check(sum(fixed = 1, failed = 1, stopped = true, escalated = true, notified = true))
        assertEquals("멈췄어요", n.text)
        assertEquals(LogKind.INFO, n.kind)
    }

    @Test fun `알림이 나갔을 때만 가족에게 알렸다고 한다`() {
        val sent = check(sum(failed = 1, escalated = true, notified = true))
        assertEquals("점검 끝. 1개는 못 고쳤어요. 가족에게 알렸어요", sent.text)
        assertEquals(LogKind.FAILED, sent.kind)
        val notSent = check(sum(failed = 1, escalated = true, notified = false))
        assertEquals("점검 끝. 1개는 못 고쳤어요. 가족에게 전화해 주세요", notSent.text)
        assertFalse(notSent.text.contains("알렸어요"))
        assertEquals(LogKind.FAILED, notSent.kind)
    }

    @Test fun `고침과 실패가 함께 있으면 둘 다 말한다`() {
        val n = check(sum(fixed = 2, failed = 1, escalated = true, notified = true))
        assertEquals("점검 끝. 2개를 고쳤고, 1개는 못 고쳤어요. 가족에게 알렸어요", n.text)
    }

    @Test fun `알림 없이 못 고친 것만 있으면 알렸다는 말 없이 FAILED`() {
        val n = check(sum(failed = 1))
        assertEquals("점검 끝. 1개는 못 고쳤어요.", n.text)
        assertEquals(LogKind.FAILED, n.kind)
    }

    @Test fun `미룸은 알림보다 뒤, 고침보다 앞`() {
        val n = check(sum(deferred = listOf("와이파이", "방해금지")))
        assertEquals("폰을 쓰는 중이라 와이파이, 방해금지은 잠금을 풀면 고칠게요", n.text)
        assertEquals(LogKind.INFO, n.kind)
        val withFixed = check(sum(fixed = 1, deferred = listOf("와이파이")))
        assertTrue(withFixed.text.startsWith("점검 끝. 1개를 고쳤어요. 폰을 쓰는 중이라"))
        // 알림이 우선
        assertTrue(check(sum(deferred = listOf("와이파이"), escalated = true, notified = true)).text.endsWith("가족에게 알렸어요"))
    }

    @Test fun `고침만 있으면 FIXED`() {
        val n = check(sum(fixed = 3))
        assertEquals("점검 끝. 3개를 고쳤어요.", n.text)
        assertEquals(LogKind.FIXED, n.kind)
    }

    @Test fun `모두 정상`() {
        val n = check(sum(allClear = true))
        assertEquals("점검 끝. 모두 괜찮아요", n.text)
        assertEquals(LogKind.INFO, n.kind)
    }

    @Test fun `읽지 못한 항목이나 정책 건너뜀만 있으면 모두 괜찮아요가 아니다`() {
        val n = check(sum(allClear = false))
        assertFalse(n.text.contains("모두 괜찮아요"))
        assertEquals(LogKind.INFO, n.kind)
        // 읽지 못한 항목 때문에 알림을 보낸 경우도 같다
        val esc = check(sum(escalated = true, notified = true))
        assertFalse(esc.text.contains("모두 괜찮아요"))
        assertTrue(esc.text.endsWith("가족에게 알렸어요"))
    }

    @Test fun `시각은 그대로 담긴다`() {
        assertEquals(100L, check(sum(allClear = true)).atMillis)
    }

    // --- 끝 음성 (항목별 음성과 겹치지 않게) ---

    @Test fun `끝 음성은 모두 괜찮음 미룸 중단일 때만`() {
        assertTrue(ResultNotes.shouldSpeakEnd(sum(allClear = true)))
        assertTrue(ResultNotes.shouldSpeakEnd(sum(deferred = listOf("와이파이"))))
        assertTrue(ResultNotes.shouldSpeakEnd(sum(stopped = true, fixed = 1)))
        assertFalse(ResultNotes.shouldSpeakEnd(sum(fixed = 1)))
        assertFalse(ResultNotes.shouldSpeakEnd(sum(failed = 1, escalated = true, notified = true)))
        assertFalse(ResultNotes.shouldSpeakEnd(sum(escalated = true, notified = true)))
    }

    // --- 다른 문구 ---

    @Test fun `정해진 문구`() {
        assertEquals("먼저 가족이 기준을 정해 줘야 해요", ResultNotes.noBaseline(1).text)
        assertEquals("먼저 동의가 필요해요", ResultNotes.noConsent(1).text)
        assertEquals("이제 다시 자동으로 고쳐 드려요", ResultNotes.autoFixResumed(1).text)
        assertEquals("지금 설정을 기준으로 저장했어요", ResultNotes.baselineSaved(1).text)
        assertEquals("가족에게 다시 보내는 중이에요", ResultNotes.resendStarted(1).text)
        assertEquals("가족에게 다 보냈어요", ResultNotes.resendDone(0, 1).text)
        assertEquals(LogKind.FIXED, ResultNotes.resendDone(0, 1).kind)
        assertEquals("아직 못 보냈어요. 망이 돌아오면 다시 보내요", ResultNotes.resendDone(2, 1).text)
        listOf(ResultNotes.noBaseline(1), ResultNotes.noConsent(1), ResultNotes.autoFixResumed(1), ResultNotes.baselineSaved(1))
            .forEach { assertEquals(LogKind.INFO, it.kind) }
    }

    @Test fun `TTS 실패 문구는 정확하고 두 번 붙이지 않는다`() {
        assertEquals("소리로 읽어 드릴 수 없어요. 화면 글을 봐 주세요.", ResultNotes.TTS_FAILED)
        val base = ResultNote(LogKind.FIXED, "점검 끝. 1개를 고쳤어요.", 10L)
        val once = ResultNotes.withTtsFailed(base)
        assertEquals(base.text, once.text)
        assertEquals(base.kind, once.kind)
        assertEquals(ResultNotes.TTS_FAILED, once.extra)
        assertEquals(once, ResultNotes.withTtsFailed(once))
        assertEquals(LogKind.INFO, ResultNotes.ttsFailed(1).kind)
        assertEquals(ResultNotes.TTS_FAILED, ResultNotes.ttsFailed(1).text)
    }

    // --- 낡은 카드 ---

    @Test fun `카드가 마지막 점검 결과보다 앞서면 낡았다`() {
        val n = ResultNote(LogKind.INFO, "x", 1_000L)
        assertTrue(ResultNotes.isStale(n, 1_001L))
        assertFalse(ResultNotes.isStale(n, 1_000L))
        assertFalse(ResultNotes.isStale(n, 999L))
        assertFalse(ResultNotes.isStale(n, 0L))
    }
}
