package com.hyundo.parentsagent.android

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager

/** 화면 꺼짐·잠금·통화 중이면 접근성 수단을 쓰지 않는다 (빠른 설정이 안 열리거나 통화 중 화면이 튀어나오는 것을 막기 위해). */
class AndroidScreenGate(context: Context) : ScreenGate {
    private val ctx = context.applicationContext

    override fun accessibilityBlocker(): String? {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val audio = ctx.getSystemService(AudioManager::class.java)
        return when {
            pm != null && !pm.isInteractive -> "화면 꺼짐"
            km != null && km.isKeyguardLocked -> "잠금 상태"
            audio != null && audio.mode == AudioManager.MODE_IN_CALL -> "통화 중"
            else -> null
        }
    }
}
