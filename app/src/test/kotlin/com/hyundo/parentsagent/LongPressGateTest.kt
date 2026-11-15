package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.LongPressGate
import org.junit.Assert.*
import org.junit.Test

class LongPressGateTest {
    @Test fun `닫힌 상태에서 누르면 타이머를 건다`() {
        assertTrue(LongPressGate().onDown(isOpen = false))
    }

    @Test fun `열린 상태에서 누르면 타이머를 걸지 않는다`() {
        val g = LongPressGate()
        assertFalse(g.onDown(isOpen = true))
        assertFalse(g.onTimeout())
    }

    @Test fun `2초 경과하면 열고 손 떼기는 삼킨다`() {
        val g = LongPressGate()
        g.onDown(false)
        assertTrue(g.onTimeout())
        assertTrue("손 떼기가 클릭이 되면 바로 닫힌다", g.onUp())
    }

    @Test fun `길게 눌러 연 뒤 다음 짧은 탭은 삼키지 않아 닫기가 동작한다`() {
        val g = LongPressGate()
        g.onDown(false); g.onTimeout(); g.onUp()
        assertFalse(g.onDown(isOpen = true))
        assertFalse(g.onUp())
    }

    @Test fun `2초 전에 손을 떼면 열지 않고 삼키지도 않는다`() {
        val g = LongPressGate()
        g.onDown(false)
        assertFalse(g.onUp())
        assertFalse(g.onTimeout())
    }

    @Test fun `구역 밖으로 나가면 열지 않는다`() {
        val g = LongPressGate()
        g.onDown(false)
        g.onLeave()
        assertFalse(g.onTimeout())
        assertFalse(g.onUp())
    }

    @Test fun `취소되면 열린 뒤여도 삼킴 상태를 남기지 않는다`() {
        val g = LongPressGate()
        g.onDown(false); g.onTimeout()
        g.onCancel()
        assertFalse(g.onUp())
    }

    @Test fun `타이머는 한 번만 열린다`() {
        val g = LongPressGate()
        g.onDown(false)
        assertTrue(g.onTimeout())
        assertFalse(g.onTimeout())
    }
}
