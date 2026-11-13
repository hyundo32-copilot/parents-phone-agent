package com.hyundo.parentsagent

import com.hyundo.parentsagent.core.*
import com.hyundo.parentsagent.net.*
import java.io.IOException
import java.time.LocalDateTime

class FakeTransport(private val responses: MutableList<() -> HttpResponse>) : HttpTransport {
    data class Call(val url: String, val headers: Map<String, String>, val body: String)

    val calls = mutableListOf<Call>()
    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResponse {
        calls += Call(url, headers, body)
        return responses.removeAt(0)()
    }
}

class RecordingSleeper : Sleeper {
    val slept = mutableListOf<Long>()
    override fun sleep(ms: Long) { slept += ms }
}

fun ok(body: String) = HttpResponse(200, emptyMap(), body)
fun chat(content: String) = ok("""{"choices":[{"message":{"role":"assistant","content":${org.json.JSONObject.quote(content)}}}]}""")
fun netDown(): () -> HttpResponse = { throw IOException("망 끊김") }

fun flag(on: Boolean) = SettingValue.Flag(on)

fun baseline(night: Pair<Int, Int> = 22 * 60 to 7 * 60) = Snapshot.capture(
    mapOf(
        SettingKey.AIRPLANE_MODE to flag(false),
        SettingKey.WIFI to flag(true),
        SettingKey.MOBILE_DATA to flag(true),
        SettingKey.RINGER_MODE to SettingValue.Mode("NORMAL"),
        SettingKey.VOLUME_RING to SettingValue.Level.current(5),
        SettingKey.VOLUME_MEDIA to SettingValue.Level.current(8),
        SettingKey.DND to flag(false),
        SettingKey.NOTIF_PHONE_APP to flag(true),
        SettingKey.NOTIF_SMS_APP to flag(true),
        SettingKey.SIM_READY to flag(true),
    ),
    nowMillis = 1_000L, nightStartMin = night.first, nightEndMin = night.second,
)

fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): LocalDateTime = LocalDateTime.of(y, m, d, h, min)

fun ctx(now: LocalDateTime, roaming: Boolean = false, attempts: Int = 0) =
    PolicyContext(now, 22 * 60, 7 * 60, roaming, attempts)

fun dev(key: SettingKey, expected: SettingValue, actual: SettingValue) = Deviation(key, expected, actual)
