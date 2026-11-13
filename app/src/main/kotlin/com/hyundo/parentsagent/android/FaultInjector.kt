package com.hyundo.parentsagent.android

import android.content.Context
import android.media.AudioManager
import com.hyundo.parentsagent.core.SettingKey
import com.hyundo.parentsagent.core.SettingValue

/**
 * 장애 주입 (데모·테스트용). 부모님 폰에서 쓰는 기능이 아니라 데모 기기에서 고장을 한 번에 재현한다.
 * 비행기 모드·알림 끄기는 일반 API가 없어 접근성 탭으로 켠다. 실기 확인 필요.
 * 결과 문구를 돌려준다. 백그라운드 스레드에서 호출.
 */
class FaultInjector(context: Context, private val reader: SettingsReader) {
    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(AudioManager::class.java)

    fun airplaneOn(): String {
        val s = AgentAccessibilityService.instance ?: return "접근성 서비스가 꺼져 있어요"
        val ok = s.toggleTile(Tile.AIRPLANE) { reader.read(SettingKey.AIRPLANE_MODE, false) == SettingValue.Flag(true) }
        return if (ok) "비행기 모드를 켰어요" else "비행기 모드를 못 켰어요 (실기 확인 필요)"
    }

    fun silent(): String = try {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) "무음으로 바꿨어요" else "무음으로 못 바꿨어요 (알림 정책 접근 필요)"
    } catch (e: SecurityException) {
        "무음으로 못 바꿨어요 (알림 정책 접근 필요)"
    }

    fun dnd(): String {
        // setInterruptionFilter 는 쓰지 않는다: Android 15+(targetSdk 35+)에서는 전역 방해금지가 아니라
        // 앱 자기 규칙만 켜서, 부모님이 직접 켠 방해금지와 다른 상태가 된다. 접근성 타일로 실제처럼 켠다.
        val s = AgentAccessibilityService.instance ?: return "접근성 서비스가 꺼져 있어요"
        val ok = s.toggleTile(Tile.DND) { reader.read(SettingKey.DND, false) == SettingValue.Flag(true) }
        return if (ok) "방해금지를 켰어요" else "방해금지를 못 켰어요 (실기 확인 필요)"
    }

    fun notificationsOff(): String {
        val s = AgentAccessibilityService.instance ?: return "접근성 서비스가 꺼져 있어요"
        val pkg = reader.defaultDialerPackage() ?: return "기본 전화 앱을 못 찾았어요"
        return if (s.setAppNotifications(pkg, false)) "전화 앱 알림을 껐어요" else "알림을 못 껐어요 (실기 확인 필요)"
    }
}
