package com.hyundo.parentsagent.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

interface HttpTransport {
    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResponse
}

interface Sleeper {
    fun sleep(ms: Long)
}

object ThreadSleeper : Sleeper {
    override fun sleep(ms: Long) = Thread.sleep(ms)
}

class UrlConnectionTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val hs = HashMap<String, String>()
            conn.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) hs[k] = v.last() }
            return HttpResponse(code, hs, text)
        } finally {
            conn.disconnect()
        }
    }
}
