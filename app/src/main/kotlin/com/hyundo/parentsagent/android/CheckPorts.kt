package com.hyundo.parentsagent.android

import com.hyundo.parentsagent.core.Deviation
import com.hyundo.parentsagent.core.LastCheckGrade
import com.hyundo.parentsagent.core.LogKind
import com.hyundo.parentsagent.core.RestoreAttempt
import com.hyundo.parentsagent.core.RestoreHistory
import com.hyundo.parentsagent.core.SettingKey
import com.hyundo.parentsagent.core.SettingValue
import com.hyundo.parentsagent.core.Snapshot

/*
 * CheckRunner 가 기대는 바깥 세계. 안드로이드 구현은 이 파일 밖에 있고, 단위 테스트는 가짜를 꽂는다.
 * 이 파일은 안드로이드 클래스를 import 하지 않는다.
 */

/** 로밍 판정. UNKNOWN(비행기 모드·서비스 없음·예외)은 로밍일 수 있는 것으로 다룬다 (fail-closed). */
enum class RoamingState { HOME, ROAMING, UNKNOWN }

interface DeviceReader {
    fun readAll(deep: Boolean): Map<SettingKey, SettingValue>
    fun roamingState(): RoamingState
}

/** 접근성 수단(빠른 설정·설정 화면)을 지금 써도 되는지. 못 쓰면 사유를 돌려준다. */
interface ScreenGate {
    fun accessibilityBlocker(): String?
}

interface RestoreExecutor {
    fun restore(dev: Deviation): RestoreAttempt
}

interface CheckStore {
    val consented: Boolean
    var lastCheckMillis: Long
    fun snapshot(): Snapshot?
    fun history(): RestoreHistory
    fun saveHistory(h: RestoreHistory)
    fun appendLog(text: String)

    /** 종류와 부모님용 문장을 함께 남긴다. 구현이 안 받으면 원문만 남긴다. */
    fun appendLog(text: String, kind: LogKind, parentText: String?) = appendLog(text)

    /** 마지막 점검의 시각·결과 등급. 화면의 상태 카드가 앱을 다시 켜도 같은 상태를 보여 주려고 저장한다. */
    fun saveLastResult(atMillis: Long, grade: LastCheckGrade) {}
}

interface Speaker {
    fun speakAndWait(text: String): Boolean
    fun speak(text: String): Boolean
}

interface StatusSink {
    fun fixing(text: String)
    fun idle()
}

interface StopSignal {
    val requested: Boolean
    fun reset()
}
