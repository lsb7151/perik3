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

    data class OverlayPoint(val x: Float, val y: Float)
    data class DirectionVec(val dx: Float, val dy: Float)

    private val _t2OverlayPoint = MutableLiveData<OverlayPoint?>()
    val t2OverlayPoint: LiveData<OverlayPoint?> = _t2OverlayPoint

    private val _t3OverlayPoint = MutableLiveData<OverlayPoint?>()
    val t3OverlayPoint: LiveData<OverlayPoint?> = _t3OverlayPoint

    private val _t3DirectionVec = MutableLiveData<DirectionVec?>()
    val t3DirectionVec: LiveData<DirectionVec?> = _t3DirectionVec

    private var lastT2Index: Int = -1
    private var lastT3Index: Int = -1

    private var lastT2Point: OverlayPoint? = null
    private var lastT3Point: OverlayPoint? = null

    private var lastT3Dir: DirectionVec? = null

    private var lastT1State: Int = 0
    private var lastT2Frac: Float = 0f
    private var lastT3Value: Float = 0f

    // T2: 5좌표 (Top / Left / Center / Right / Bottom)
    private val t2Points = listOf(
        OverlayPoint(0.50f, 0.30f), // TOP
        OverlayPoint(0.40f, 0.48f), // LEFT ( -,- )
        OverlayPoint(0.60f, 0.48f), // RIGHT ( +,- )
        OverlayPoint(0.30f, 0.72f), // BOTTOM-LEFT ( -,+ )
        OverlayPoint(0.70f, 0.72f) // BOTTOM-RIGHT ( +,+ )
    )

    // T3: "위에서부터 3좌표만"
    private val t3Top3Points = listOf(
        OverlayPoint(0.50f, 0.40f), // TOP
        OverlayPoint(0.40f, 0.48f), // LEFT
        OverlayPoint(0.60f, 0.48f)  // RIGHT
    )

    // ====== 내부 버퍼 ======
    @Volatile
    var latestDFrame: PeriK3JsonFrame? = null
    @Volatile private var latestSeq: Long = 0L
    private var lastAppliedSeq: Long = 0L

    private val logBuffer: ArrayDeque<MeasurementLogRow> = ArrayDeque()
    private val maxLogSize = 300

    private val chartBuffer: ArrayDeque<Entry> = ArrayDeque()
    private val maxChartPoints = 3000

    // T2: Force 정규화 기준 (필요하면 튜닝)
    private val t2ForceMaxN = 12.0

    // T3: LD bipolar 정규화 기준 (필요하면 튜닝)
    private val t3LvAbsMax = 5.0

    //  그래프 X축: 장비 ts 대신 "수신 시간"을 기본으로 사용 (단조 증가 보장)
    //   - 장비 ts를 꼭 쓰고 싶으면 false로 바꿔도 되는데, ts가 리셋되면 그래프가 깨질 수 있음
    private val useReceivedTimeForChartX: Boolean = true
    private var firstReceivedAtMs: Long = -1L

    init {
        //  200ms 샘플링 루프 (UI/로그/그래프도 200ms에 한 번만 갱신)
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
                    latestSeq = receivedAtMs  // 중요: seq 갱신
                }
            }
        } catch (_: Exception) {
            // JSON 파싱 실패는 무시 (펌웨어 깨진 데이터/중간 조각 등)
        }
    }
    private fun applyFrame(frame: PeriK3JsonFrame) {
        val tab = _selectedTab.value ?: 0

        //  공통: ts 없으면 스킵 (너 요구사항 그대로)
        val ts = frame.ts ?: return

        // =========================================================
        // 1) 헤더 UI 값들은 "탭과 무관하게 항상" 최신값으로 갱신
        //    단, update 함수 내부에서 "유효할 때만 post" 하도록!
        // =========================================================
        updateT1Indicator(frame)   // TF=1 처리 포함 (빈값 무시)
        updateT2SixCell(frame)     // F 기반 (null/NaN 무시)
        updateT3Bipolar(frame)     // LD 기반 (null/NaN 무시)

        // Overlay도 "항상 계산"은 OK.
        // 다만 updateOverlay 내부에서 null/무효면 "아무것도 post하지 않음" (마지막 유지)
        updateOverlayT2(frame)
        updateOverlayT3(frame)

        // =========================================================
        // 2) 로그/그래프는 "현재 탭" 기준으로만 쌓기
        //    (이 부분에서도 무효면 스킵하는 guard는 append 내부에서 처리 권장)
        // =========================================================
        appendLogRow(frame, tab)   //  EVENT 제거했으면 여기서 절대 추가하지 않게
        appendChart(frame, tab)
    }

    private fun appendLogRow(frame: PeriK3JsonFrame, tab: Int) {
        val rawTs = frame.ts ?: return
        val ts = formatElapsedTs(rawTs)

        val normalRow = if (tab == 2) {
            // T3: timestamp, x,y,z,rotation (ts, px, py, LV, r)
            MeasurementLogRow(
                c1 = ts,
                c2 = fmt(frame.px),
                c3 = fmt(frame.py),
                c4 = fmt(frame.LD),
                c5 = fmt(frame.r)
            )
        } else {
            // T1/T2: timestamp, state, force, disp, rotation (ts, s, F, x, r)
            MeasurementLogRow(
                c1 = ts,
                c2 = frame.stateLabel(),
                c3 = fmt(frame.F),
                c4 = fmt(frame.x),
                c5 = fmt(frame.r)
            )
        }

        // 최신이 위로 오게 addFirst
        logBuffer.addFirst(normalRow)

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

        //  T3: 음수 포함 → 0이 중간쯤 오도록 min/max 잡기
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


    private fun updateOverlayT2(frame: PeriK3JsonFrame) {
        val px = frame.px ?: return
        val py = frame.py ?: return
        val ld = frame.LD ?: return   // z = LD

        // 부호 규칙: '-'는 0이하, '+'는 0보다 큼
        fun isPos(v: Double) = v > 0.0
        fun isNegOrZero(v: Double) = v < 0.0

        //  TOP 조건: x가 -1~+1, y는 '-', z는 '+'
        //   (x 범위는 스펙 그대로 1.0 사용)
        val isTop = (kotlin.math.abs(px) <= 1.0) && isNegOrZero(py) && isPos(ld)

        val idx = if (isTop) {
            0 // TOP
        } else {
            // 나머지는 z가 '-'(0이하)일 때만 유효하게 찍는다.
            // z가 '+'인데 TOP 조건을 못 맞춘 경우(예: x가 너무 큼)는 "유지" 처리
          //   if (isPos(ld)) return

            when {
                isNegOrZero(px) && isNegOrZero(py) -> 1 // (-, -, -)
                isPos(px)       && isNegOrZero(py) -> 2 // (+, -, -)
                isNegOrZero(px) && isPos(py)        -> 3 // (-, +, -)
                else                               -> 4 // (+, +, -)
            }
        }

        //  같은 위치면 업데이트 생략(깜빡임 방지)
        if (idx == lastT2Index) return

        lastT2Index = idx
        val p = t2Points[idx]
        lastT2Point = p
        _t2OverlayPoint.postValue(p)
    }

    private fun updateOverlayT3(frame: PeriK3JsonFrame) {
        val px = frame.px ?: return
        val py = frame.py ?: return
        val ld = frame.LD ?: return

        fun isPos(v: Double) = v > 0.0
        fun isNegOrZero(v: Double) = v <= 0.0

        val isTop = (kotlin.math.abs(px) <= 0.5) && isNegOrZero(py) && isPos(ld)

        val idx = if (isTop) {
            0 // TOP
        } else {
       //     if (isPos(ld)) return         // TOP 아닌데 z가 +면 유지
        //    if (!isPos(py)) return        // 아래 라인(y=+)만 사용
            if (isNegOrZero(px)) 1 else 2 // LEFT/RIGHT
        }

        //  위치는 유지/업데이트
        if (idx != lastT3Index) {
            lastT3Index = idx
            val p = t3Top3Points[idx]
            lastT3Point = p
            _t3OverlayPoint.postValue(p)
        }

        // 화살표는 r 들어오면 갱신 (null이면 유지)
        frame.r?.let { updateDirectionByR(it) }
    }

    private fun updateDirectionByR(rDeg: Double) {
        val rad = Math.toRadians(rDeg)

        //  0° = 12시(위), 90° = 3시(오른쪽), 180° = 6시(아래), -90° = 9시(왼쪽)
        val dx = kotlin.math.sin(rad).toFloat()
        val dy = (-kotlin.math.cos(rad)).toFloat()

        val v = DirectionVec(dx, dy)

        lastT3Dir = v
        _t3DirectionVec.postValue(v)
    }

    private fun updateT1Indicator(frame: PeriK3JsonFrame) {
        val tf = frame.TF
        val s = frame.s

        val newState = when {
            tf == 1 -> 2 // TREMOR 강제
            s == null -> return              //  빈값 → 무시
            s !in 0..2 -> return             //  범위 밖 → 무시
            else -> s
        }

        if (newState != lastT1State) {
            lastT1State = newState
            _t1IndicatorState.postValue(newState)
        }
    }

    private fun updateT2SixCell(frame: PeriK3JsonFrame) {
        val f = frame.F ?: return           //  null 무시
        if (!f.isFinite()) return           //  NaN/Inf 무시

        val frac = (f / t2ForceMaxN)
            .toFloat()
            .coerceIn(0f, 1f)

        if (frac != lastT2Frac) {
            lastT2Frac = frac
            _t2FillFraction.postValue(frac)
        }
    }

    private fun updateT3Bipolar(frame: PeriK3JsonFrame) {
        val lv = frame.LV ?: return
        if (!lv.isFinite()) return

        val v = (lv / t3LvAbsMax)
            .toFloat()
            .coerceIn(-1f, 1f)

        if (v != lastT3Value) {
            lastT3Value = v
            _t3BipolarValue.postValue(v)
        }
    }


    fun buildSnapshotJsonString(): String {
        val root = JSONObject()

        root.put("savedAtMs", System.currentTimeMillis())
        root.put("selectedTab", selectedTab.value ?: 0)

        // 최신 프레임
        val frame = latestDFrame
        root.put("latestFrameRaw", frame?.rawJson ?: JSONObject.NULL) //  PeriK3JsonFrame에 rawJson 필드가 없으면 아래 주석 참고

        // 로그 테이블(현재)
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

        // 그래프 (너무 크면 마지막 N개만)
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
}