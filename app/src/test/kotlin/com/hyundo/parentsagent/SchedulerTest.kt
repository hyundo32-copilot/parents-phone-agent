package com.hyundo.parentsagent

import com.hyundo.parentsagent.android.Scheduler
import com.hyundo.parentsagent.android.Scheduler.AlarmAction
import org.junit.Assert.*
import org.junit.Test

class SchedulerTest {
    private val dailySlots = Scheduler.SLOT_HOURS.indices.toList() + Scheduler.HEARTBEAT_SLOT

    @Test fun `테스트 슬롯 requestCode는 매일 슬롯과 겹치지 않는다`() {
        assertEquals(listOf(0, 1, 2), dailySlots)
        assertFalse(Scheduler.TEST_SLOT in dailySlots)
    }

    @Test fun `테스트 슬롯은 점검만 하고 매일 슬롯 재예약은 하지 않는다`() {
        val a = Scheduler.actionFor(Scheduler.TEST_SLOT)
        assertEquals(AlarmAction.TEST_CHECK, a)
        assertTrue(a.runsCheck)
        assertFalse(a.sendsHeartbeat)
        assertFalse(a.reschedulesDaily)
    }

    @Test fun `매일 점검 슬롯은 점검하고 다음 날로 재예약한다`() {
        Scheduler.SLOT_HOURS.indices.forEach {
            val a = Scheduler.actionFor(it)
            assertEquals(AlarmAction.DAILY_CHECK, a)
            assertTrue(a.runsCheck)
            assertTrue(a.reschedulesDaily)
        }
    }

    @Test fun `안부 신호 슬롯 동작은 그대로다`() {
        val a = Scheduler.actionFor(Scheduler.HEARTBEAT_SLOT)
        assertEquals(AlarmAction.HEARTBEAT, a)
        assertTrue(a.sendsHeartbeat)
        assertFalse(a.runsCheck)
        assertTrue(a.reschedulesDaily)
    }
}
