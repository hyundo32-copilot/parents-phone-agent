package com.hyundo.parentsagent.android

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.hyundo.parentsagent.core.SettingKey
import com.hyundo.parentsagent.core.SettingValue

/** 현재 설정값을 읽는다. 값만 읽고 메시지·통화·연락처 내용은 건드리지 않는다. */
class SettingsReader(context: Context) : DeviceReader {
    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    fun defaultDialerPackage(): String? = ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    fun defaultSmsPackage(): String? = Telephony.Sms.getDefaultSmsPackage(ctx)

    fun packageFor(key: SettingKey): String? = when (key) {
        SettingKey.NOTIF_PHONE_APP -> defaultDialerPackage()
        SettingKey.NOTIF_SMS_APP -> defaultSmsPackage()
        else -> null
    }

    /**
     * @param deep true면 다른 앱의 알림 설정까지 접근성으로 화면을 열어 읽는다.
     *   다른 앱의 알림 허용 여부를 읽는 공개 API가 없어서다. 부모님이 폰을 쓰는 중에 설정 화면이
     *   튀어나오지 않도록 주기 점검에서는 false (알림 항목은 Unknown → 비교 제외).
     */
    override fun readAll(deep: Boolean): Map<SettingKey, SettingValue> =
        SettingKey.entries.associateWith { read(it, deep) }

    fun read(key: SettingKey, deep: Boolean): SettingValue = try {
        when (key) {
            SettingKey.AIRPLANE_MODE ->
                SettingValue.Flag(Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0)
            SettingKey.WIFI -> {
                val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
                if (wm == null) SettingValue.Unknown else SettingValue.Flag(wm.isWifiEnabled)
            }
            SettingKey.MOBILE_DATA -> {
                // 실기 확인 필요: 삼성 듀얼 SIM에서 mobile_data 키 값 (공개 API 없음)
                val v = Settings.Global.getInt(ctx.contentResolver, "mobile_data", -1)
                if (v < 0) SettingValue.Unknown else SettingValue.Flag(v != 0)
            }
            SettingKey.RINGER_MODE -> SettingValue.Mode(
                when (audio.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "SILENT"
                    AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
                    else -> "NORMAL"
                },
            )
            SettingKey.VOLUME_RING -> SettingValue.Level.current(audio.getStreamVolume(AudioManager.STREAM_RING))
            SettingKey.VOLUME_MEDIA -> SettingValue.Level.current(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            SettingKey.DND -> when (nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> SettingValue.Flag(false)
                NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> SettingValue.Unknown
                else -> SettingValue.Flag(true)
            }
            SettingKey.NOTIF_PHONE_APP, SettingKey.NOTIF_SMS_APP -> {
                val pkg = packageFor(key)
                val on = if (deep && pkg != null) AgentAccessibilityService.instance?.readAppNotifications(pkg) else null
                if (on == null) SettingValue.Unknown else SettingValue.Flag(on)
            }
            SettingKey.SIM_READY -> {
                val tm = ctx.getSystemService(TelephonyManager::class.java)
                SettingValue.Flag(tm?.simState == TelephonyManager.SIM_STATE_READY)
            }
        }
    } catch (e: Exception) {
        SettingValue.Unknown
    }

    /**
     * 해외 로밍 여부. 비행기 모드 중이거나 SIM·서비스가 없어 모르면 UNKNOWN (로밍일 수 있음으로 취급).
     * 비행기 모드에서는 isNetworkRoaming 이 false로 나오므로 그 값을 믿으면 안 된다.
     */
    override fun roamingState(): RoamingState = try {
        val airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        when {
            airplane || tm == null -> RoamingState.UNKNOWN
            tm.simState != TelephonyManager.SIM_STATE_READY -> RoamingState.UNKNOWN
            tm.networkOperator.isNullOrEmpty() -> RoamingState.UNKNOWN // 서비스 없음
            tm.isNetworkRoaming -> RoamingState.ROAMING
            else -> RoamingState.HOME
        }
    } catch (e: Exception) {
        RoamingState.UNKNOWN
    }
}
