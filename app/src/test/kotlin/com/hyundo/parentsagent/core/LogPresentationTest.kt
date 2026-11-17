package com.hyundo.parentsagent.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class LogPresentationTest {
    private data class Item(val key: String, val time: Long)

    private fun collapse(vararg items: Item) =
        LogPresentation.collapseRepeats(items.toList(), { it.key }, { it.time }, { it.key })

    @Test fun `빈 목록은 빈 결과`() = assertEquals(emptyList<LogRow>(), collapse())

    @Test fun `연속 3개는 1행 count 3`() {
        val r = collapse(Item("a", 3), Item("a", 2), Item("a", 1))
        assertEquals(1, r.size)
        assertEquals(3, r[0].count)
    }

    @Test fun `A B A는 묶이지 않는다`() = assertEquals(3, collapse(Item("a", 3), Item("b", 2), Item("a", 1)).size)

    @Test fun `묶음 시각은 가장 최근`() = assertEquals(30L, collapse(Item("a", 30), Item("a", 20), Item("a", 10))[0].timeMillis)

    @Test fun `순서가 유지된다`() =
        assertEquals(listOf("c", "b", "a"), collapse(Item("c", 3), Item("b", 2), Item("a", 1)).map { it.text })

    @Test fun `formatCount`() {
        assertEquals("글", LogPresentation.formatCount("글", 1))
        assertEquals("글", LogPresentation.formatCount("글", 0))
        assertEquals("글 (4번)", LogPresentation.formatCount("글", 4))
    }

    @Test fun `parentVisible 8가지`() {
        assertTrue(LogPresentation.parentVisible(LogKind.FIXED, "p"))
        assertTrue(LogPresentation.parentVisible(LogKind.FIXED, null))
        assertTrue(LogPresentation.parentVisible(LogKind.FAILED, "p"))
        assertTrue(LogPresentation.parentVisible(LogKind.FAILED, null))
        assertTrue(LogPresentation.parentVisible(LogKind.INFO, "p"))
        assertFalse(LogPresentation.parentVisible(LogKind.INFO, null))
        assertFalse(LogPresentation.parentVisible(LogKind.DEV, "p"))
        assertFalse(LogPresentation.parentVisible(LogKind.DEV, null))
    }

    private fun e(t: Long, kind: LogKind, p: String?, text: String = "원문$t") = LogEntry(t, text, kind, p)

    @Test fun `parentRows는 DEV를 뺀다`() =
        assertEquals(1, LogPresentation.parentRows(listOf(e(2, LogKind.DEV, "x"), e(1, LogKind.FIXED, "y"))).size)

    @Test fun `parentRows는 parentText 없는 INFO를 뺀다`() =
        assertEquals(emptyList<LogRow>(), LogPresentation.parentRows(listOf(e(1, LogKind.INFO, null))))

    @Test fun `묶은 뒤에 5건으로 자른다`() {
        val same = (20L downTo 11L).map { e(it, LogKind.FAILED, "같은 문장") }
        val others = (10L downTo 7L).map { e(it, LogKind.FIXED, "다른 문장 $it") }
        val rows = LogPresentation.parentRows(same + others)
        assertEquals(5, rows.size)
        assertEquals(10, rows[0].count)
        assertEquals(20L, rows[0].timeMillis)
    }

    @Test fun `표시 문구는 parentText 우선, kind도 담긴다`() {
        val r = LogPresentation.parentRows(listOf(e(1, LogKind.FIXED, "부모님 문장", "원문")))[0]
        assertEquals("부모님 문장", r.text)
        assertEquals(LogKind.FIXED, r.kind)
    }

    @Test fun `kind가 다르면 parentText가 같아도 묶지 않는다`() =
        assertEquals(2, LogPresentation.parentRows(listOf(e(2, LogKind.FIXED, "같음"), e(1, LogKind.FAILED, "같음"))).size)

    @Test fun `adminRows는 text 기준으로 묶고 DEV도 포함한다`() {
        val rows = LogPresentation.adminRows(listOf(e(3, LogKind.DEV, null, "a"), e(2, LogKind.DEV, null, "a"), e(1, LogKind.FIXED, "p", "b")))
        assertEquals(listOf("a" to 2, "b" to 1), rows.map { it.text to it.count })
    }

    @Test fun `기호`() {
        assertEquals("✓", LogPresentation.kindSymbol(LogKind.FIXED))
        assertEquals("✕", LogPresentation.kindSymbol(LogKind.FAILED))
        assertEquals("•", LogPresentation.kindSymbol(LogKind.INFO))
        assertEquals("", LogPresentation.kindSymbol(LogKind.DEV))
    }

    @Test fun `부모님 줄 형식`() {
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val now = java.time.ZonedDateTime.of(2026, 11, 15, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        val t = java.time.ZonedDateTime.of(2026, 11, 15, 15, 10, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("✓ 오늘 오후 3:10 — 고쳤어요 (2번)", LogPresentation.parentLine(LogRow(t, "고쳤어요", 2, LogKind.FIXED), now, zone))
    }

    @Test fun `복사 텍스트는 화면의 최근 20건과 같은 줄이다`() {
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val base = java.time.ZonedDateTime.of(2026, 11, 17, 8, 6, 0, 0, zone).toInstant().toEpochMilli()
        val entries = (0 until 25).map { LogEntry(base - it * 60_000L, "기록 $it") }
        val rows = LogPresentation.adminRows(entries, 20)
        val lines = LogPresentation.adminCopyText(entries, 20, zone).split("\n")
        assertEquals(20, lines.size)
        assertEquals("11월 17일 08:06 기록 0", lines[0])
        assertEquals("11월 17일 07:47 기록 19", lines[19])
        assertEquals(rows.map { LogPresentation.adminCopyLine(it, zone) }, lines)
    }

    @Test fun `복사 텍스트도 이어진 같은 줄은 묶고 횟수를 붙인다`() {
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val base = java.time.ZonedDateTime.of(2026, 1, 5, 23, 5, 0, 0, zone).toInstant().toEpochMilli()
        val entries = listOf(LogEntry(base, "와이파이"), LogEntry(base - 1000, "와이파이"), LogEntry(base - 2000, "다른 줄"))
        assertEquals("1월 5일 23:05 와이파이 (2번)\n1월 5일 23:04 다른 줄", LogPresentation.adminCopyText(entries, 20, zone))
    }

    @Test fun `기록이 없으면 복사 텍스트는 빈 문자열`() = assertEquals("", LogPresentation.adminCopyText(emptyList()))
}
