package com.hyundo.parentsagent.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 화면에 그릴 한 줄. count는 바로 이어서 반복된 횟수. */
data class LogRow(val timeMillis: Long, val text: String, val count: Int, val kind: LogKind = LogKind.DEV)

/** 기록을 화면에 보여 줄 때의 규칙 (순수 함수). 저장은 건드리지 않는다. */
object LogPresentation {
    /** 바로 이어지는 같은 key끼리만 묶는다. 입력은 최신순이라 묶음 시각은 가장 최근 것이다. */
    fun <T> collapseRepeats(
        items: List<T>, key: (T) -> Any, time: (T) -> Long, text: (T) -> String,
        kind: (T) -> LogKind = { LogKind.DEV },
    ): List<LogRow> {
        val out = mutableListOf<LogRow>()
        var lastKey: Any? = null
        for (item in items) {
            val k = key(item)
            if (out.isNotEmpty() && k == lastKey) {
                val prev = out.last()
                out[out.lastIndex] = prev.copy(timeMillis = maxOf(prev.timeMillis, time(item)), count = prev.count + 1)
            } else {
                out += LogRow(time(item), text(item), 1, kind(item))
                lastKey = k
            }
        }
        return out
    }

    fun formatCount(text: String, count: Int): String = if (count <= 1) text else "$text (${count}번)"

    fun parentVisible(kind: LogKind, parentText: String?): Boolean = when (kind) {
        LogKind.FIXED, LogKind.FAILED -> true
        LogKind.INFO -> parentText != null
        LogKind.DEV -> false
    }

    /** 부모님 영역 줄. 묶은 뒤에 limit건으로 자른다. 문구는 parentText 우선. */
    fun parentRows(entries: List<LogEntry>, limit: Int = 5): List<LogRow> =
        collapseRepeats(
            entries.filter { parentVisible(it.kind, it.parentText) },
            key = { it.kind to (it.parentText ?: it.text) },
            time = { it.timeMillis },
            text = { it.parentText ?: it.text },
            kind = { it.kind },
        ).take(limit)

    /** 관리 영역 줄. text 기준으로 묶고 limit건까지. */
    fun adminRows(entries: List<LogEntry>, limit: Int = 20): List<LogRow> =
        collapseRepeats(entries, { it.text }, { it.timeMillis }, { it.text }).take(limit)

    /** 복사용 한 줄: "11월 17일 08:06 <내용>" (묶인 줄은 "(N번)"까지 화면과 같다). */
    fun adminCopyLine(row: LogRow, zone: ZoneId = ZoneId.systemDefault()): String =
        "${ADMIN_TIME.withZone(zone).format(Instant.ofEpochMilli(row.timeMillis))} ${formatCount(row.text, row.count)}"

    /** 관리용 기록 복사 텍스트. 화면에 보이는 [adminRows]와 같은 줄들을 한 줄에 하나씩. */
    fun adminCopyText(entries: List<LogEntry>, limit: Int = 20, zone: ZoneId = ZoneId.systemDefault()): String =
        adminRows(entries, limit).joinToString("\n") { adminCopyLine(it, zone) }

    private val ADMIN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREA)

    fun kindSymbol(kind: LogKind): String = when (kind) {
        LogKind.FIXED -> "✓"
        LogKind.FAILED -> "✕"
        LogKind.INFO -> "•"
        LogKind.DEV -> ""
    }

    /** "✓ 오늘 오후 3:10 — 와이파이를 다시 켰어요 (2번)" */
    fun parentLine(row: LogRow, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "${kindSymbol(row.kind)} ${StatusCard.friendlyTime(row.timeMillis, nowMillis, zone)} — ${formatCount(row.text, row.count)}"
}
