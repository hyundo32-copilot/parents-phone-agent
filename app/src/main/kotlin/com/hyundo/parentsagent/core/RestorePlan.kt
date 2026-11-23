package com.hyundo.parentsagent.core

enum class GateMode {
    /** 화면 상태와 상관없이 복원한다. */
    NONE,

    /** 일반 API만 먼저 시도하고, 안 되면 잠금 해제 때로 미룬다. */
    API_FIRST,

    /** 접근성이 필요해서 통째로 잠금 해제 때로 미룬다. */
    DEFER,
}

/** 복원 순서와 화면 게이트 판정 (순수 함수). */
object RestorePlan {
    /** 복원 수단이 접근성(빠른 설정·설정 화면)인 항목. 방해금지는 일반 API가 먼저지만 폴백이 접근성이라 같이 묶는다. */
    val NEEDS_ACCESSIBILITY: Set<SettingKey> = setOf(
        SettingKey.AIRPLANE_MODE, SettingKey.WIFI, SettingKey.MOBILE_DATA, SettingKey.DND,
        SettingKey.NOTIF_PHONE_APP, SettingKey.NOTIF_SMS_APP,
    )

    /** 일반 API(AudioManager)로 복원하는 오디오 항목. 화면 꺼짐에서도 한 번 시도하고, 안 되면 미룬다. */
    val API_FIRST_AUDIO: Set<SettingKey> = setOf(SettingKey.RINGER_MODE, SettingKey.VOLUME_RING, SettingKey.VOLUME_MEDIA)

    const val RINGER_NO_POLICY = "알림 정책 접근 없음"
    const val SCREEN_OFF = "화면 꺼짐"

    /**
     * 방해금지를 벨소리 모드·벨소리 볼륨보다 먼저 복원한다. 우선순위 모드의 방해금지가 켜져 있으면
     * 벨소리 모드를 NORMAL로 바꿔도 SILENT로 유지돼서, 방해금지를 먼저 꺼야 한다.
     * 나머지는 원래 순서를 지킨다 (방해금지를 두 항목 바로 앞으로만 옮긴다).
     */
    fun orderForRestore(devs: List<Deviation>): List<Deviation> {
        val dnd = devs.firstOrNull { it.key == SettingKey.DND } ?: return devs
        val rest = devs.filter { it.key != SettingKey.DND }
        val firstSound = rest.indexOfFirst { it.key == SettingKey.RINGER_MODE || it.key == SettingKey.VOLUME_RING }
        val at = if (firstSound >= 0) firstSound else devs.indexOf(dnd).coerceAtMost(rest.size)
        return rest.toMutableList().also { it.add(at, dnd) }
    }

    sealed class DndPath {
        /** 일반 API만 쓴다. 안 되면 미룬다. */
        data object Api : DndPath()

        /** 일반 API(알림 정책 접근이 있을 때)를 먼저 하고, 안 되면 접근성으로 간다. */
        data object ApiThenAccessibility : DndPath()

        /** 일반 API를 못 쓰고 접근성도 못 쓰니 통째로 미룬다. */
        data object DeferAll : DndPath()
    }

    /** @param blocker 접근성을 못 쓰는 사유 (화면 꺼짐 등). 쓸 수 있으면 null. */
    fun dndPath(policyGranted: Boolean, blocker: String?): DndPath = when {
        policyGranted && blocker != null -> DndPath.Api
        !policyGranted && blocker != null -> DndPath.DeferAll
        else -> DndPath.ApiThenAccessibility
    }

    fun gateMode(key: SettingKey, blocker: String?): GateMode = when {
        blocker == null -> GateMode.NONE
        key == SettingKey.DND || key in API_FIRST_AUDIO -> GateMode.API_FIRST
        key in NEEDS_ACCESSIBILITY -> GateMode.DEFER
        else -> GateMode.NONE
    }

    fun deferredLogLine(label: String): String = "$label: 화면 꺼짐이라 일반 API로 안 돼서 잠금 해제 때 고칠게요"

    /**
     * API_FIRST로 시도한 결과를 미룸(DEFERRED)으로 바꿀지.
     * 방해금지는 실행하지 못했을 때(권한 없음 등), 오디오 항목은 시도했더라도 값이 안 맞으면 미룬다.
     */
    fun deferredAfterApi(key: SettingKey, apiOnly: Boolean, attempted: Boolean, matched: Boolean): Boolean =
        apiOnly && !matched && ((key == SettingKey.DND && !attempted) || key in API_FIRST_AUDIO)

    fun alreadyOkLogLine(label: String): String = "$label: 다른 항목을 고치는 사이 이미 원래대로 돌아와서 건드리지 않았어요 (이미 정상)"

    /**
     * 벨소리 모드 변경. 알림 정책 접근이 없으면 SecurityException이 나는데, 점검을 멈추지 않고 실패 사유로 돌려준다.
     * @return (수단 설명, 실행 시도 여부)
     */
    fun applyRinger(set: () -> Unit): Pair<String, Boolean> = try {
        set()
        "일반 API (벨소리 모드)" to true
    } catch (e: SecurityException) {
        RINGER_NO_POLICY to false
    }
}
