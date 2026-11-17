package com.hyundo.parentsagent.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 소리로 읽어 줄 문장을 만든다. 안드로이드 없이 계산한다. */
object SpeechText {
    /** "오늘 오후 3시 10분" / "어제 오전 12시" / "11월 3일 오후 1시 30분". 날짜 규칙은 [StatusCard.friendlyTime]과 같고, 분이 0이면 "분"을 뺀다. */
    fun koreanTime(millis: Long, nowMillis: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        val today: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val day = when (t.toLocalDate()) {
            today -> "오늘"
            today.minusDays(1) -> "어제"
            else -> "${t.monthValue}월 ${t.dayOfMonth}일"
        }
        val h = t.hour
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        val min = if (t.minute == 0) "" else " ${t.minute}분"
        return "$day $ampm ${h12}시$min"
    }

    /**
     * 상태 카드를 읽는 문장. 정상이면 마지막 확인 시각까지, 아니면 제목과 보조 줄.
     * 마지막 확인이 없으면 "아직 확인 전이에요."
     */
    fun statusCard(state: StatusCardState, lastCheckMillis: Long, nowMillis: Long, zone: ZoneId): String =
        when (state.level) {
            StatusLevel.GOOD ->
                if (lastCheckMillis <= 0L) "${state.title}. 아직 확인 전이에요."
                else {
                    val time = koreanTime(lastCheckMillis, nowMillis, zone)
                    "${state.title}. 마지막 확인은 $time${if (time.endsWith("분")) "이에요" else "예요"}."
                }
            StatusLevel.WARN, StatusLevel.BAD -> "${state.title}. ${endSentence(state.sub)}"
        }

    private fun endSentence(s: String): String = if (s.isEmpty() || s.last() in ".!?") s else "$s."

    private val DROP = setOf('✓', '✔', '✕', '✘', '•', '▸', '▾', '(', ')', '️', '‍')

    /**
     * 읽기 좋게 정리한다. 기호·이모지·괄호 기호를 지우고, 공백을 하나로 줄이고, 줄바꿈은 문장 끝으로 바꾼다.
     * 숫자와 단위("3개", "85%")는 그대로 둔다.
     */
    fun forSpeech(text: String): String {
        val lines = text.lines().map { line ->
            val sb = StringBuilder()
            line.codePoints().forEach { cp ->
                val drop = (cp < 0x10000 && cp.toChar() in DROP) || Character.getType(cp) == Character.OTHER_SYMBOL.toInt()
                sb.append(if (drop) ' ' else String(Character.toChars(cp)))
            }
            sb.toString().replace(Regex("\\s+"), " ").trim()
        }.filter { it.isNotEmpty() }
        return lines.foldIndexed("") { i, acc, line ->
            if (i == 0) line else acc + (if (acc.last() in ".!?") " " else ". ") + line
        }
    }
}
