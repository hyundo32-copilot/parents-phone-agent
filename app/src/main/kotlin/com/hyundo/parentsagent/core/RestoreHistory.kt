package com.hyundo.parentsagent.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 자동 복원 안전 규칙(설계 §5)이 쓰는 상태: 하루 복원 횟수, 연속 이탈 일수, 일시정지 항목, 통지 중복 방지.
 * 순수 메모리 객체이고 JSON으로 저장·복원한다.
 */
class RestoreHistory {
    private val restores = HashMap<String, Int>() // "KEY|date" -> 횟수
    private val deviationDays = HashMap<SettingKey, MutableSet<LocalDate>>()
    private val paused = LinkedHashSet<SettingKey>()
    private val notified = HashSet<String>() // "KEY|date|reason"

    fun restoreCount(key: SettingKey, date: LocalDate): Int = restores["${key.name}|$date"] ?: 0

    fun recordRestoreAttempt(key: SettingKey, date: LocalDate) {
        val k = "${key.name}|$date"
        restores[k] = (restores[k] ?: 0) + 1
    }

    fun recordDeviation(key: SettingKey, date: LocalDate) {
        deviationDays.getOrPut(key) { HashSet() }.add(date)
    }

    /** today 를 포함해 하루도 빠짐없이 이탈이 이어진 일수. */
    fun consecutiveDeviationDays(key: SettingKey, today: LocalDate): Int {
        val days = deviationDays[key] ?: return 0
        var n = 0
        var d = today
        while (d in days) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    fun pause(key: SettingKey) { paused += key }
    fun resume(key: SettingKey) {
        paused -= key
        deviationDays.remove(key)
    }
    fun isPaused(key: SettingKey): Boolean = key in paused
    fun pausedKeys(): Set<SettingKey> = paused.toSet()

    /** 처음 통지하면 true. 같은 날 같은 사유로 현도에게 두 번 보내지 않기 위한 장치. */
    fun markNotified(key: SettingKey, date: LocalDate, reason: String): Boolean =
        notified.add("${key.name}|$date|$reason")

    fun toJson(today: LocalDate): String {
        val cutoff = today.minusDays(14)
        val o = JSONObject()
        val r = JSONObject()
        restores.forEach { (k, v) -> if (LocalDate.parse(k.substringAfter('|')) >= cutoff) r.put(k, v) }
        o.put("restores", r)
        val dv = JSONObject()
        deviationDays.forEach { (k, days) ->
            val arr = JSONArray()
            days.filter { it >= cutoff }.sorted().forEach { arr.put(it.toString()) }
            dv.put(k.name, arr)
        }
        o.put("deviationDays", dv)
        o.put("paused", JSONArray(paused.map { it.name }))
        val n = JSONArray()
        notified.filter { LocalDate.parse(it.split('|')[1]) >= cutoff }.forEach { n.put(it) }
        o.put("notified", n)
        return o.toString()
    }

    companion object {
        fun fromJson(s: String?): RestoreHistory {
            val h = RestoreHistory()
            if (s.isNullOrBlank()) return h
            val o = JSONObject(s)
            o.optJSONObject("restores")?.let { r -> for (k in r.keys()) h.restores[k] = r.getInt(k) }
            o.optJSONObject("deviationDays")?.let { dv ->
                for (name in dv.keys()) {
                    val key = SettingKey.entries.firstOrNull { it.name == name } ?: continue
                    val arr = dv.getJSONArray(name)
                    val set = HashSet<LocalDate>()
                    for (i in 0 until arr.length()) set += LocalDate.parse(arr.getString(i))
                    h.deviationDays[key] = set
                }
            }
            o.optJSONArray("paused")?.let { a ->
                for (i in 0 until a.length()) SettingKey.entries.firstOrNull { it.name == a.getString(i) }?.let { h.paused += it }
            }
            o.optJSONArray("notified")?.let { a -> for (i in 0 until a.length()) h.notified += a.getString(i) }
            return h
        }
    }
}
