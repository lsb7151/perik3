package com.coremotion.perik3.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coremotion.perik3.ble.PeriK3JsonFrame
import com.coremotion.perik3.ui.MeasurementLogRow
import com.github.mikephil.charting.data.Entry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MeasurementViewModel : ViewModel() {

    // 0=T1, 1=T2, 2=T3
    private val _selectedTab = MutableLiveData(0)
    val selectedTab: LiveData<Int> = _selectedTab

    /**
     * ✅ 탭 변경 시: 리셋 없음
     * - 단지 "요약 텍스트(summary)"만 현재 탭 기준으로 다시 보여주기
     * - 로그/그래프는 탭별로 다르게 쌓는 구조라면 여기서 clearBuffersForTabChange()를 유지하면 되지만,
     *   너 요구사항은 "탭변경 리셋 안 됨"이라서 호출 제거.
     */
    fun setSelectedTab(index: Int) {
        if (_selectedTab.value == index) return
        _selectedTab.value = index
        updateSummaryText()
    }

    // ====== 좌측 헤더용 값들 ======
    private val _t1IndicatorState = MutableLiveData(0) // 0/1/2
    val t1IndicatorState: LiveData<Int> = _t1IndicatorState

    private val _t2FillFraction = MutableLiveData(0f)  // 0..1
    val t2FillFraction: LiveData<Float> = _t2FillFraction

    private val _t3BipolarValue = MutableLiveData(0f)  // -1..1 (LD)
    val t3BipolarValue: LiveData<Float> = _t3BipolarValue

    // T3 LV 하단 텍스트 (예: "3.1 mm/s")
    private val _t3LvText = MutableLiveData("")
    val t3LvText: LiveData<String> = _t3LvText

    // ====== ✅ 하단 요약(TextView 한 개로 T2/T3 다르게) ======
    private val _summaryText = MutableLiveData("")
    val summaryText: LiveData<String> = _summaryText

    private var firstDeviceTs: Long = -1L

    // ====== 우측 로그(표) ======
    private val _logRows = MutableLiveData<List<MeasurementLogRow>>(emptyList())
    val logRows: LiveData<List<MeasurementLogRow>> = _logRows

    // ====== 그래프 ======
    private val _chartEntries = MutableLiveData<List<Entry>>(emptyList())
    val chartEntries: LiveData<List<Entry>> = _chartEntries

    private val _chartYMin = MutableLiveData<Float?>(null)
    val chartYMin: LiveData<Float?> = _chartYMin

    private val _chartYMax = MutableLiveData<Float?>(null)
    val chartYMax: LiveData<Float?> = _chartYMax

    // ====== 오버레이 ======
    data class OverlayPoint(val x: Float, val y: Float)
    data class DirectionVec(val dx: Float, val dy: Float)

    private val _t2OverlayPoint = MutableLiveData<OverlayPoint?>()
    val t2OverlayPoint: LiveData<OverlayPoint?> = _t2OverlayPoint

    private val _t3OverlayPoint = MutableLiveData<OverlayPoint?>()
    val t3OverlayPoint: LiveData<OverlayPoint?> = _t3OverlayPoint

    private val _t3DirectionVec = MutableLiveData<DirectionVec?>()
    val t3DirectionVec: LiveData<DirectionVec?> = _t3DirectionVec

    // ====== 마지막 확정 상태 ======
    private var lastT2Index: Int = -1
    private var lastT3Index: Int = -1

    private var lastT1State: Int = 0
    private var lastT2Frac: Float = 0f
    private var lastT3Value: Float = 0f

    // ====== ✅ 튐 방지: "연속 N프레임" 안정화 (hold) ======
    private var t2CandidateIdx: Int = -1
    private var t2CandidateCount: Int = 0
    private val t2StableCountNeeded = 3

    private var t3CandidateIdx: Int = -1
    private var t3CandidateCount: Int = 0
    private val t3StableCountNeeded = 3

    // ====== ✅ 튐 방지: TOP 판정 히스테리시스 ======
    private var t2IsTopLatched: Boolean = false
    private var t3IsTopLatched: Boolean = false

    // T2: 5좌표 (Top / Left / Right / Bottom-Left / Bottom-Right)
    private val t2Points = listOf(
        OverlayPoint(0.50f, 0.30f), // TOP
        OverlayPoint(0.40f, 0.48f), // LEFT
        OverlayPoint(0.60f, 0.48f), // RIGHT
        OverlayPoint(0.30f, 0.72f), // BOTTOM-LEFT
        OverlayPoint(0.70f, 0.72f)  // BOTTOM-RIGHT
    )

    // T3: 위 3좌표만
    private val t3Top3Points = listOf(
        OverlayPoint(0.50f, 0.40f), // TOP
        OverlayPoint(0.40f, 0.48f), // LEFT
        OverlayPoint(0.60f, 0.48f)  // RIGHT
    )

    // ====== 내부 버퍼 ======
    @Volatile var latestDFrame: PeriK3JsonFrame? = null
    @Volatile private var latestSeq: Long = 0L
    private var lastAppliedSeq: Long = 0L

    private val logBuffer: ArrayDeque<MeasurementLogRow> = ArrayDeque()
    private val maxLogSize = 300

    private val chartBuffer: ArrayDeque<Entry> = ArrayDeque()
    private val maxChartPoints = 3000

    // T2: Force 정규화 기준
    private val t2ForceMaxN = 12.0

    // T3: LD bipolar 정규화 기준
    private val t3LdAbsMax = 5.0

    // 그래프 X축: 수신 시간 사용(단조 증가)
    private val useReceivedTimeForChartX: Boolean = true
    private var firstReceivedAtMs: Long = -1L

    // ====== T3 Peak (누적) + Rotation Presence ======
    private var peakPy: Double = Double.NEGATIVE_INFINITY
    private var peakLv: Double = Double.NEGATIVE_INFINITY
    private var lastRotationPositive: Boolean = false

    // ====== T2 P 프레임 저장 ======
    data class PFrame(
        val e: Double?,      // Elastic Energy (J)
        val fmax: Double?,   // Peak Response Slope (N/mm)
        val xmax: Double?,   // Maximum Disp (mm)
        val r2: Double?,     // Curve Fit Quality (R^2)
        val dur: Long?       // optional
    )
    @Volatile private var latestPFrame: PFrame? = null

    init {
        // 200ms 샘플링 루프 (D 프레임 적용)
        viewModelScope.launch {
            while (true) {
                val seq = latestSeq
                if (seq != 0L && seq != lastAppliedSeq) {
                    latestDFrame?.let { frame ->
                        applyDFrame(frame)
                        lastAppliedSeq = seq
                    }
                }
                delay(200L)
            }
        }

        // ✅ 최초 기본 위치를 "최상단 중앙"으로 한번 찍어줌
        //    (원하면 Fragment에서 setMarkerVisible(false)로 숨기고 시작해도 됨)
        _t2OverlayPoint.postValue(t2Points[0])
        _t3OverlayPoint.postValue(t3Top3Points[0])
        _t3DirectionVec.postValue(null)
    }

    fun onRawPacketReceived(raw: String, receivedAtMs: Long) {
        val text = raw.trim()
        if (text.isEmpty()) return

        try {
            val root = JSONObject(text)

            // BLE_RX 배치 포맷
            if (root.optString("type", "") == "BLE_RX") {
                val packets = root.optJSONArray("packets") ?: return
                var lastD: PeriK3JsonFrame? = null

                for (i in 0 until packets.length()) {
                    val p = packets.optString(i, "").trim()
                    if (!p.startsWith("{")) continue

                    val frame = PeriK3JsonFrame.parseOrNull(p, receivedAtMs) ?: continue
                    if (frame.t == "D") lastD = frame
                    if (frame.t == "P") {
                        parsePFrame(JSONObject(p))?.let { applyPFrame(it) }
                    }
                }

                if (lastD != null) {
                    latestDFrame = lastD
                    latestSeq = receivedAtMs
                }
                return
            }

            // RAW 단일 프레임: {"t":"D"} / {"t":"P"}
            val t = root.optString("t", "")
            when (t) {
                "D" -> {
                    val frame = PeriK3JsonFrame.parseOrNull(root.toString(), receivedAtMs) ?: return
                    latestDFrame = frame
                    latestSeq = receivedAtMs
                }
                "P" -> {
                    parsePFrame(root)?.let { applyPFrame(it) }
                }
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    // =========================
    // ✅ Save Result 시 "전체 리셋"
    // =========================
    fun resetAllAfterSaveResult() {
        // 버퍼/표/그래프
        logBuffer.clear()
        chartBuffer.clear()
        firstReceivedAtMs = -1L
        firstDeviceTs = -1L

        _logRows.postValue(emptyList())
        _chartEntries.postValue(emptyList())
        _chartYMin.postValue(null)
        _chartYMax.postValue(null)

        // 헤더 값(원하면 초기화)
        _t1IndicatorState.postValue(0)
        _t2FillFraction.postValue(0f)
        _t3BipolarValue.postValue(0f)
        _t3LvText.postValue("")

        // T3 peak + rotation
        peakPy = Double.NEGATIVE_INFINITY
        peakLv = Double.NEGATIVE_INFINITY
        lastRotationPositive = false

        // T2 P 프레임
        latestPFrame = null

        // 오버레이 안정화 상태도 리셋
        lastT2Index = -1
        lastT3Index = -1
        t2CandidateIdx = -1
        t2CandidateCount = 0
        t3CandidateIdx = -1
        t3CandidateCount = 0
        t2IsTopLatched = false
        t3IsTopLatched = false

        // ✅ 기본 위치: 최상단 중앙으로
        _t2OverlayPoint.postValue(t2Points[0])
        _t3OverlayPoint.postValue(t3Top3Points[0])
        _t3DirectionVec.postValue(null)

        // 요약 텍스트도 비움 (현재 탭이 T2/T3여도 비어짐)
        _summaryText.postValue("")
    }

    // =========================
    // D 프레임 적용
    // =========================
    private fun applyDFrame(frame: PeriK3JsonFrame) {
        // ts 없으면 스킵
        frame.ts ?: return

        // 헤더 갱신
        updateT1Indicator(frame)
        updateT2SixCell(frame)
        updateT3Bipolar(frame)
        updateT3LvText(frame)

        // 오버레이(튐 방지 포함)
        updateOverlayT2(frame)
        updateOverlayT3(frame)

        // 로그/그래프: "현재 탭 기준"
        val tab = _selectedTab.value ?: 0
        appendLogRow(frame, tab)
        appendChart(frame, tab)

        // ✅ T3 요약 값은 "T3 탭일 때만 누적" (혼입 방지)
        if (tab == 2) {
            updateT3PeaksAndRotation(frame)
            updateSummaryText()
        }
    }

    // =========================
    // P 프레임 적용 (즉시 반영)
    // =========================
    private fun parsePFrame(root: JSONObject): PFrame? {
        if (root.optString("t", "") != "P") return null

        fun optDoubleOrNull(key: String): Double? =
            if (root.has(key)) root.optDouble(key, Double.NaN).takeIf { it.isFinite() } else null

        fun optLongOrNull(key: String): Long? =
            if (root.has(key)) root.optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } else null

        return PFrame(
            e = optDoubleOrNull("e"),
            fmax = optDoubleOrNull("fmax"),
            xmax = optDoubleOrNull("xmax"),
            r2 = optDoubleOrNull("r2"),
            dur = optLongOrNull("dur")
        )
    }

    private fun applyPFrame(p: PFrame) {
        latestPFrame = p
        // 탭이 T2일 때는 즉시 보이게
        if ((_selectedTab.value ?: 0) == 1) {
            updateSummaryText()
        }
    }

    // =========================
    // 요약 텍스트 (T1 숨김 / T2 P / T3 Peak)
    // =========================
    private fun updateSummaryText() {
        when (_selectedTab.value ?: 0) {
            0 -> _summaryText.postValue("") // T1 숨김

            1 -> { // T2
                val p = latestPFrame
                if (p == null) {
                    _summaryText.postValue("")
                    return
                }

                val s = listOf(
                    "Peak Response Slope : ${p.fmax?.let { "%.2f".format(it) } ?: "-"} N/mm",
                    "Elastic Energy : ${p.e?.let { "%.6f".format(it) } ?: "-"} J",
                    "Curve Fit Quality : R² = ${p.r2?.let { "%.3f".format(it) } ?: "-"}",
                    "Maximum Disp : ${p.xmax?.let { "%.3f".format(it) } ?: "-"} mm"
                ).joinToString("\n")

                _summaryText.postValue(s)
            }

            2 -> { // T3
                if (!peakPy.isFinite() && !peakLv.isFinite()) {
                    _summaryText.postValue("")
                    return
                }

                val lift = if (peakPy.isFinite()) "%.2f".format(peakPy) else "-"
                val vel = if (peakLv.isFinite()) "%.2f".format(peakLv) else "-"
                val presence = if (lastRotationPositive) "Present" else "Absent"

                val s = listOf(
                    "Peak lift : $lift mm",
                    "Peak movement velocity : $vel mm/s",
                    "Rotation Presence : $presence"
                ).joinToString("\n")

                _summaryText.postValue(s)
            }
        }
    }

    private fun updateT3PeaksAndRotation(frame: PeriK3JsonFrame) {
        frame.py?.let { py ->
            if (py.isFinite()) peakPy = max(peakPy, py)
        }
        frame.LV?.let { lv ->
            if (lv.isFinite()) peakLv = max(peakLv, lv)
        }
        frame.r?.let { r ->
            lastRotationPositive = r > 0.0
        }
    }

    // =========================
    // 로그/그래프
    // =========================
    private fun appendLogRow(frame: PeriK3JsonFrame, tab: Int) {
        val rawTs = frame.ts ?: return
        val ts = formatElapsedTs(rawTs)

        val row = if (tab == 2) {
            MeasurementLogRow(
                c1 = ts,
                c2 = fmt(frame.px),
                c3 = fmt(frame.py),
                c4 = fmt(frame.LD),
                c5 = fmt(frame.r)
            )
        } else {
            MeasurementLogRow(
                c1 = ts,
                c2 = frame.stateLabel(),
                c3 = fmt(frame.F),
                c4 = fmt(frame.x),
                c5 = fmt(frame.r)
            )
        }

        logBuffer.addFirst(row)
        while (logBuffer.size > maxLogSize) logBuffer.removeLast()
        _logRows.postValue(logBuffer.toList())
    }

    //  그래프: T2=Force(F), T3=ventral displacement(py)
    private fun appendChart(frame: PeriK3JsonFrame, tab: Int) {
        val y = if (tab == 2) frame.py?.toFloat() else frame.F?.toFloat()
        if (y == null || y.isNaN() || y.isInfinite()) return

        val x = if (useReceivedTimeForChartX) {
            val recv = frame.receivedAtMs ?: return
            if (firstReceivedAtMs < 0L) firstReceivedAtMs = recv
            ((recv - firstReceivedAtMs).toFloat() / 1000f)
        } else {
            ((frame.ts ?: return).toFloat() / 1000f)
        }
        if (x.isNaN() || x.isInfinite()) return

        val lastX = chartBuffer.lastOrNull()?.x
        val safeX = if (lastX != null && x <= lastX) lastX + 0.001f else x

        chartBuffer.addLast(Entry(safeX, y))
        while (chartBuffer.size > maxChartPoints) chartBuffer.removeFirst()

        val list = chartBuffer.toList()
        _chartEntries.postValue(list)

        if (tab == 2 && list.isNotEmpty()) {
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (e in list) {
                minY = min(minY, e.y)
                maxY = max(maxY, e.y)
            }
            minY = min(minY, 0f)
            maxY = max(maxY, 0f)

            val range = max(1e-6f, maxY - minY)
            val pad = range * 0.25f
            _chartYMin.postValue(minY - pad)
            _chartYMax.postValue(maxY + pad)
        } else {
            _chartYMin.postValue(null)
            _chartYMax.postValue(null)
        }
    }

    private fun fmt(v: Double?): String {
        if (v == null) return "?"
        return String.format("%.2f", v)
    }

    private fun formatElapsedTs(ts: Long): String {
        if (firstDeviceTs < 0L) firstDeviceTs = ts
        val delta = (ts - firstDeviceTs).coerceAtLeast(0L)

        val minutes = delta / 60_000
        val seconds = (delta % 60_000) / 1_000
        val millis = delta % 1_000

        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }

    // =========================
    // 오버레이 튐 방지
    // =========================
    private fun decideT2IsTop(px: Double, py: Double, ld: Double): Boolean {
        fun isPos(v: Double) = v > 0.0
        fun isNegOrZero(v: Double) = v <= 0.0

        // px 보정 범위 (-0.5 ~ 0.5 근처를 TOP으로)
        val enterPx = 0.50
        val exitPx = 0.70

        val baseOk = isNegOrZero(py) && isPos(ld)
        if (!baseOk) {
            t2IsTopLatched = false
            return false
        }

        val pxAbs = kotlin.math.abs(px)
        t2IsTopLatched =
            if (t2IsTopLatched) pxAbs <= exitPx
            else pxAbs <= enterPx

        return t2IsTopLatched
    }

    private fun decideT3IsTop(px: Double, py: Double, ld: Double): Boolean {
        fun isPos(v: Double) = v > 0.0
        fun isNegOrZero(v: Double) = v <= 0.0

        val enterPx = 0.45
        val exitPx = 0.60

        val baseOk = isNegOrZero(py) && isPos(ld)
        if (!baseOk) {
            t3IsTopLatched = false
            return false
        }

        val pxAbs = kotlin.math.abs(px)
        t3IsTopLatched =
            if (t3IsTopLatched) pxAbs <= exitPx
            else pxAbs <= enterPx

        return t3IsTopLatched
    }

    private fun updateOverlayT2(frame: PeriK3JsonFrame) {
        val px = frame.px ?: return
        val py = frame.py ?: return
        val ld = frame.LD ?: return

        val eps = 1e-6
        val isZeroTriple =
            kotlin.math.abs(px) <= eps &&
                    kotlin.math.abs(py) <= eps &&
                    kotlin.math.abs(ld) <= eps

        val idx = when {
            //  0,0,0 → 최상단 중앙
            isZeroTriple -> 0

            //  TOP 판정
            decideT2IsTop(px, py, ld) -> 0

            //  TOP이 아닐 때: py 기준으로 하단 / 최하단 분리
            py <= 0.0 && px <= 0.0 -> 1   // 하단 왼쪽
            py <= 0.0 && px > 0.0  -> 2   // 하단 오른쪽
            py > 0.0  && px <= 0.0 -> 3   // 최하단 왼쪽
            else                   -> 4  // 최하단 오른쪽
        }

        //  0,0,0은 즉시 반영
        if (isZeroTriple) {
            t2CandidateIdx = idx
            t2CandidateCount = t2StableCountNeeded
            if (idx != lastT2Index) {
                lastT2Index = idx
                _t2OverlayPoint.postValue(t2Points[idx])
            }
            return
        }

        //  hold 로직
        if (idx != t2CandidateIdx) {
            t2CandidateIdx = idx
            t2CandidateCount = 1
            return
        } else {
            t2CandidateCount++
        }

        if (t2CandidateCount < t2StableCountNeeded) return
        if (idx == lastT2Index) return

        lastT2Index = idx
        _t2OverlayPoint.postValue(t2Points[idx])
    }

    private fun updateOverlayT3(frame: PeriK3JsonFrame) {
        val px = frame.px ?: return
        val py = frame.py ?: return
        val ld = frame.LD ?: return

        val eps = 1e-6
        val isZeroTriple =
            kotlin.math.abs(px) <= eps &&
                    kotlin.math.abs(py) <= eps &&
                    kotlin.math.abs(ld) <= eps

        val idx = when {
            //  0,0,0 → 최상단 중앙
            isZeroTriple -> 0

            //  TOP
            decideT3IsTop(px, py, ld) -> 0

            //  TOP 아님 → px 부호만 사용
            px <= 0.0 -> 1   // 왼쪽 하단
            else      -> 2   // 오른쪽 하단
        }

        if (isZeroTriple) {
            t3CandidateIdx = idx
            t3CandidateCount = t3StableCountNeeded
            if (idx != lastT3Index) {
                lastT3Index = idx
                _t3OverlayPoint.postValue(t3Top3Points[idx])
            }
            _t3DirectionVec.postValue(null)
            return
        }

        if (idx != t3CandidateIdx) {
            t3CandidateIdx = idx
            t3CandidateCount = 1
            frame.r?.let { updateDirectionByR(it) }
            return
        } else {
            t3CandidateCount++
        }

        if (t3CandidateCount >= t3StableCountNeeded && idx != lastT3Index) {
            lastT3Index = idx
            _t3OverlayPoint.postValue(t3Top3Points[idx])
        }

        frame.r?.let { updateDirectionByR(it) }
    }

    private fun updateDirectionByR(rDeg: Double) {
        val rad = Math.toRadians(rDeg)
        val dx = kotlin.math.sin(rad).toFloat()
        val dy = (-kotlin.math.cos(rad)).toFloat()
        _t3DirectionVec.postValue(DirectionVec(dx, dy))
    }

    // =========================
    // 헤더 값 업데이트
    // =========================
    private fun updateT1Indicator(frame: PeriK3JsonFrame) {
        val tf = frame.TF
        val s = frame.s

        val newState = when {
            tf == 1 -> 2
            s == null -> return
            s !in 0..2 -> return
            else -> s
        }

        if (newState != lastT1State) {
            lastT1State = newState
            _t1IndicatorState.postValue(newState)
        }
    }

    private fun updateT2SixCell(frame: PeriK3JsonFrame) {
        val f = frame.F ?: return
        if (!f.isFinite()) return

        val frac = (f / t2ForceMaxN).toFloat().coerceIn(0f, 1f)
        if (frac != lastT2Frac) {
            lastT2Frac = frac
            _t2FillFraction.postValue(frac)
        }
    }

    private fun updateT3Bipolar(frame: PeriK3JsonFrame) {
        val ld = frame.LD ?: return
        if (!ld.isFinite()) return

        val v = (ld / t3LdAbsMax).toFloat().coerceIn(-1f, 1f)
        if (v != lastT3Value) {
            lastT3Value = v
            _t3BipolarValue.postValue(v)
        }
    }

    private fun updateT3LvText(frame: PeriK3JsonFrame) {
        val lv = frame.LV ?: return
        if (!lv.isFinite()) return
        _t3LvText.postValue(String.format("%.1f mm/s", lv))
    }

    fun buildSnapshotJsonString(): String {
        val root = JSONObject()
        root.put("savedAtMs", System.currentTimeMillis())
        root.put("selectedTab", selectedTab.value ?: 0)

        val frame = latestDFrame
        root.put("latestFrameRaw", frame?.rawJson ?: JSONObject.NULL)

        val rows = JSONArray()
        for (r in logBuffer) {
            val o = JSONObject()
            o.put("c1", r.c1)
            o.put("c2", r.c2)
            o.put("c3", r.c3)
            o.put("c4", r.c4)
            o.put("c5", r.c5)
            rows.put(o)
        }
        root.put("logRows", rows)

        val points = JSONArray()
        val takeN = 60
        val tail = chartBuffer.takeLast(takeN)
        for (e in tail) {
            val p = JSONObject()
            p.put("x", e.x)
            p.put("y", e.y)
            points.put(p)
        }
        root.put("chartTail", points)

        return root.toString()
    }

    // MeasurementViewModel 안에 추가
    fun resetAllResults() {
        // 로그/그래프 버퍼
        logBuffer.clear()
        chartBuffer.clear()
        firstReceivedAtMs = -1L
        firstDeviceTs = -1L

        _logRows.postValue(emptyList())
        _chartEntries.postValue(emptyList())
        _chartYMin.postValue(null)
        _chartYMax.postValue(null)

        // T3 peak 리셋
        peakPy = Double.NEGATIVE_INFINITY
        peakLv = Double.NEGATIVE_INFINITY

        // T2 P 프레임 결과 리셋
        latestPFrame = null


        _summaryText.postValue("")
        // 공통 summary 텍스트(네 Fragment에서 peakVelocityText에 꽂는 영역)도 비우기용으로 쓰고 있다면
        // (네 코드 구조상 peakVelocityText는 Fragment에서 만들어서 넣으니, LiveData 기반이면 여기서 같이 비우는 게 깔끔)
        // 만약 summaryText를 쓰고 있다면 같이:
        // _summaryText.postValue("")
    }
}