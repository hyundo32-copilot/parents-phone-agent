package com.hyundo.parentsagent.android

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.accessibility.AccessibilityManager
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TTS 한 줄 안내. 한국어 음성이 없거나 초기화 전이면 말하지 못하고 false를 돌려준다.
 * 프로세스 전체에서 하나만 쓴다 (AgentRuntime). 점검의 안전 안내 음성도 이걸 쓰므로 화면(Activity)이 shutdown 하면 안 된다.
 */
class Announcer(context: Context) : Speaker {
    @Volatile private var ready = false
    private val pending = HashMap<String, CountDownLatch>()
    /** [speakOwned]로 시작한 발화의 결과 콜백. 화면이 직접 시작한 발화만 여기 있다. */
    private val owned = HashMap<String, (Boolean) -> Unit>()
    @Volatile private var speakingId: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        // 실기 확인 필요: 삼성 TTS 엔진의 한국어 지원과 오디오 경로(스피커/보청기)
        if (status == TextToSpeech.SUCCESS) {
            ready = ownLanguage()
        }
    }

    private fun ownLanguage(): Boolean {
        val r = tts.setLanguage(Locale.KOREAN)
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speakingId = utteranceId }
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // 중단된 발화는 실패가 아니므로 콜백 없이 정리만 한다
                if (speakingId == utteranceId) speakingId = null
                synchronized(owned) { owned.remove(utteranceId) }
                synchronized(pending) { pending.remove(utteranceId) }?.countDown()
            }
            private fun finish(id: String?, ok: Boolean) {
                if (speakingId == id) speakingId = null
                synchronized(pending) { pending.remove(id) }?.countDown()
                synchronized(owned) { owned.remove(id) }?.invoke(ok)
            }
        })
    }

    /** 말하고 끝날 때까지(최대 [maxWaitMs]) 기다린다. 말하지 못했으면 false. 백그라운드 스레드에서 호출. */
    override fun speakAndWait(text: String): Boolean = speakAndWait(text, 6_000)

    fun speakAndWait(text: String, maxWaitMs: Long): Boolean {
        if (!ready) return false
        val id = "u" + System.nanoTime()
        val latch = CountDownLatch(1)
        synchronized(pending) { pending[id] = latch }
        val res = tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (res != TextToSpeech.SUCCESS) {
            synchronized(pending) { pending.remove(id) }
            return false
        }
        latch.await(maxWaitMs, TimeUnit.MILLISECONDS)
        return true
    }

    override fun speak(text: String): Boolean {
        if (!ready) return false
        return tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "u" + System.nanoTime()) == TextToSpeech.SUCCESS
    }

    /**
     * 화면의 [읽어 주기]·점검 끝 음성용. 시작하지 못하거나 엔진에서 실패하면 [onResult](false), 끝까지 말하면 (true).
     * 중단([stopUtterance])하면 콜백이 오지 않는다. 콜백은 TTS 스레드에서 불릴 수 있다. 시작 못 하면 null.
     */
    fun speakOwned(text: String, onResult: (Boolean) -> Unit): String? {
        if (!ready) { onResult(false); return null }
        val id = "o" + System.nanoTime()
        synchronized(owned) { owned[id] = onResult }
        val res = tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (res != TextToSpeech.SUCCESS) {
            synchronized(owned) { owned.remove(id) }
            onResult(false)
            return null
        }
        return id
    }

    /**
     * 화면이 시작한 발화만 멈춘다. 안드로이드 TTS에는 발화 하나만 취소하는 API가 없어서,
     * 지금 말하는 것이 [id]일 때만 stop()한다 (점검의 안내 음성이 말하는 중이면 그대로 둔다).
     * 아직 시작 전이라 대기 중인 발화는 콜백만 지워 실패로 오인하지 않게 한다.
     */
    fun stopUtterance(id: String?) {
        if (id == null) return
        if (speakingId == id) tts.stop() else synchronized(owned) { owned.remove(id) }
    }

    companion object {
        /**
         * TalkBack처럼 화면을 소리로 읽어 주는 도구가 켜져 있는지. isEnabled()는 쓰지 않는다:
         * 이 앱 자신의 접근성 서비스가 늘 켜져 있어 항상 true가 된다.
         */
        fun isTalkBackOn(context: Context): Boolean =
            context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
    }
}
