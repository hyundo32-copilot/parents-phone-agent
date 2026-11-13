package com.hyundo.parentsagent.core

/** 스냅샷 기준 항목 (설계 §3). autoRestorable = 1단계 A등급 자동 복원 대상 (설계 §5). */
enum class SettingKey(val label: String, val autoRestorable: Boolean) {
    AIRPLANE_MODE("비행기 모드", true),
    WIFI("와이파이", true),
    MOBILE_DATA("모바일 데이터", true),
    RINGER_MODE("벨소리 모드", true),
    VOLUME_RING("벨소리 볼륨", true),
    VOLUME_MEDIA("미디어 볼륨", true),
    DND("방해금지", true),
    NOTIF_PHONE_APP("전화 앱 알림", true),
    NOTIF_SMS_APP("문자 앱 알림", true),

    /** 읽기 전용. SIM 문제는 C등급이라 자동 복원하지 않고 현도에게 넘긴다. */
    SIM_READY("SIM 인식", false),
}
