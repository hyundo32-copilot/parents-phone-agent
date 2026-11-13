package com.hyundo.parentsagent.android

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** 부모님의 중단 요청. 복원 단계 사이마다 확인한다. */
object StopController : StopSignal {
    private val flag = AtomicBoolean(false)
    fun request() = flag.set(true)
    override fun reset() = flag.set(false)
    override val requested: Boolean get() = flag.get()
}

/** "지금 고치는 중" 표시 상태. 화면(앱 배너)과 접근성 오버레이가 구독한다. */
object StatusBus : StatusSink {
    @Volatile var fixingText: String? = null
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    override fun fixing(text: String) = set(text)
    override fun idle() = set(null)

    private fun set(t: String?) {
        fixingText = t
        main.post { listeners.forEach { it() } }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}
