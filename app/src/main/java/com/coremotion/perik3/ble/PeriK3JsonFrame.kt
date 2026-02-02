package com.coremotion.perik3.ble

import org.json.JSONObject
import kotlin.math.abs

data class PeriK3JsonFrame(
    val t: String,
    val ts: Long? = null,
    val s: Int? = null,
    val F: Double? = null,
    val x: Double? = null,
    val TF: Int? = null,
    val TI: Double? = null,
    val LV: Double? = null,
    val LD: Double? = null,
    val px: Double? = null,
    val py: Double? = null,
    val r: Double? = null,
    val receivedAtMs: Long = 0L
) {
    fun stateLabel(): String = when (s ?: -1) {
        0 -> "IDLE"
        1 -> "STABLE"
        2 -> "TREMOR"
        3 -> "PRESS"
        4 -> "PRESS_DONE"
        5 -> "CONTRACTION"
        else -> "UNKNOWN"
    }

    fun isEvent(): Boolean {
        val pxV = px ?: 0.0
        val pyV = py ?: 0.0
        val ldV = LD ?: 0.0
        return abs(pxV) > 1e-6 || abs(pyV) > 1e-6 || abs(ldV) > 1e-6
    }

    companion object {
        fun parseOrNull(raw: String, receivedAtMs: Long): PeriK3JsonFrame? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

            return try {
                val o = JSONObject(trimmed)
                val t = o.optString("t", "")
                if (t.isBlank()) return null

                PeriK3JsonFrame(
                    t = t,
                    ts = if (o.has("ts")) o.optLong("ts") else null,
                    s = if (o.has("s")) o.optInt("s") else null,
                    F = if (o.has("F")) o.optDouble("F") else null,
                    x = if (o.has("x")) o.optDouble("x") else null,
                    TF = if (o.has("TF")) o.optInt("TF") else null,
                    TI = if (o.has("TI")) o.optDouble("TI") else null,
                    LV = if (o.has("LV")) o.optDouble("LV") else null,
                    LD = if (o.has("LD")) o.optDouble("LD") else null,
                    px = if (o.has("px")) o.optDouble("px") else null,
                    py = if (o.has("py")) o.optDouble("py") else null,
                    r = if (o.has("r")) o.optDouble("r") else null,
                    receivedAtMs = receivedAtMs
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}