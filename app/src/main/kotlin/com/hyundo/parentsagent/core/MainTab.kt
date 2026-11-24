package com.hyundo.parentsagent.core

/** 메인 화면 탭. 기본은 부모님용 [PHONE_STATUS]. 저장된 값이 없거나 깨졌으면 기본으로 돌아간다. */
enum class MainTab(val label: String) {
    PHONE_STATUS("폰 상태"),
    FAMILY("가족 관리");

    companion object {
        val DEFAULT = PHONE_STATUS

        fun parse(raw: String?): MainTab = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
