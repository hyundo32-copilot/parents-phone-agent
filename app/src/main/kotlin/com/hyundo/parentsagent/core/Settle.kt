package com.hyundo.parentsagent.core

/**
 * 복원 직후 값이 기준에 맞을 때까지 기다리며 다시 읽는다.
 * 와이파이는 스위치를 켜도 전파가 올라오는 동안 WifiManager.isWifiEnabled 가 false(ENABLING)로 나와서,
 * 바로 재읽기하면 실제로는 고쳐졌는데 실패로 판정한다. 설정값이 동기적으로 바뀌는 항목에는 쓰지 않는다.
 */
object Settle {
    const val DEFAULT_TIMEOUT_MS = 8_000L
    const val DEFAULT_INTERVAL_MS = 500L

    /** 오디오 항목은 짧게만 기다린다. 길게 막으면 Doze의 네트워크 예외 시간이 줄어든다. */
    const val AUDIO_TIMEOUT_MS = 1_500L
    const val AUDIO_INTERVAL_MS = 250L

    /**
     * 맞으면 바로 돌려주고, 안 맞으면 [intervalMs]마다 [timeoutMs]까지 다시 읽는다.
     * [shouldStop]이 true가 되면 더 기다리지 않는다. 마지막으로 읽은 값을 돌려준다 (판정은 호출한 쪽이 한다).
     */
    fun await(
        expected: SettingValue,
        read: () -> SettingValue,
        sleep: (Long) -> Unit,
        shouldStop: () -> Boolean = { false },
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): SettingValue {
        var value = read()
        var waited = 0L
        while (!expected.matches(value) && waited < timeoutMs && !shouldStop()) {
            sleep(intervalMs)
            waited += intervalMs
            value = read()
        }
        return value
    }
}

/** 설정 화면 스위치 작업의 결과. 로그에 남아 실기에서 어디서 막혔는지 알 수 있게 한다. */
enum class SwitchOutcome(val text: String, val executed: Boolean) {
    ALREADY("이미 원하는 상태", true),
    CHANGED("눌러서 바뀜", true),
    NOT_FOUND("스위치를 못 찾음", false),
    CLICK_FAILED("스위치를 못 눌렀음", false),
    NOT_CHANGED("눌렀지만 안 바뀜", true),
    CLICKED_UNVERIFIED("행을 눌렀음 (화면에 상태가 안 나와 확인 못 함)", true),
}

/**
 * [detail]은 못 찾았을 때의 화면 상황 (문구 노드 수, 화면 전체 스위치 수).
 * [strategy]는 스위치를 찾은 방법. 못 찾았으면 null.
 */
data class SwitchAttempt(val outcome: SwitchOutcome, val detail: String = "", val strategy: FindStrategy? = null) {
    fun describe(): String {
        val base = if (detail.isEmpty()) outcome.text else "${outcome.text}: $detail"
        return if (strategy == null) base else "$base [찾은 방법: ${strategy.text}]"
    }
}
