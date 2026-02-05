package com.coremotion.perik3.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.coremotion.perik3.adapter.MeasurementLogAdapter
import com.coremotion.perik3.ble.BleJsonClient
import com.coremotion.perik3.databinding.FragmentMeasurementBinding
import com.coremotion.perik3.viewmodel.MeasurementViewModel
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.tabs.TabLayout

class MeasurementFragment : Fragment() {

    private var fragmentMeasurementBinding: FragmentMeasurementBinding? = null
    private val binding: FragmentMeasurementBinding get() = requireNotNull(fragmentMeasurementBinding)

    private val viewModel: MeasurementViewModel by viewModels()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleJsonClient: BleJsonClient? = null

    private lateinit var logAdapter: MeasurementLogAdapter



    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            if (!granted) {
                Toast.makeText(requireContext(), "BLE 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        fragmentMeasurementBinding = FragmentMeasurementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bluetoothAdapter = (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bleJsonClient = BleJsonClient(requireContext().applicationContext)

        setupTabs()
        setupLogList()
        setupChart()
        setupButtons()
        setupObservers()

        ensureBlePermissions()
        applyHeaderVisibility(0)

        binding.imageViewBackgroundT2.drawable?.let { d ->
            binding.overlayMarkerDirectionViewT2.setFitCenterImageSize(d.intrinsicWidth, d.intrinsicHeight)
        }

        binding.imageViewBackgroundT3.drawable?.let { d ->
            binding.overlayMarkerDirectionViewT3.setFitCenterImageSize(d.intrinsicWidth, d.intrinsicHeight)
        }

        binding.sixCellFillGraphViewT2.setUnit("N/mm")     // 예: "%" 또는 "N" 등
        binding.sixCellBipolarGraphViewT3.setUnit("mm")  // 예: "deg" 또는 "norm" 등
        binding.sixCellFillGraphViewT2.setLabel("F")     //
        binding.sixCellBipolarGraphViewT3.setLabel("LD")   // 예: r(회전)
    }

    private fun ensureBlePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        val need = permissions.any {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (need) requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun setupTabs() {
        binding.tabLayoutMeasurement.apply {
            removeAllTabs()
            addTab(newTab().setText("T1: Baseline"))
            addTab(newTab().setText("T2: Tissue Response"))
            addTab(newTab().setText("T3: MovementControl"))

            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    val idx = tab.position
                    viewModel.setSelectedTab(idx)
                    applyHeaderVisibility(idx)
                    applyLogHeader(idx)
                    // (선택) 탭 변경 시 그래프/로그는 유지하고 싶으면 그대로,
                    // 탭별로 새로 시작하고 싶으면 ViewModel에 clear() 만들어 호출하면 됨.
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }
    }

    private fun applyHeaderVisibility(tabIndex: Int) {
        binding.linearLayoutHeaderT1.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        binding.linearLayoutHeaderT2.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        binding.linearLayoutHeaderT3.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
    }

    private fun setupLogList() {
        logAdapter = MeasurementLogAdapter(requireContext(), mutableListOf())
        binding.listViewRealtimeLogs.adapter = logAdapter
    }

    private fun setupChart() {
        binding.lineChartRealtime.apply {
            description.isEnabled = false
            legend.isEnabled = false

            setTouchEnabled(false)
            setDragEnabled(false)
            setScaleEnabled(false)

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)

            axisRight.isEnabled = false
            axisLeft.setDrawGridLines(true)

            data = LineData().apply {
                setDrawValues(false) //  데이터 전체 값 텍스트 OFF
            }

            invalidate()
        }
    }
    private fun setupButtons() {
        // 초기: 연결 버튼만 보이게
        setButtonsConnected(false)

        binding.buttonBluetoothConnect.setOnClickListener {
            startBle()
        }

        binding.buttonZeroContainer.setOnClickListener {
            // 영점=캘리브레이션으로 연결 (문서에 따라 바뀌면 여기만 변경)
            bleJsonClient?.sendCalibrate()
            bleJsonClient?.sendGetStatus()
        }

        binding.buttonStartContainer.setOnClickListener {
            bleJsonClient?.sendStartMeasurement()
        }

        binding.buttonEndAndSaveContainer.setOnClickListener {
            bleJsonClient?.sendStopMeasurement()
            bleJsonClient?.sendResetSystem()

            val tabIndex = viewModel.selectedTab.value ?: 0
            showSaveDialogAndPersist(tabIndex)
        }
    }
    private fun showSaveDialogAndPersist(tabIndex: Int) {
        val frame = viewModel.latestDFrame ?: run {
            Toast.makeText(requireContext(), "저장할 데이터가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("measurement_pref", Context.MODE_PRIVATE)

        val patientEt = android.widget.EditText(ctx).apply {
            hint = "환자번호 (예: AA01)"
        }
        val posEt = android.widget.EditText(ctx).apply {
            hint = "검사위치 (예: 5)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val wrap = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(patientEt)
            addView(posEt)
        }

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("파일명 생성")
            .setView(wrap)
            .setPositiveButton("저장") { _, _ ->
                val patientId = patientEt.text?.toString()?.trim()?.uppercase().orEmpty()
                val position = posEt.text?.toString()?.toIntOrNull() ?: 0

                if (patientId.isBlank()) {
                    Toast.makeText(ctx, "환자번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                //  파일명 생성 (AA01-260127-T2-5-01)
                val filename = com.coremotion.perik3.util.MeasurementFileNameUtil.buildFileName(
                    patientId = patientId,
                    tabIndex = tabIndex,
                    positionIndex = position,
                    prefs = prefs
                )

                //  “화면 스냅샷” (로그/그래프 일부 + latestFrameRaw 포함)
                val snapshot = viewModel.buildSnapshotJsonString()

                prefs.edit()
                    .putString("LAST_MEASUREMENT_FILENAME", filename)
                    .putString("LAST_MEASUREMENT_SNAPSHOT", snapshot)
                    .putString("LAST_MEASUREMENT_JSON", frame.rawJson)
                    .putLong("LAST_MEASUREMENT_TS", frame.receivedAtMs ?: System.currentTimeMillis())
                    .apply()

                Log.d("SAVE", "filename=$filename")
                Log.d("SAVE", "snapshot=${snapshot.take(200)}...")
                Toast.makeText(ctx, "저장됨: $filename", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }
    private fun setButtonsConnected(connected: Boolean) {
        binding.buttonBluetoothConnect.visibility = if (connected) View.GONE else View.VISIBLE
        binding.buttonZeroContainer.visibility = if (connected) View.VISIBLE else View.GONE
        binding.buttonStartContainer.visibility = if (connected) View.VISIBLE else View.GONE
        binding.buttonEndAndSaveContainer.visibility = if (connected) View.VISIBLE else View.GONE
    }

    private fun setupObservers() {
        // T1 Indicator (0/1/2)
        viewModel.t1IndicatorState.observe(viewLifecycleOwner) { stateId012 ->
            binding.threeStateIndicatorView.updateState(stateId012)
        }

        // T2 Fill (0..1)
        viewModel.t2FillFraction.observe(viewLifecycleOwner) { frac ->
            binding.sixCellFillGraphViewT2.updateNormalizedValue(frac)
        }

        // T3 Bipolar (-1..1)
        viewModel.t3BipolarValue.observe(viewLifecycleOwner) { value ->
            binding.sixCellBipolarGraphViewT3.updateNormalizedValue(value)
        }

        // Log rows
        viewModel.logRows.observe(viewLifecycleOwner) { rows ->
            logAdapter.submit(rows)
        }

        // Chart
        viewModel.chartEntries.observe(viewLifecycleOwner) { entries ->
            renderChart(entries)
        }

        viewModel.chartYMin.observe(viewLifecycleOwner) { minY ->
            val axis = binding.lineChartRealtime.axisLeft
            if (minY == null) axis.resetAxisMinimum() else axis.axisMinimum = minY
            binding.lineChartRealtime.invalidate()
        }

        viewModel.chartYMax.observe(viewLifecycleOwner) { maxY ->
            val axis = binding.lineChartRealtime.axisLeft
            if (maxY == null) axis.resetAxisMaximum() else axis.axisMaximum = maxY
            binding.lineChartRealtime.invalidate()
        }

        viewModel.selectedTab.observe(viewLifecycleOwner) { tab ->
            binding.lineChartRealtime.description.text = if (tab == 2) {
                "Y: Lift Displacement (LD), X: t"
            } else {
                "Y: Force (N), X: t"
            }
            binding.lineChartRealtime.invalidate()
        }

// T2 Overlay (5좌표) - 점만 표시(화살표는 T2에서 안 씀)
        viewModel.t2OverlayPoint.observe(viewLifecycleOwner) { p ->
            if (p != null) {
                binding.overlayMarkerDirectionViewT2.updateNormalizedPoint(p.x, p.y)
                binding.overlayMarkerDirectionViewT2.updateDirectionVector(0f, 0f, false) // 방향 OFF
            }
        }

//  T3 Overlay (3좌표) - 점 표시
        viewModel.t3OverlayPoint.observe(viewLifecycleOwner) { p ->
            if (p != null) {
                binding.overlayMarkerDirectionViewT3.updateNormalizedPoint(p.x, p.y)
                binding.overlayMarkerDirectionViewT3.setMarkerVisible(true)

            }
        }

//  T3 Arrow direction (r 기반) - 점이 있을 때만 화살표 ON
        viewModel.t3DirectionVec.observe(viewLifecycleOwner) { v ->
            val markerVisibleNow = binding.overlayMarkerDirectionViewT3.visibility == View.VISIBLE
            if (v != null && markerVisibleNow) {
                binding.overlayMarkerDirectionViewT3.updateDirectionVector(v.dx, v.dy, true)
            }
        }
    }

    private fun renderChart(entries: List<Entry>) {
        val safe = entries.filter { it.x.isFinite() && it.y.isFinite() }
        if (safe.size < 2) {
            binding.lineChartRealtime.clear()
            binding.lineChartRealtime.invalidate()
            return
        }

        val chart = binding.lineChartRealtime
        val lineData = chart.data ?: LineData().also { chart.data = it }

        val dataSet = if (lineData.dataSetCount == 0) {
            LineDataSet(ArrayList(safe), "data").apply {
                setDrawValues(false)
                setDrawCircles(false)
                lineWidth = 2f
            }.also { lineData.addDataSet(it) }
        } else {
            (lineData.getDataSetByIndex(0) as LineDataSet)
        }

        dataSet.values = ArrayList(safe)
        dataSet.setDrawValues(false)
        dataSet.setDrawCircles(false)

        lineData.notifyDataChanged()
        chart.notifyDataSetChanged()

        val minX = safe.first().x
        val maxX = safe.last().x

        chart.xAxis.axisMinimum = minX
        chart.xAxis.axisMaximum = maxX + 0.1f  // 오른쪽 여백 조금


        chart.invalidate()
    }

    private fun Float.isFinite(): Boolean = !this.isNaN() && !this.isInfinite()

    private fun startBle() {
        val adapter = bluetoothAdapter ?: run {
            Toast.makeText(requireContext(), "BluetoothAdapter 없음", Toast.LENGTH_SHORT).show()
            return
        }
        val client = bleJsonClient ?: return

        client.startScanAndConnect(adapter, object : BleJsonClient.Callback {

            override fun onLog(logLine: String) {
              //  Log.d("PeriK3_UI", "onLog len=${logLine.length} data=${logLine.take(800)}")
                //  이제 200ms마다 JSON 묶음 1줄만 들어옴
                // 필요하면 listViewRealtimeLogs에도 표시
                // viewModel.addBleLog(logLine) 같은 식으로 연결해도 됨
            }

            override fun onConnectionStateChanged(isConnected: Boolean) {
                android.util.Log.d("PeriK3_UI", "BLE connected=$isConnected")
                setButtonsConnected(isConnected)
                // 버튼 visible 토글도 여기서 하던 기존 로직 유지하면 됨
            }

            override fun onJsonStringReceived(jsonString: String) {
                Log.d("PeriK3_UI", "onJsonStringReceived len=${jsonString.length}")
                viewModel.onRawPacketReceived(jsonString, System.currentTimeMillis())
            }
        })
    }

    private fun applyLogHeader(tab: Int) {
        if (tab == 2) {
            // T3
            binding.tvHeaderC1.text = "Timestamp"
            binding.tvHeaderC2.text = "x"
            binding.tvHeaderC3.text = "y"
            binding.tvHeaderC4.text = "Lift Displacement"
            binding.tvHeaderC5.text = "Rotation"
        } else {
            // T1/T2
            binding.tvHeaderC1.text = "Timestamp"
            binding.tvHeaderC2.text = "State"
            binding.tvHeaderC3.text = "Force"
            binding.tvHeaderC4.text = "Disp(mm)"
            binding.tvHeaderC5.text = "Rotation"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fragmentMeasurementBinding = null
    }
}