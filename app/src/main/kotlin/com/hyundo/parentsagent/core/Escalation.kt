package com.hyundo.parentsagent.core

enum class AttemptResult(val text: String) {
    SUCCESS("성공"),
    FAILED("실패"),
    STOPPED_BY_USER("부모님이 중단"),

    /** 화면 꺼짐 등으로 못 해서 잠금 해제 때로 미룸. 시도·이탈 일수에 세지 않는다. */
    DEFERRED("미룸"),

    /** 복원 직전에 다시 읽으니 이미 정상이라 건드리지 않음. */
    ALREADY_OK("이미 정상");

    /** 하루 복원 횟수에 세는지. 미룬 것과 이미 정상인 것은 세지 않는다. */
    fun countsTowardDailyLimit(): Boolean = this != DEFERRED && this != ALREADY_OK

    /** 이탈 일수(연속 이탈 계산)에 세는지. */
    fun countsAsDeviationDay(): Boolean = this != DEFERRED && this != ALREADY_OK
}

data class RestoreAttempt(
    val key: SettingKey,
    val method: String,
    val before: SettingValue,
    val after: SettingValue,
    val result: AttemptResult,
)

data class SkippedItem(val deviation: Deviation, val reason: SkipReason)

/** 규칙 엔진이 모은 점검 결과. 현도에게 보낼 메시지의 재료. */
data class EscalationReport(
    val symptom: Symptom?,
    /** 이번 점검에서 기준에서 벗어났던 항목 전부. */
    val deviations: List<Deviation>,
    val attempts: List<RestoreAttempt>,
    val skipped: List<SkippedItem>,
    /** 복원 뒤 다시 비교해도 남은 이탈. */
    val remaining: List<Deviation>,
    val unreadable: List<SettingKey> = emptyList(),
    val rebooted: Boolean = false,
) {
    /** 현도에게 알릴 만한 일인지. 전부 성공적으로 복원했으면 알리지 않는다. */
    fun needsEscalation(): Boolean {
        // 부모님이 직접 멈춘 것(STOPPED_BY_USER)은 현도에게 알릴 사유가 아니다 (부모님 통제권 우선)
        if (attempts.any { it.result == AttemptResult.FAILED }) return true
        if (remaining.isNotEmpty()) return true
        if (skipped.any { it.reason != SkipReason.NIGHT_DND && it.reason != SkipReason.PAUSED_STREAK }) return true
        // 증상 신고인데 기준 이탈이 없으면 폰 설정으로는 원인을 못 찾은 것
        if (symptom != null && deviations.isEmpty()) return true
        return false
    }
}

object EscalationMessage {
    const val MAX_ATTEMPTS_SHOWN = 3
    const val TITLE = "[부모님 폰 알림]"

    /** 규칙 엔진 원문. 설정값·시도 내역·전후 수치만 담고, 사람 이름·연락처·메시지 내용은 담지 않는다. */
    fun build(r: EscalationReport): String = buildString {
        appendLine(TITLE)
        appendLine("증상: " + (r.symptom?.let { if (it == Symptom.OTHER) "부모님이 증상 버튼을 눌렀음 (분류 불가)" else it.label } ?: "정기 점검"))
        appendLine()
        appendLine("기준에서 벗어난 항목:")
        if (r.deviations.isEmpty()) appendLine("- 없음")
        r.deviations.forEach { appendLine("- ${it.key.label}: 기준 ${it.expected.display()} → 현재 ${it.actual.display()}") }
        appendLine()
        appendLine("시도한 수정 (최대 ${MAX_ATTEMPTS_SHOWN}개):")
        if (r.attempts.isEmpty()) appendLine("- 없음")
        r.attempts.take(MAX_ATTEMPTS_SHOWN).forEachIndexed { i, a ->
            appendLine("${i + 1}) ${a.key.label} — ${a.method}: ${a.before.display()} → ${a.after.display()} (${a.result.text})")
        }
        if (r.skipped.isNotEmpty()) {
            appendLine()
            appendLine("복원하지 않은 항목:")
            r.skipped.forEach { appendLine("- ${it.deviation.key.label}: ${it.reason.text}") }
        }
        appendLine()
        appendLine("재부팅: " + if (r.rebooted) "함" else "하지 않음")
        if (r.remaining.isEmpty()) {
            appendLine("남은 이탈: 없음")
        } else {
            appendLine("남은 이탈:")
            r.remaining.forEach { appendLine("- ${it.key.label}: 기준 ${it.expected.display()} → 현재 ${it.actual.display()}") }
        }
        if (r.unreadable.isNotEmpty()) {
            appendLine("읽지 못한 항목: " + r.unreadable.joinToString(", ") { it.label })
        }
    }.trimEnd()
}
