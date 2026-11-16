package com.hyundo.parentsagent.core

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull

class LogEntryJsonTest {
    private fun roundTrip(e: LogEntry) = LogEntry.fromJson(JSONObject(e.toJson().toString()))

    @Test fun `4종 kind와 parentText가 그대로 복원된다`() {
        LogKind.entries.forEach { k ->
            val e = LogEntry(123L, "원문 $k", k, "부모님 문장 $k")
            assertEquals(e, roundTrip(e))
        }
    }

    @Test fun `k와 p가 없는 예전 JSON은 DEV와 null`() {
        val e = LogEntry.fromJson(JSONObject("""{"t":5,"x":"예전 기록"}"""))
        assertEquals(LogEntry(5L, "예전 기록", LogKind.DEV, null), e)
    }

    @Test fun `parentText가 null이면 p 키를 쓰지 않는다`() {
        val o = LogEntry(1L, "a", LogKind.INFO, null).toJson()
        assertFalse(o.has("p"))
        assertEquals("i", o.getString("k"))
    }

    @Test fun `모르는 k는 DEV로 읽힌다`() {
        assertEquals(LogKind.DEV, LogEntry.fromJson(JSONObject("""{"t":1,"x":"a","k":"q","p":"b"}""")).kind)
    }

    @Test fun `빈 문자열 p는 null`() {
        assertNull(LogEntry.fromJson(JSONObject("""{"t":1,"x":"a","k":"f","p":""}""")).parentText)
    }

    @Test fun `모르는 키는 무시한다`() {
        val e = LogEntry.fromJson(JSONObject("""{"t":1,"x":"a","k":"f","p":"b","zz":9}"""))
        assertEquals(LogEntry(1L, "a", LogKind.FIXED, "b"), e)
    }
}
