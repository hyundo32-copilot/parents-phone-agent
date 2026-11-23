package com.hyundo.parentsagent.android

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import com.hyundo.parentsagent.core.AttemptResult
import com.hyundo.parentsagent.core.Deviation
import com.hyundo.parentsagent.core.RestoreAttempt
import com.hyundo.parentsagent.core.RestorePlan
import com.hyundo.parentsagent.core.SettingKey
import com.hyundo.parentsagent.core.SettingValue
import com.hyundo.parentsagent.core.Settle

/** A등급 복원 수단 실행. 판정(할지 말지)은 RestorePolicy가 하고, 여기서는 방법만 안다. */
class Restorer(context: Context, private val reader: SettingsReader, private val deep: Boolean) : RestoreExecutor {
    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    override fun restore(dev: Deviation, apiOnly: Boolean): RestoreAttempt {
        val before = dev.actual
        val (method, attempted) = try {
            apply(dev, apiOnly)
        } catch (e: SecurityException) {
            "권한 없음" to false
        } catch (e: Exception) {
            "오류" to false
        }
        // 와이파이는 스위치를 켠 뒤 전파가 올라올 때까지 isWifiEnabled 가 false라서, 바로 읽으면 고쳐졌는데도 실패로 본다.
        val after = if (attempted && dev.key in SETTLE_KEYS) {
            val audio = dev.key in RestorePlan.API_FIRST_AUDIO
            Settle.await(
                dev.expected, { reader.read(dev.key, deep) }, Thread::sleep, { StopController.requested },
                timeoutMs = if (audio) Settle.AUDIO_TIMEOUT_MS else Settle.DEFAULT_TIMEOUT_MS,
                intervalMs = if (audio) Settle.AUDIO_INTERVAL_MS else Settle.DEFAULT_INTERVAL_MS,
            )
        } else {
            reader.read(dev.key, deep)
        }
        val result = when {
            RestorePlan.deferredAfterApi(dev.key, apiOnly, attempted, dev.expected.matches(after)) -> AttemptResult.DEFERRED
            StopController.requested && !dev.expected.matches(after) -> AttemptResult.STOPPED_BY_USER
            dev.expected.matches(after) -> AttemptResult.SUCCESS
            else -> AttemptResult.FAILED
        }
        val base = if (attempted || result == AttemptResult.DEFERRED) method else "$method (실행 못 함)"
        // 실패 원인이 화면이 아니라 출력 기기·고정 볼륨일 수 있어서 진단용으로 남긴다
        val shown = if (result != AttemptResult.SUCCESS && dev.key in RestorePlan.API_FIRST_AUDIO) "$base ${audioDiagnostics()}" else base
        return RestoreAttempt(dev.key, shown, before, after, result)
    }

    private fun audioDiagnostics(): String = runCatching {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString(",") { "${it.type}:${it.productName}" }
        "[출력 기기: $outputs, 볼륨 고정: ${audio.isVolumeFixed}]"
    }.getOrDefault("[출력 기기 확인 실패]")

