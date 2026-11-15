package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.*
import org.junit.Assert.*
import org.junit.Test

class SettleTest {
    private val on = flag(true)
    private val off = flag(false)

    /** reads 를 차례로 돌려주는 읽기. 다 쓰면 마지막 값을 반복한다. */
    private fun sequence(vararg values: SettingValue): () -> SettingValue {
        var i = 0
        return { values[minOf(i++, values.size - 1)] }
    }

    @Test fun `처음부터 맞으면 기다리지 않는다`() {
        val sleeper = RecordingSleeper()
        val v = Settle.await(on, sequence(on), sleeper::sleep)
        assertEquals(on, v)
        assertTrue(sleeper.slept.isEmpty())
    }

    @Test fun `와이파이처럼 켜지는 데 시간이 걸리면 맞을 때까지 다시 읽는다`() {
        val sleeper = RecordingSleeper()
        // 설정 화면 스위치는 켜졌지만 isWifiEnabled 는 ENABLING 동안 false → 몇 번 뒤에 true
        val v = Settle.await(on, sequence(off, off, off, on), sleeper::sleep)
        assertEquals(on, v)
        assertEquals(listOf(500L, 500L, 500L), sleeper.slept)
        assertTrue(on.matches(v))
    }

    @Test fun `끝내 안 맞으면 제한 시간만 기다리고 마지막 값을 돌려준다`() {
        val sleeper = RecordingSleeper()
        val v = Settle.await(on, sequence(off), sleeper::sleep, timeoutMs = 2_000, intervalMs = 500)
        assertEquals(off, v)
        assertEquals(2_000L, sleeper.slept.sum())
        assertEquals(4, sleeper.slept.size)
    }

    @Test fun `중단 요청이 있으면 더 기다리지 않는다`() {
        val sleeper = RecordingSleeper()
        val v = Settle.await(on, sequence(off), sleeper::sleep, shouldStop = { true })
        assertEquals(off, v)
        assertTrue(sleeper.slept.isEmpty())
    }

    @Test fun `기본 제한 시간은 와이파이가 올라올 만큼 길다`() {
        val sleeper = RecordingSleeper()
        Settle.await(on, sequence(off), sleeper::sleep)
        assertEquals(Settle.DEFAULT_TIMEOUT_MS, sleeper.slept.sum())
        assertTrue(Settle.DEFAULT_TIMEOUT_MS >= 5_000)
    }

    // --- 설정 화면 스위치 결과: 실행 못 했는데 실행했다고 기록하던 문제 ---

    @Test fun `스위치를 못 찾거나 못 누르면 실행 못 한 것으로 본다`() {
        assertFalse(SwitchOutcome.NOT_FOUND.executed)
        assertFalse(SwitchOutcome.CLICK_FAILED.executed)
        assertTrue(SwitchOutcome.CHANGED.executed)
        assertTrue(SwitchOutcome.ALREADY.executed)
        assertTrue(SwitchOutcome.NOT_CHANGED.executed)
    }

    @Test fun `못 찾은 화면 상황이 로그 문구에 들어간다`() {
        val a = SwitchAttempt(SwitchOutcome.NOT_FOUND, "문구 2곳 발견, 화면 전체 스위치 0개")
        assertEquals("스위치를 못 찾음: 문구 2곳 발견, 화면 전체 스위치 0개", a.describe())
        assertEquals("눌러서 바뀜", SwitchAttempt(SwitchOutcome.CHANGED).describe())
    }
}
