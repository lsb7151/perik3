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
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class MeasurementViewModel : ViewModel() {

    // 0=T1, 1=T2, 2=T3
    private val _selectedTab = MutableLiveData(0)
    val selectedTab: LiveData<Int> = _selectedTab

    fun setSelectedTab(index: Int) {
        if (_selectedTab.value == index) return
        _selectedTab.value = index
        clearBuffersForTabChange()
    }

    // ====== 좌측 헤더용 값들 ======
    private val _t1IndicatorState = MutableLiveData(0) // 0/1/2
    val t1IndicatorState: LiveData<Int> = _t1IndicatorState

    private val _t2FillFraction = MutableLiveData(0f)  // 0..1
    val t2FillFraction: LiveData<Float> = _t2FillFraction

    private val _t3BipolarValue = MutableLiveData(0f)  // -1..1
    val t3BipolarValue: LiveData<Float> = _t3BipolarValue

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

    // ====== 내부 버퍼 ======
    @Volatile private var latestDFrame: PeriK3JsonFrame? = null
    @Volatile private var latestSeq: Long = 0L
    private var lastAppliedSeq: Long = 0L

    private val logBuffer: ArrayDeque<MeasurementLogRow> = ArrayDeque()
    private val maxLogSize = 300

    private val chartBuffer: ArrayDeque<Entry> = ArrayDeque()
    private val maxChartPoints = 120

    // T2: Force 정규화 기준 (필요하면 튜닝)
    private val t2ForceMaxN = 12.0

    // T3: LD bipolar 정규화 기준 (필요하면 튜닝)
    private val t3LdAbsMax = 3.0

    // ✅ 그래프 X축: 장비 ts 대신 "수신 시간"을 기본으로 사용 (단조 증가 보장)
    //   - 장비 ts를 꼭 쓰고 싶으면 false로 바꿔도 되는데, ts가 리셋되면 그래프가 깨질 수 있음
    private val useReceivedTimeForChartX: Boolean = true
    private var firstReceivedAtMs: Long = -1L

    init {
        // ✅ 200ms 샘플링 루프 (UI/로그/그래프도 200ms에 한 번만 갱신)
        viewModelScope.launch {
            while (true) {
                val seq = latestSeq
                if (seq != 0L && seq != lastAppliedSeq) {
                    val frame = latestDFrame
                    if (frame != null) {
                        applyFrame(frame)
                        lastAppliedSeq = seq
                    }
                }
                delay(200L)
            }
        }
    }

    /**
     * Fragment에서 "수신된 JSON 문자열"을 넣어줌
     * - receivedAtMs: System.currentTimeMillis() 로 넣어줘
     */
    fun onRawPacketReceived(raw: String, receivedAtMs: Long) {
        try {
            val root = JSONObject(raw)
            val type = root.optString("type", "")
            if (type != "BLE_RX") return

            val packets = root.optJSONArray("packets") ?: return

            // packets 중 마지막 D 프레임을 latest로 사용
            var lastD: PeriK3JsonFrame? = null
            for (i in 0 until packets.length()) {
                val p = packets.optString(i, "")
                if (!p.trim().startsWith("{")) continue

                val frame = PeriK3JsonFrame.parseOrNull(p, receivedAtMs) ?: continue
                if (frame.t == "D") lastD = frame
            }

            if (lastD != null) {
                latestDFrame = lastD
            }
        } catch (_: Exception) {
            // batch json이 아닌 경우는 무시
        }
    }
    private fun applyFrame(frame: PeriK3JsonFrame) {
        val tab = _selectedTab.value ?: 0

        // ===== 1) 헤더 UI용 값 계산 =====
        when (tab) {
            0 -> { // T1: IDLE/STABLE/TREMOR 만 3칸
                val state012 = when (frame.s ?: 0) {
                    0 -> 0 // IDLE
                    1 -> 1 // STABLE
                    2 -> 2 // TREMOR
                    else -> 1 // PRESS류는 일단 STABLE로 처리
                }
                _t1IndicatorState.postValue(state012)
            }

            1 -> { // T2: 6셀 fill = Force 기반 0..1
                val f = frame.F ?: 0.0
                val frac = (f / t2ForceMaxN).toFloat().coerceIn(0f, 1f)
                _t2FillFraction.postValue(frac)
            }

            2 -> { // T3: bipolar = LD 기반 -1..1
                val ld = frame.LD ?: 0.0
                val v = (ld / t3LdAbsMax).toFloat().coerceIn(-1f, 1f)
                _t3BipolarValue.postValue(v)
            }
        }

        // ===== 2) 로그 표(row) 누적 =====
        appendLogRowAndMaybeEvent(frame, tab)

        // ===== 3) 그래프 엔트리 =====
        appendChart(frame, tab)
    }

    private fun appendLogRowAndMaybeEvent(frame: PeriK3JsonFrame, tab: Int) {
        val ts = (frame.ts ?: 0L).toString()

        val normalRow = if (tab == 2) {
            // T3: timestamp, x,y,z,rotation (ts, px, py, LV, r)
            MeasurementLogRow(
                c1 = ts,
                c2 = "px=${fmt(frame.px)}",
                c3 = "py=${fmt(frame.py)}",
                c4 = "LV=${fmt(frame.LV)}",
                c5 = "r=${fmt(frame.r)}"
            )
        } else {
            // T1/T2: timestamp, state, force, disp, rotation (ts, s, F, x, r)
            MeasurementLogRow(
                c1 = ts,
                c2 = frame.stateLabel(),
                c3 = "F=${fmt(frame.F)}",
                c4 = "x=${fmt(frame.x)}",
                c5 = "r=${fmt(frame.r)}"
            )
        }

        // 최신이 위로 오게 addFirst
        logBuffer.addFirst(normalRow)

        // EVENT row는 "조건 만족 시" 위에 추가로 1줄 더
        if (frame.isEvent()) {
            val eventRow = MeasurementLogRow(
                c1 = ts,
                c2 = "EVENT",
                c3 = "px=${fmt(frame.px)}",
                c4 = "py=${fmt(frame.py)}",
                c5 = "LD=${fmt(frame.LD)}"
            )
            logBuffer.addFirst(eventRow)
        }

        while (logBuffer.size > maxLogSize) logBuffer.removeLast()
        _logRows.postValue(logBuffer.toList())
    }

    private fun appendChart(frame: PeriK3JsonFrame, tab: Int) {
        val y = if (tab == 2) {
            (frame.LD ?: 0.0).toFloat()
        } else {
            (frame.F ?: 0.0).toFloat()
        }

        val x = if (useReceivedTimeForChartX) {
            val recv = frame.receivedAtMs ?: System.currentTimeMillis()
            if (firstReceivedAtMs < 0L) firstReceivedAtMs = recv
            ((recv - firstReceivedAtMs).toFloat() / 1000f) // 0부터 시작하는 seconds
        } else {
            ((frame.ts ?: 0L).toFloat() / 1000f)
        }

        // ✅ X가 뒤로 가면 차트가 터질 수 있음 → 안전장치로 단조 증가 보정
        val lastX = chartBuffer.lastOrNull()?.x
        val safeX = if (lastX != null && x <= lastX) lastX + 0.001f else x

        chartBuffer.addLast(Entry(safeX, y))
        while (chartBuffer.size > maxChartPoints) chartBuffer.removeFirst()

        val list = chartBuffer.toList()
        _chartEntries.postValue(list)

        // ✅ T3: 음수 포함 → 0이 중간쯤 오도록 min/max 잡기
        if (tab == 2 && list.isNotEmpty()) {
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (e in list) {
                minY = min(minY, e.y)
                maxY = max(maxY, e.y)
            }

            // 0 포함
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

    private fun clearBuffersForTabChange() {
        // 탭 바뀌면 버퍼 초기화(안 하면 축/의미 섞임)
        logBuffer.clear()
        chartBuffer.clear()
        firstReceivedAtMs = -1L

        _logRows.postValue(emptyList())
        _chartEntries.postValue(emptyList())
        _chartYMin.postValue(null)
        _chartYMax.postValue(null)

        // 헤더 값도 초기화(원하면 유지해도 됨)
        _t1IndicatorState.postValue(0)
        _t2FillFraction.postValue(0f)
        _t3BipolarValue.postValue(0f)
    }

    private fun fmt(v: Double?): String {
        if (v == null) return "?"
        return String.format("%.2f", v)
    }
}