    /** @return (수단 설명, 실행 시도 여부) */
    private fun apply(dev: Deviation, apiOnly: Boolean): Pair<String, Boolean> {
        val service = AgentAccessibilityService.instance
        val flag = (dev.expected as? SettingValue.Flag)?.on
        return when (dev.key) {
            SettingKey.RINGER_MODE -> {
                // 알림 정책 접근이 없으면 SecurityException: 실패 사유로 돌려주고 점검은 계속한다
                RestorePlan.applyRinger { audio.ringerMode = ringerModeFor((dev.expected as SettingValue.Mode).name) }
            }
            SettingKey.VOLUME_RING -> {
                audio.setStreamVolume(AudioManager.STREAM_RING, (dev.expected as SettingValue.Level).value, 0)
                "일반 API (볼륨)" to true
            }
            SettingKey.VOLUME_MEDIA -> {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (dev.expected as SettingValue.Level).value, 0)
                "일반 API (볼륨)" to true
            }
            SettingKey.DND -> restoreDnd(service, apiOnly)
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

    companion object {
        /** 값이 바뀌는 데 시간이 걸려 복원 직후 재읽기를 기다려야 하는 항목. 나머지는 설정값이 동기적으로 바뀐다. */
        private val SETTLE_KEYS = setOf(SettingKey.WIFI) + RestorePlan.API_FIRST_AUDIO

        /** 기준의 벨소리 모드 이름을 AudioManager 상수로. 기준이 진동이면 진동으로 되돌린다 (원칙 1: 기준이 먼저다). */
        fun ringerModeFor(name: String): Int = when (name) {
            "VIBRATE" -> AudioManager.RINGER_MODE_VIBRATE
            "SILENT" -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
    }

    /**
     * @param apiOnly 화면을 못 쓰는 상황. 일반 API로 안 되거나 권한이 없으면 접근성으로 가지 않고
     * (SCREEN_OFF, false)를 돌려준다. restore()가 이걸 DEFERRED로 바꾼다.
     */
    private fun restoreDnd(service: AgentAccessibilityService?, apiOnly: Boolean): Pair<String, Boolean> {
        val granted = nm.isNotificationPolicyAccessGranted
        val path = RestorePlan.dndPath(granted, if (apiOnly) RestorePlan.SCREEN_OFF else null)
        if (path == RestorePlan.DndPath.DeferAll) return RestorePlan.SCREEN_OFF to false
        // 일반 API 먼저: Android 15+ targetSdk 35+ 앱은 사용자가 직접 켠 방해금지를 못 끌 수 있다 → 실기 확인 필요
        if (granted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            if (reader.read(SettingKey.DND, false) == SettingValue.Flag(false)) return "일반 API (방해금지)" to true
        }
        if (path == RestorePlan.DndPath.Api) return RestorePlan.SCREEN_OFF to false
        return tile(service, Tile.DND, SettingKey.DND, false)
    }

    private fun tile(service: AgentAccessibilityService?, tile: Tile, key: SettingKey, want: Boolean?): Pair<String, Boolean> {
        val label = "접근성 빠른 설정 탭"
        if (service == null || want == null) return label to false
        if (service.toggleTile(tile) { reader.read(key, false) == SettingValue.Flag(want) }) return label to true
        // 빠른 설정 타일이 안 되면 설정 화면의 스위치로 시도한다 (One UI 9.0 실기에서 타일 실패 확인).
        val (intent, labels) = settingsFallback(key) ?: return label to true
        val r = service.setSwitchInSettings(intent, labels, want, singlePurpose = key == SettingKey.AIRPLANE_MODE)
        // 비행기 모드만 전용 페이지(스위치 1개)라 단일 스위치 폴백을 허용한다. 와이파이·데이터·방해금지는 스위치가 여럿이다.
        // 못 찾았거나 못 눌렀으면 실행한 게 아니다. 사유를 남겨야 실기 로그로 원인을 알 수 있다.
        return "접근성 설정 화면 스위치 (${r.describe()})" to r.outcome.executed
    }

    /** 타일이 안 될 때 설정 화면으로 여는 차선책. 없으면 null. */
    private fun settingsFallback(key: SettingKey): Pair<Intent, List<String>>? = when (key) {
        SettingKey.AIRPLANE_MODE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS) to listOf("비행기 모드", "Airplane mode")
        SettingKey.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS) to listOf("Wi-Fi", "와이파이")
        SettingKey.MOBILE_DATA -> Intent(Settings.ACTION_WIRELESS_SETTINGS) to listOf("모바일 데이터", "Mobile data")
        SettingKey.DND -> Intent("android.settings.ZEN_MODE_SETTINGS") to listOf("방해 금지", "Do not disturb")
        else -> null
    }
}
