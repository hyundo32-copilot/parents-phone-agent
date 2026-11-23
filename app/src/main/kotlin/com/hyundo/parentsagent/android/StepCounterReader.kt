package com.hyundo.parentsagent.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 폰 만보기 센서(TYPE_STEP_COUNTER)를 한 번 읽는다. 호출한 스레드에서 최대 [TIMEOUT_MS] 기다리므로 메인 스레드에서 부르면 안 된다. */
object StepCounterReader {
    const val TIMEOUT_MS = 5_000L

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** 누적 걸음수. 권한·센서가 없거나 타임아웃이면 null (알 수 없음). */
    fun read(context: Context): Long? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val latch = CountDownLatch(1)
        val value = AtomicReference<Long?>(null)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty() && value.compareAndSet(null, event.values[0].toLong())) latch.countDown()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        return try {
            if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) return null
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            value.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: RuntimeException) {
            null
        } finally {
            manager.unregisterListener(listener)
        }
    }
}
