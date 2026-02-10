package com.coremotion.perik3.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
        applySummaryVisibility(0)

        binding.imageViewBackgroundT2.drawable?.let { d ->
            binding.overlayMarkerDirectionViewT2.setFitCenterImageSize(d.intrinsicWidth, d.intrinsicHeight)
        }

        binding.imageViewBackgroundT3.drawable?.let { d ->
            binding.overlayMarkerDirectionViewT3.setFitCenterImageSize(d.intrinsicWidth, d.intrinsicHeight)
        }
// T2 overlay 기본 위치 = TOP 포인트
        binding.overlayMarkerDirectionViewT2.setDefaultMarkerPositionNormalized(0.5f, 0.30f)

// T3 overlay 기본 위치 = TOP 포인트
        binding.overlayMarkerDirectionViewT3.setDefaultMarkerPositionNormalized(0.5f, 0.40f)
        binding.sixCellFillGraphViewT2.setUnit("N/mm")
        binding.sixCellBipolarGraphViewT3.setUnit("mm")
        binding.sixCellFillGraphViewT2.setLabel("F")
        binding.sixCellBipolarGraphViewT3.setLabel("LD")
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
                    applySummaryVisibility(idx)
                    applyLogHeader(idx)
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

    private fun applySummaryVisibility(tabIndex: Int) {
        // ✅ T1은 숨김, T2/T3만 표시 (내용은 ViewModel summaryText에서 결정)
        binding.peakVelocityText.visibility = if (tabIndex == 0) View.GONE else View.VISIBLE
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
                setDrawValues(false)
            }

            invalidate()
        }
    }

    private fun setupButtons() {
        setButtonsConnected(false)

        binding.buttonBluetoothConnect.setOnClickListener {
            startBle()
        }

        binding.buttonZeroContainer.setOnClickListener {
            bleJsonClient?.sendCalibrate()
            bleJsonClient?.sendGetStatus()
        }

        binding.buttonStartContainer.setOnClickListener {
            bleJsonClient?.sendStartMeasurement()
        }

        // ✅ Save Result(End and Save): 저장 성공 시 전체 리셋
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
            // 버튼을 눌렀으니 "새 측정 준비"로 리셋하는게 원하면 아래 주석 해제
            // viewModel.resetAllAfterSaveResult()
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

                val filename = com.coremotion.perik3.util.MeasurementFileNameUtil.buildFileName(
                    patientId = patientId,
                    tabIndex = tabIndex,
                    positionIndex = position,
                    prefs = prefs
                )

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
                viewModel.resetAllResults()
                // ✅ 저장 완료 → “모든 데이터(리스트/텍스트/그래프/peak/p결과)” 리셋
                viewModel.resetAllAfterSaveResult()
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
                "Y: posY (py), X: t"
            } else {
                "Y: Force (N), X: t"
            }

            binding.chartYAxisLabel.text = when (tab) {
                1 -> "Force (N)"
                2 -> "Ventral displacement (mm)"
                else -> ""
            }
            binding.lineChartRealtime.invalidate()
        }

        // T2 Overlay
        viewModel.t2OverlayPoint.observe(viewLifecycleOwner) { p ->
            if (p != null) {
                binding.overlayMarkerDirectionViewT2.updateNormalizedPoint(p.x, p.y)
                binding.overlayMarkerDirectionViewT2.updateDirectionVector(0f, 0f, false)
                binding.overlayMarkerDirectionViewT2.setMarkerVisible(true)
            }
        }

        // T3 Overlay
        viewModel.t3OverlayPoint.observe(viewLifecycleOwner) { p ->
            if (p != null) {
                binding.overlayMarkerDirectionViewT3.updateNormalizedPoint(p.x, p.y)
                binding.overlayMarkerDirectionViewT3.setMarkerVisible(true)
            }
        }

        // T3 Arrow
        viewModel.t3DirectionVec.observe(viewLifecycleOwner) { v ->
            if (v != null) {
                binding.overlayMarkerDirectionViewT3.updateDirectionVector(v.dx, v.dy, true)
            } else {
                binding.overlayMarkerDirectionViewT3.updateDirectionVector(0f, 0f, false)
            }
        }

        viewModel.t3LvText.observe(viewLifecycleOwner) { text ->
            binding.sixCellBipolarGraphViewT3.setBottomValueText(text)
        }

        // ✅ 요약 텍스트 (T2/T3)
        viewModel.summaryText.observe(viewLifecycleOwner) { text ->
            binding.peakVelocityText.text = text
            val tab = viewModel.selectedTab.value ?: 0
            binding.peakVelocityText.visibility =
                if (tab == 0 || text.isBlank()) View.GONE else View.VISIBLE
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
        chart.xAxis.axisMaximum = maxX + 0.1f
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
                // 필요하면 로그 표시 연결
            }

            override fun onConnectionStateChanged(isConnected: Boolean) {
                Log.d("PeriK3_UI", "BLE connected=$isConnected")
                setButtonsConnected(isConnected)
            }

            override fun onJsonStringReceived(jsonString: String) {
                Log.d("PeriK3_UI", "onJsonStringReceived len=${jsonString.length}")
                viewModel.onRawPacketReceived(jsonString, System.currentTimeMillis())
            }
        })
    }

    private fun applyLogHeader(tab: Int) {
        if (tab == 2) {
            binding.tvHeaderC1.text = "Timestamp"
            binding.tvHeaderC2.text = "x"
            binding.tvHeaderC3.text = "y"
            binding.tvHeaderC4.text = "Lift Displacement"
            binding.tvHeaderC5.text = "Rotation"
        } else {
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