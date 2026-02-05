package com.coremotion.perik3.util

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MeasurementFileNameUtil {

    private fun todayYYMMDD(): String =
        SimpleDateFormat("yyMMdd", Locale.KOREA).format(Date())

    private fun stageFromTab(tabIndex: Int): String = "T${tabIndex + 1}"

    private fun twoDigits(n: Int): String =
        n.coerceIn(0, 99).toString().padStart(2, '0')

    private fun nextSeq(prefs: SharedPreferences, keyBase: String): Int {
        val last = prefs.getInt(keyBase, 0)
        val next = if (last >= 99) 1 else last + 1
        prefs.edit().putInt(keyBase, next).apply()
        return next
    }

    /**
     * 예: AA01-260127-T2-5-01
     */
    fun buildFileName(
        patientId: String,
        tabIndex: Int,
        positionIndex: Int,
        prefs: SharedPreferences
    ): String {
        val date = todayYYMMDD()
        val stage = stageFromTab(tabIndex)

        val seqKey = "MEAS_SEQ_${patientId}_${date}_${stage}_${positionIndex}"
        val seq = nextSeq(prefs, seqKey)

        return "${patientId}-${date}-${stage}-${positionIndex}-${twoDigits(seq)}"
    }
}