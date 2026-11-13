package com.hyundo.parentsagent.android

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.hyundo.parentsagent.core.AttemptResult
import com.hyundo.parentsagent.core.Deviation
import com.hyundo.parentsagent.core.RestoreAttempt
import com.hyundo.parentsagent.core.SettingKey
import com.hyundo.parentsagent.core.SettingValue

/** A등급 복원 수단 실행. 판정(할지 말지)은 RestorePolicy가 하고, 여기서는 방법만 안다. */
class Restorer(context: Context, private val reader: SettingsReader, private val deep: Boolean) {
    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    fun restore(dev: Deviation): RestoreAttempt {
        val before = dev.actual
        val (method, attempted) = try {
            apply(dev)
        } catch (e: SecurityException) {
            "권한 없음" to false
        } catch (e: Exception) {
            "오류" to false
        }
        val after = reader.read(dev.key, deep)
        val result = when {
            StopController.requested && !dev.expected.matches(after) -> AttemptResult.STOPPED_BY_USER
            dev.expected.matches(after) -> AttemptResult.SUCCESS
            else -> AttemptResult.FAILED
        }
        return RestoreAttempt(dev.key, if (attempted) method else "$method (실행 못 함)", before, after, result)
    }

    /** @return (수단 설명, 실행 시도 여부) */
    private fun apply(dev: Deviation): Pair<String, Boolean> {
        val service = AgentAccessibilityService.instance
        val flag = (dev.expected as? SettingValue.Flag)?.on
        return when (dev.key) {
            SettingKey.RINGER_MODE -> {
                // 실기 확인 필요: 방해금지 중에는 알림 정책 접근 없이 링어 모드를 못 바꿀 수 있음
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                "일반 API (벨소리 모드)" to true
            }
            SettingKey.VOLUME_RING -> {
                audio.setStreamVolume(AudioManager.STREAM_RING, (dev.expected as SettingValue.Level).value, 0)
                "일반 API (볼륨)" to true
            }
            SettingKey.VOLUME_MEDIA -> {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (dev.expected as SettingValue.Level).value, 0)
                "일반 API (볼륨)" to true
            }
            SettingKey.DND -> restoreDnd(service)
            SettingKey.AIRPLANE_MODE -> tile(service, Tile.AIRPLANE, SettingKey.AIRPLANE_MODE, flag)
            SettingKey.WIFI -> tile(service, Tile.WIFI, SettingKey.WIFI, flag)
            SettingKey.MOBILE_DATA -> tile(service, Tile.MOBILE_DATA, SettingKey.MOBILE_DATA, flag)
            SettingKey.NOTIF_PHONE_APP, SettingKey.NOTIF_SMS_APP -> {
                val pkg = reader.packageFor(dev.key)
                if (service == null || pkg == null || flag == null) {
                    "접근성 (앱 알림 설정)" to false
                } else {
                    service.setAppNotifications(pkg, flag)
                    "접근성 (앱 알림 설정)" to true
                }
            }
            SettingKey.SIM_READY -> "자동 복원 대상 아님" to false
        }
    }

    private fun restoreDnd(service: AgentAccessibilityService?): Pair<String, Boolean> {
        // 일반 API 먼저: Android 15+ targetSdk 35+ 앱은 사용자가 직접 켠 방해금지를 못 끌 수 있다 → 실기 확인 필요
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            if (reader.read(SettingKey.DND, false) == SettingValue.Flag(false)) return "일반 API (방해금지)" to true
        }
        return tile(service, Tile.DND, SettingKey.DND, false)
    }

    private fun tile(service: AgentAccessibilityService?, tile: Tile, key: SettingKey, want: Boolean?): Pair<String, Boolean> {
        val label = "접근성 빠른 설정 탭"
        if (service == null || want == null) return label to false
        service.toggleTile(tile) { reader.read(key, false) == SettingValue.Flag(want) }
        return label to true
    }
}
