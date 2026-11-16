package com.hyundo.parentsagent.core

/** 부모님 영역에 보이는 기록 문장 (존댓말, 기술 용어·숫자·괄호 없음). 시각은 화면에서 붙인다. */
object ParentText {
    const val NO_PROBLEM = "점검했어요, 문제 없어요"

    fun fixed(label: String) = "$label 설정을 원래대로 되돌렸어요"

    /** 가족 알림은 실제로 보냈을 때만 "알렸어요", 못 보냈으면 전화를 부탁한다. */
    fun failed(label: String, familyNotified: Boolean) =
        "$label 설정을 못 고쳤어요" + if (familyNotified) ". 가족에게 알렸어요" else ". 가족에게 전화해 주세요"

    /** 횟수 제한으로 더 고치지 않은 경우도 같은 실패 문장을 쓴다. */
    fun blockedByLimit(label: String, familyNotified: Boolean) = failed(label, familyNotified)

    /** 횟수 제한(하루·점검당)으로 막은 사유인지. 부모님 영역에는 실패로 보인다. */
    fun isLimit(reason: SkipReason) = reason == SkipReason.DAILY_LIMIT || reason == SkipReason.RUN_LIMIT
}
