package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.UnlockCheckPolicy
import org.junit.Assert.*
import org.junit.Test

class UnlockCheckPolicyTest {
    private val hour = 60 * 60 * 1000L
    private val gap = 6 * hour
    private val now = 100 * hour

    @Test fun `미룬 항목 있음 + 6시간 이내면 잠금 해제 점검을 돌린다`() {
        assertTrue(UnlockCheckPolicy.shouldRun(now - 20 * 60 * 1000L, now, hasDeferred = true, minGapMs = gap))
    }

    @Test fun `미룬 항목 없음 + 6시간 이내면 건너뛴다`() {
        assertFalse(UnlockCheckPolicy.shouldRun(now - 20 * 60 * 1000L, now, hasDeferred = false, minGapMs = gap))
    }

    @Test fun `미룬 항목 없음 + 6시간 초과면 돌린다`() {
        assertTrue(UnlockCheckPolicy.shouldRun(now - 7 * hour, now, hasDeferred = false, minGapMs = gap))
    }

    @Test fun `미룬 항목 있음 + 6시간 초과면 돌린다`() {
        assertTrue(UnlockCheckPolicy.shouldRun(now - 7 * hour, now, hasDeferred = true, minGapMs = gap))
    }

    @Test fun `정확히 6시간이면 돌린다`() {
        assertTrue(UnlockCheckPolicy.shouldRun(now - 6 * hour, now, hasDeferred = false, minGapMs = gap))
    }

    @Test fun `진단 로그 - 미룬 항목이 있으면 개수와 함께 점검한다고 남긴다`() {
        val line = UnlockCheckPolicy.logLine(now - 20 * 60 * 1000L, now, deferredCount = 2, run = true)
        assertTrue(line, line.startsWith("잠금 해제 감지"))
        assertTrue(line, line.contains("미룬 항목 2개"))
        assertTrue(line, line.contains("20분 전"))
        assertTrue(line, line.contains("점검함"))
    }

    @Test fun `진단 로그 - 건너뛰면 건너뜀으로 남긴다`() {
        val line = UnlockCheckPolicy.logLine(now - 2 * hour, now, deferredCount = 0, run = false)
        assertTrue(line, line.contains("미룬 항목 0개"))
        assertTrue(line, line.contains("120분 전"))
        assertTrue(line, line.contains("건너뜀"))
    }

    @Test fun `진단 로그 - 점검한 적이 없으면 없음으로 남긴다`() {
        val line = UnlockCheckPolicy.logLine(0L, now, deferredCount = 0, run = true)
        assertTrue(line, line.contains("마지막 점검 없음"))
    }
}
