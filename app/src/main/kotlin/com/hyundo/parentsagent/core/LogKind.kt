package com.hyundo.parentsagent.core

/** 기록의 종류. 부모님 영역에 보일지 여부와 기호를 정한다. 저장은 짧은 code 문자열로 한다. */
enum class LogKind(val code: String) {
    FIXED("f"), FAILED("x"), INFO("i"), DEV("d");

    companion object {
        /** null이거나 모르는 값이면 DEV (예전 기록은 부모님 영역에 안 나온다). */
        fun fromCode(c: String?): LogKind = entries.firstOrNull { it.code == c } ?: DEV
    }
}
