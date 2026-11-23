package com.hyundo.parentsagent.core

object UnlockCheckPolicy {
    /** 미룬 항목이 있으면 [minGapMs] 간격을 무시하고 따라잡는다. 없으면 배터리 부담을 줄이려고 간격을 지킨다. */
    fun shouldRun(lastCheckMillis: Long, nowMillis: Long, hasDeferred: Boolean, minGapMs: Long): Boolean =
        hasDeferred || nowMillis - lastCheckMillis >= minGapMs

    /** 개발자 로그(DEV) 한 줄: 잠금 해제를 받았는지, 그때 판정 근거가 무엇이었는지 실기에서 가려내려고 남긴다. */
    fun logLine(lastCheckMillis: Long, nowMillis: Long, deferredCount: Int, run: Boolean): String {
        val last = if (lastCheckMillis <= 0L) "마지막 점검 없음" else "마지막 점검 ${(nowMillis - lastCheckMillis) / 60_000L}분 전"
        return "잠금 해제 감지: 미룬 항목 ${deferredCount}개, $last → ${if (run) "점검함" else "건너뜀"}"
    }
}
