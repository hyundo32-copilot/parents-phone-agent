package com.hyundo.parentsagent.core

import org.junit.Test
import org.junit.Assert.assertEquals

class LogKindTest {
    @Test fun `정상 코드 4개는 각 종류로 읽힌다`() {
        assertEquals(LogKind.FIXED, LogKind.fromCode("f"))
        assertEquals(LogKind.FAILED, LogKind.fromCode("x"))
        assertEquals(LogKind.INFO, LogKind.fromCode("i"))
        assertEquals(LogKind.DEV, LogKind.fromCode("d"))
    }

    @Test fun `null은 DEV`() = assertEquals(LogKind.DEV, LogKind.fromCode(null))

    @Test fun `모르는 값은 DEV`() {
        assertEquals(LogKind.DEV, LogKind.fromCode("zzz"))
        assertEquals(LogKind.DEV, LogKind.fromCode(""))
        assertEquals(LogKind.DEV, LogKind.fromCode("FIXED"))
    }
}
