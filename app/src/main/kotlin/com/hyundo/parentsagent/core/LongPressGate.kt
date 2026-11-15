package com.hyundo.parentsagent.core

/**
 * "길게 눌러서 열기" 상태 전이 (Android 없는 순수 로직).
 *
 * 누름 → 2초 경과(타이머) → 열림 → 손 떼기는 삼킴(클릭 안 일어남) → 다음 짧은 탭부터 닫기.
 * 손 떼기를 삼키지 않으면 그 ACTION_UP이 클릭이 되어 방금 열린 구역을 곧바로 닫아 버린다.
 */
class LongPressGate {
    private var armed = false
    private var swallowUp = false

    /** 손가락이 닿았다. 닫혀 있을 때만 타이머를 걸어야 하면 true. */
    fun onDown(isOpen: Boolean): Boolean {
        swallowUp = false
        armed = !isOpen
        return armed
    }

    /** 타이머가 만료됐다. 구역을 열어야 하면 true. */
    fun onTimeout(): Boolean {
        if (!armed) return false
        armed = false
        swallowUp = true
        return true
    }

    /** 손가락이 구역 밖으로 나갔다. 타이머를 취소해야 한다. */
    fun onLeave() { armed = false }

    /** 손을 뗐다. true면 이 이벤트를 소비해 클릭이 발생하지 않게 한다. */
    fun onUp(): Boolean {
        armed = false
        val swallow = swallowUp
        swallowUp = false
        return swallow
    }

    /** 터치가 취소됐다. */
    fun onCancel() {
        armed = false
        swallowUp = false
    }
}
