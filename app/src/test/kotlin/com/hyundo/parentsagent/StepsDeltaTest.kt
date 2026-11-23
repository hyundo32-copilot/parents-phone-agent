package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.StepsDelta
import com.hyundo.parentsagent.core.StepsReading
import org.junit.Assert.*
import org.junit.Test

class StepsDeltaTest {
    @Test fun `이전 값이 없으면 알 수 없음`() {
        val r = StepsDelta.compute(null, 12000)
        assertNull(r.steps)
        assertFalse(r.abnormal)
    }

    @Test fun `현재 값을 못 읽으면 알 수 없음`() {
        assertEquals(StepsReading.UNKNOWN, StepsDelta.compute(1000, null))
    }

    @Test fun `재부팅으로 현재가 이전보다 작으면 알 수 없음이고 이상 판정 안 한다`() {
        val r = StepsDelta.compute(50000, 120)
        assertNull(r.steps)
        assertFalse(r.abnormal)
    }

    @Test fun `499보는 이상`() {
        val r = StepsDelta.compute(1000, 1499)
        assertEquals(499L, r.steps)
        assertTrue(r.abnormal)
    }

    @Test fun `500보는 정상`() {
        val r = StepsDelta.compute(1000, 1500)
        assertEquals(500L, r.steps)
        assertFalse(r.abnormal)
    }

    @Test fun `501보는 정상`() {
        val r = StepsDelta.compute(1000, 1501)
        assertEquals(501L, r.steps)
        assertFalse(r.abnormal)
    }

    @Test fun `값이 같으면 0보로 이상`() {
        val r = StepsDelta.compute(777, 777)
        assertEquals(0L, r.steps)
        assertTrue(r.abnormal)
    }

    @Test fun `큰 수도 계산한다`() {
        val r = StepsDelta.compute(1_000_000, 3_500_000)
        assertEquals(2_500_000L, r.steps)
        assertFalse(r.abnormal)
    }

    @Test fun `기준 상수는 500`() {
        assertEquals(500L, StepsDelta.STEPS_ABNORMAL_THRESHOLD)
    }
}
