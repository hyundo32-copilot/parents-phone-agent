package com.hyundo.parentsagent.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ParentTextTest {
    @Test fun `실패 문장은 알림을 보냈을 때만 알렸다고 한다`() {
        assertEquals("와이파이 설정을 못 고쳤어요. 가족에게 알렸어요", ParentText.failed("와이파이", true))
        assertEquals("와이파이 설정을 못 고쳤어요. 가족에게 전화해 주세요", ParentText.failed("와이파이", false))
    }

    @Test fun `고침 문장`() = assertEquals("와이파이 설정을 원래대로 되돌렸어요", ParentText.fixed("와이파이"))

    @Test fun `횟수 제한만 실패로 분류`() {
        assertTrue(ParentText.isLimit(SkipReason.DAILY_LIMIT))
        assertTrue(ParentText.isLimit(SkipReason.RUN_LIMIT))
        assertFalse(ParentText.isLimit(SkipReason.NIGHT_DND))
        assertFalse(ParentText.isLimit(SkipReason.PAUSED_STREAK))
    }

    @Test fun `부모님 문장에 괄호와 숫자가 없다`() {
        val all = listOf(ParentText.fixed("방해금지"), ParentText.failed("방해금지", true), ParentText.failed("방해금지", false), ParentText.NO_PROBLEM)
        all.forEach { s -> assertFalse(s, s.any { it in "()" || it.isDigit() }) }
    }
}
