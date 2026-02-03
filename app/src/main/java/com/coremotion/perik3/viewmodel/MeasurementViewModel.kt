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
        val text = raw.trim()
        if (text.isEmpty()) return

        try {
            val root = JSONObject(text)

            // 1) (기존) BLE_RX 배치 포맷 지원
            if (root.optString("type", "") == "BLE_RX") {
                val packets = root.optJSONArray("packets") ?: return

                var lastD: PeriK3JsonFrame? = null
                for (i in 0 until packets.length()) {
                    val p = packets.optString(i, "").trim()
                    if (!p.startsWith("{")) continue

                    val frame = PeriK3JsonFrame.parseOrNull(p, receivedAtMs) ?: continue
                    if (frame.t == "D") lastD = frame
                }

                if (lastD != null) {
                    latestDFrame = lastD
                    latestSeq = receivedAtMs  // ✅ 중요: seq 갱신
                }
                return
            }

            // 2) (추가) RAW 단일 프레임 지원: {"t":"D", ...}
            val t = root.optString("t", "")
            if (t.isNotEmpty()) {
                val frame = PeriK3JsonFrame.parseOrNull(root.toString(), receivedAtMs) ?: return
                if (frame.t == "D") {
                    latestDFrame = frame
                    latestSeq = receivedAtMs  // ✅ 중요: seq 갱신
                }
            }
        } catch (_: Exception) {
            // JSON 파싱 실패는 무시 (펌웨어 깨진 데이터/중간 조각 등)
        }
    }
    private fun applyFrame(frame: PeriK3JsonFrame) {
        val tab = _selectedTab.value ?: 0

        // ✅ 공통: receivedAt/ts가 없으면 스킵
        if (frame.ts == null) return

        // ✅ 탭별 필수 값 없으면 스킵 (그래프/표/애니메이터 안전)
        when (tab) {
            0, 1 -> {
                // Force 기반 그래프/표
                val f = frame.F
                val x = frame.x
                val r = frame.r
                if (f == null || x == null || r == null) return
                if (!f.isFinite() || !x.isFinite() || !r.isFinite()) return
            }
            2 -> {
                // LD 기반 그래프 + px/py 방향 표시(쓸 경우)
                val ld = frame.LD
                if (ld == null || !ld.isFinite()) return

                // px/py를 UI에 쓰는 경우만 체크
                val px = frame.px
                val py = frame.py
                if (px != null && !px.isFinite()) return
                if (py != null && !py.isFinite()) return
            }
        }

        // ===== 2) 로그 표(row) 누적 =====
        appendLogRowAndMaybeEvent(frame, tab)

        // ===== 3) 그래프 엔트리 =====
        appendChart(frame, tab)
    }

    private fun appendLogRowAndMaybeEvent(frame: PeriK3JsonFrame, tab: Int) {
        val rawTs = frame.ts ?: return
        val ts = formatElapsedTs(rawTs)

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
        val y = if (tab == 2) frame.LD?.toFloat() else frame.F?.toFloat()
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
        firstDeviceTs = -1L

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

    private fun formatElapsedTs(ts: Long): String {
        if (firstDeviceTs < 0L) firstDeviceTs = ts
        val delta = (ts - firstDeviceTs).coerceAtLeast(0L)

        val minutes = delta / 60_000
        val seconds = (delta % 60_000) / 1_000
        val millis  = delta % 1_000

        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }
}