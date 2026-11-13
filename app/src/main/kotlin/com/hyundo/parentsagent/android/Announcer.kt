package com.hyundo.parentsagent.android

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** TTS 한 줄 안내. 한국어 음성이 없거나 초기화 전이면 말하지 못하고 false를 돌려준다. */
class Announcer(context: Context) : Speaker {
    @Volatile private var ready = false
    private val pending = HashMap<String, CountDownLatch>()
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
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = release(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = release(utteranceId)
            private fun release(id: String?) {
                synchronized(pending) { pending.remove(id) }?.countDown()
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
}
