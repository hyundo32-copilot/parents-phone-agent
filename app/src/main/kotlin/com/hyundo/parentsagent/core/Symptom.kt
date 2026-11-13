package com.hyundo.parentsagent.core

/** Liner 증상 분류 결과 후보 (위임 노트 §5). 이 목록 밖의 값은 OTHER로 처리한다. */
enum class Symptom(val label: String) {
    SMS_NOT_RECEIVED("문자가 안 와요"),
    CALL_NOT_RECEIVED("전화가 안 받아져요"),
    CALL_NOT_MADE("전화가 안 걸려요"),
    CALL_NOT_ENDED("통화가 안 끊겨요"),
    SLOW_APP("폰이 느려요"),
    OTHER("기타");

    companion object {
        /** 모델 응답 문자열을 목록 중 하나로. 정확히 일치하지 않으면 OTHER. */
        fun parse(raw: String?): Symptom {
            val t = raw?.trim()?.trim('`', '"', '\'', '.')?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == t } ?: OTHER
        }
    }
}

/** 데모용 임계값 설정 자리. 장시간 통화 감지는 2단계라 1단계에서는 값만 저장한다. */
data class Thresholds(val longCallMinutes: Int = DEFAULT_LONG_CALL_MINUTES) {
    companion object {
        const val DEFAULT_LONG_CALL_MINUTES = 30
        const val DEMO_LONG_CALL_MINUTES = 5

        fun of(demoMode: Boolean) =
            Thresholds(if (demoMode) DEMO_LONG_CALL_MINUTES else DEFAULT_LONG_CALL_MINUTES)
    }
}
