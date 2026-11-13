package com.hyundo.parentsagent.net

import org.json.JSONArray

/** 망이 끊겨 못 보낸 에스컬레이션 원문 대기열. 저장소는 호출자가 JSON 문자열로 넘겨받는다. */
class Outbox(initialJson: String? = null, private val maxSize: Int = MAX_SIZE) {
    private val items = ArrayDeque<String>()

    init {
        if (!initialJson.isNullOrBlank()) {
            val a = JSONArray(initialJson)
            for (i in 0 until a.length()) items.addLast(a.getString(i))
        }
    }

    val size: Int get() = items.size

    fun enqueue(raw: String) {
        items.addLast(raw)
        while (items.size > maxSize) items.removeFirst() // 가장 오래된 것부터 버린다
    }

    fun peek(): String? = items.firstOrNull()
    fun removeFirst(): String? = items.removeFirstOrNull()
    fun toJson(): String = JSONArray(items.toList()).toString()

    companion object {
        const val MAX_SIZE = 30
    }
}
