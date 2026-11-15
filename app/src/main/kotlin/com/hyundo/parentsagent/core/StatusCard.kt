package com.hyundo.parentsagent.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 마지막 점검 결과 등급. 저장된 값이 없으면 UNKNOWN. */
enum class LastCheckGrade {
    UNKNOWN, OK, FAILED;

    companion object {
        fun parse(raw: String?): LastCheckGrade = entries.firstOrNull { it.name == raw } ?: UNKNOWN

        /** 복원 시도가 하나라도 실패했거나 복원 뒤에도 이탈이 남았으면 FAILED. */
        fun of(attempts: List<RestoreAttempt>, remaining: List<Deviation>): LastCheckGrade =
            if (attempts.any { it.result == AttemptResult.FAILED } || remaining.isNotEmpty()) FAILED else OK
    }
}

enum class StatusLevel { GOOD, WARN, BAD }

/** 상태 카드를 정하는 재료. 안드로이드 없이 계산할 수 있게 값만 담는다. */
data class StatusInput(
    val accessibilityOn: Boolean,
    val notificationPolicyOn: Boolean,
    val hasBaseline: Boolean,
    val pausedCount: Int,
    /** 마지막 점검 시각. 0이면 알 수 없음. */
    val lastCheckMillis: Long,
    val lastCheckGrade: LastCheckGrade,
    val queuedCount: Int,
    val nowMillis: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
)

data class StatusCardState(val level: StatusLevel, val title: String, val sub: String)

/** 상단 상태 카드 등급 규칙. 빨강 = 마지막 점검 실패, 노랑 = 확인할 것이 있음, 초록 = 그 외. */
object StatusCard {
    const val STALE_MILLIS = 24 * 60 * 60 * 1000L

    fun evaluate(i: StatusInput): StatusCardState {
        if (i.lastCheckGrade == LastCheckGrade.FAILED) {
            return StatusCardState(
                StatusLevel.BAD, "고치지 못했어요",
                if (i.queuedCount > 0) "가족에게 연락을 아직 못 보냈어요" else "가족에게 연락했어요",
            )
        }
        val warn = when {
            !i.accessibilityOn || !i.notificationPolicyOn -> "준비가 덜 됐어요. 가족에게 보여 주세요"
            !i.hasBaseline -> "아직 기준이 없어요. 가족이 정해 줘야 해요"
            i.pausedCount > 0 -> "같은 일이 자꾸 생겨서 잠깐 쉬고 있는 게 있어요"
            i.lastCheckMillis <= 0L -> "아직 점검한 기록이 없어요"
            i.nowMillis - i.lastCheckMillis > STALE_MILLIS -> "점검한 지 오래됐어요. 점검하기를 눌러 보세요"
            i.queuedCount > 0 -> "가족에게 알림을 아직 못 보냈어요"
            else -> null
        }
        if (warn != null) return StatusCardState(StatusLevel.WARN, "확인이 필요해요", warn)
        return StatusCardState(StatusLevel.GOOD, "폰이 괜찮아요", "마지막 확인: ${friendlyTime(i.lastCheckMillis, i.nowMillis, i.zone)}")
    }

    /** "오늘 오후 3:10" / "어제 오전 9:05" / "11월 3일 오후 3:10". 12시간제. */
    fun friendlyTime(millis: Long, nowMillis: Long, zone: ZoneId): String {
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
        return "$day $ampm $h12:%02d".format(t.minute)
    }
}
