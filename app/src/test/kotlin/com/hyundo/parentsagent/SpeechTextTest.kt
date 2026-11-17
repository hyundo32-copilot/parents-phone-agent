package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SpeechTextTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
    private val now = ms(2026, 11, 17, 15, 0)

    private fun t(h: Int, m: Int, day: Int = 17) = SpeechText.koreanTime(ms(2026, 11, day, h, m), now, zone)

    @Test fun `koreanTime 자정과 정오`() {
        assertEquals("오늘 오전 12시", t(0, 0))
        assertEquals("오늘 오후 12시", t(12, 0))
    }

    @Test fun `koreanTime 분 표기`() {
        assertEquals("오늘 오전 9시 5분", t(9, 5))
        assertEquals("오늘 오후 1시 30분", t(13, 30))
        assertEquals("오늘 오후 3시", t(15, 0))
        assertEquals("오늘 오전 12시 5분", t(0, 5))
    }

    @Test fun `koreanTime 어제와 날짜`() {
        assertEquals("어제 오후 3시 10분", t(15, 10, day = 16))
        assertEquals("11월 3일 오전 9시", t(9, 0, day = 3))
    }

    private fun stateAt(grade: LastCheckGrade, last: Long, queued: Int = 0, acc: Boolean = true) = StatusCard.evaluate(
        StatusInput(acc, true, true, 0, last, grade, queued, now, zone),
    )

    @Test fun `statusCard 정상은 마지막 확인 시각을 읽는다`() {
        val last = ms(2026, 11, 17, 15, 10)
        val s = stateAt(LastCheckGrade.OK, last)
        assertEquals(StatusLevel.GOOD, s.level)
        assertEquals("폰이 괜찮아요. 마지막 확인은 오늘 오후 3시 10분이에요.", SpeechText.statusCard(s, last, now, zone))
        val onHour = ms(2026, 11, 17, 14, 0)
        assertEquals("폰이 괜찮아요. 마지막 확인은 오늘 오후 2시예요.", SpeechText.statusCard(stateAt(LastCheckGrade.OK, onHour), onHour, now, zone))
    }

    @Test fun `statusCard 경고와 위험은 제목과 보조 줄`() {
        val warn = stateAt(LastCheckGrade.OK, 0L)
        assertEquals(StatusLevel.WARN, warn.level)
        assertEquals("확인이 필요해요. 아직 점검한 기록이 없어요.", SpeechText.statusCard(warn, 0L, now, zone))
        val bad = stateAt(LastCheckGrade.FAILED, now - 1000)
        assertEquals(StatusLevel.BAD, bad.level)
        assertEquals("고치지 못했어요. 가족에게 연락했어요.", SpeechText.statusCard(bad, now - 1000, now, zone))
    }

    @Test fun `statusCard 모든 상태가 마침표로 끝난다`() {
        listOf(stateAt(LastCheckGrade.OK, now - 1000), stateAt(LastCheckGrade.OK, 0L), stateAt(LastCheckGrade.FAILED, now, queued = 1))
            .forEach { assertTrue(SpeechText.statusCard(it, now - 1000, now, zone).endsWith(".")) }
    }

    @Test fun `statusCard 마지막 확인이 없는 정상 상태`() {
        val s = StatusCardState(StatusLevel.GOOD, "폰이 괜찮아요", "")
        assertEquals("폰이 괜찮아요. 아직 확인 전이에요.", SpeechText.statusCard(s, 0L, now, zone))
    }

    @Test fun `forSpeech 기호와 이모지를 지운다`() {
        assertEquals("읽어 주기", SpeechText.forSpeech("🔊 읽어 주기"))
        assertEquals("점검 끝", SpeechText.forSpeech("✅ 점검 끝"))
        assertEquals("주의", SpeechText.forSpeech("⚠️ 주의"))
        assertEquals("고쳤어요", SpeechText.forSpeech("• 고쳤어요"))
        assertEquals("와이파이", SpeechText.forSpeech("✓ ✕ 와이파이"))
        assertEquals("방해금지 켜짐", SpeechText.forSpeech("방해금지 (켜짐)"))
    }

    @Test fun `forSpeech 공백 정리와 줄바꿈`() {
        assertEquals("가나 다라", SpeechText.forSpeech("  가나    다라  "))
        assertEquals("첫째 줄. 둘째 줄", SpeechText.forSpeech("첫째 줄\n둘째 줄"))
        assertEquals("끝났어요. 다음이에요", SpeechText.forSpeech("끝났어요.\n\n다음이에요"))
        assertEquals("가. 나", SpeechText.forSpeech("가\r\n\r\n나"))
    }

    @Test fun `forSpeech 빈 입력`() {
        assertEquals("", SpeechText.forSpeech(""))
        assertEquals("", SpeechText.forSpeech("   \n  "))
        assertEquals("", SpeechText.forSpeech("🔊"))
    }

    @Test fun `forSpeech 숫자와 단위는 그대로`() {
        assertEquals("3개를 고쳤어요", SpeechText.forSpeech("3개를 고쳤어요"))
        assertEquals("배터리 85%", SpeechText.forSpeech("배터리 85%"))
        assertEquals("11월 3일 오후 1시 30분", SpeechText.forSpeech("11월 3일 오후 1시 30분"))
    }
}
