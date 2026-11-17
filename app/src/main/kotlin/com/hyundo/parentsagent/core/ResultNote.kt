package com.hyundo.parentsagent.core

import org.json.JSONObject

/**
 * 점검이 끝났을 때 CheckRunner가 바깥에 알려 주는 숫자·상태 (부모님 화면 문장의 재료).
 * 가족용 원문 summary와는 별개다. 기본값 없이 모두 채워서 만든다.
 */
data class CheckSummary(
    val fixed: Int,
    /** 못 고친 항목 수 (횟수 제한으로 멈춘 것 포함). */
    val failed: Int,
    val deferredLabels: List<String>,
    val stopped: Boolean,
    /** 가족에게 알림을 보내려고 시도했는지. */
    val escalated: Boolean,
    /** 알림이 실제로 나갔는지 (SENT·SENT_RAW만 true. QUEUED·REJECTED는 false). */
    val familyNotified: Boolean,
    /** 이탈도 못 읽은 것도 없음. */
    val allClear: Boolean,
)

/**
 * 부모님이 방금 한 동작의 결과 카드 한 장. [extra]는 본문 아래에 붙는 INFO 한 줄 (TTS 실패 안내).
 * 종류는 [LogKind]를 그대로 쓴다. DEV는 카드로 만들지 않는다.
 */
data class ResultNote(val kind: LogKind, val text: String, val atMillis: Long, val extra: String? = null)

/** 결과 카드 문구와 저장 형식. 문구는 ParentText처럼 이 안에 상수로 둔다. */
object ResultNotes {
    const val TTS_FAILED = "소리로 읽어 드릴 수 없어요. 화면 글을 봐 주세요."

    fun noBaseline(now: Long) = ResultNote(LogKind.INFO, "먼저 가족이 기준을 정해 줘야 해요", now)
    fun noConsent(now: Long) = ResultNote(LogKind.INFO, "먼저 동의가 필요해요", now)
    fun autoFixResumed(now: Long) = ResultNote(LogKind.INFO, "이제 다시 자동으로 고쳐 드려요", now)
    fun ttsFailed(now: Long) = ResultNote(LogKind.INFO, TTS_FAILED, now)

    // 가족 영역 버튼 근처에 보이는 피드백 (맨 위 결과 카드로는 쓰지 않는다)
    fun baselineSaved(now: Long) = ResultNote(LogKind.INFO, "지금 설정을 기준으로 저장했어요", now)
    fun resendStarted(now: Long) = ResultNote(LogKind.INFO, "가족에게 다시 보내는 중이에요", now)
    fun resendDone(remaining: Int, now: Long) =
        if (remaining <= 0) ResultNote(LogKind.FIXED, "가족에게 다 보냈어요", now)
        else ResultNote(LogKind.INFO, "아직 못 보냈어요. 망이 돌아오면 다시 보내요", now)

    /** TTS 실패 안내를 결과 본문 아래 INFO 줄로 덧붙인다. 여러 번 불러도 같다. */
    fun withTtsFailed(n: ResultNote): ResultNote = if (n.extra == TTS_FAILED) n else n.copy(extra = TTS_FAILED)

    /**
     * 점검 결과 문장. 우선순위는 CheckRunner summary 분기 순서와 같다:
     * 중단 → 가족 알림(성공/실패) → 미룸 → 고침·못 고침 → 모두 정상.
     * "가족에게 알렸어요"는 알림이 실제로 나갔을 때(familyNotified)만 쓴다.
     */
    fun forCheck(s: CheckSummary, now: Long): ResultNote {
        if (s.stopped) return ResultNote(LogKind.INFO, "멈췄어요", now)
        val head = countsSentence(s)
        if (s.escalated) {
            val tail = if (s.familyNotified) "가족에게 알렸어요" else "가족에게 전화해 주세요"
            return ResultNote(LogKind.FAILED, "$head $tail", now)
        }
        if (s.deferredLabels.isNotEmpty()) {
            val wait = "폰을 쓰는 중이라 ${s.deferredLabels.joinToString(", ")}은 잠금을 풀면 고칠게요"
            return ResultNote(LogKind.INFO, if (s.fixed > 0) "점검 끝. ${s.fixed}개를 고쳤어요. $wait" else wait, now)
        }
        if (s.failed > 0) return ResultNote(LogKind.FAILED, head, now)
        if (s.fixed > 0) return ResultNote(LogKind.FIXED, head, now)
        if (s.allClear) return ResultNote(LogKind.INFO, "점검 끝. 모두 괜찮아요", now)
        // 고친 것도 못 고친 것도 없지만 읽지 못한 항목이 있거나 정책상 건너뛴 이탈이 남았다: "모두 괜찮아요"가 아니다
        return ResultNote(LogKind.INFO, "점검 끝. 더 확인할 것이 남아 있어요", now)
    }

    private fun countsSentence(s: CheckSummary): String = when {
        s.fixed > 0 && s.failed > 0 -> "점검 끝. ${s.fixed}개를 고쳤고, ${s.failed}개는 못 고쳤어요."
        s.failed > 0 -> "점검 끝. ${s.failed}개는 못 고쳤어요."
        s.fixed > 0 -> "점검 끝. ${s.fixed}개를 고쳤어요."
        else -> "점검 끝. 확인이 필요한 게 있어요."
    }

    /**
     * 점검 끝 음성을 읽을지. 항목별 안내 음성이 이미 나간 경우(고침·못 고침·가족 알림)는 겹치지 않게 읽지 않고,
     * 항목별 음성이 없는 경우(모두 괜찮아요, 미룸, 중단)만 읽는다.
     */
    fun shouldSpeakEnd(s: CheckSummary): Boolean =
        s.stopped || s.deferredLabels.isNotEmpty() || (s.fixed == 0 && s.failed == 0 && !s.escalated)

    /** 점검 결과 카드가 상태 카드보다 오래됐으면(그 뒤에 예약·잠금 해제 점검이 결과를 갱신) 숨긴다. 같은 시각은 유지. */
    fun isStale(n: ResultNote, lastResultMillis: Long): Boolean = n.atMillis < lastResultMillis

    /** JSON 키: k 종류 코드, t 문구, a 시각, e 덧붙임(있을 때만). */
    fun encode(n: ResultNote): String = JSONObject().put("k", n.kind.code).put("t", n.text).put("a", n.atMillis).apply {
        if (n.extra != null) put("e", n.extra)
    }.toString()

    /** null·빈 문자열·깨진 JSON·필수 키(t, a) 없음은 null. 모르는 종류는 INFO. */
    fun decode(s: String?): ResultNote? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(s)
            val text = o.getString("t")
            val at = o.getLong("a")
            val kind = LogKind.entries.firstOrNull { it.code == o.optString("k") } ?: LogKind.INFO
            ResultNote(kind, text, at, if (o.has("e")) o.optString("e").ifEmpty { null } else null)
        }.getOrNull()
    }
}
