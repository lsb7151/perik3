package com.coremotion.perik3.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.UUID

class BleJsonClient(
    private val applicationContext: Context
) {

    interface Callback {
        fun onLog(logLine: String)
        fun onConnectionStateChanged(isConnected: Boolean)
        fun onJsonStringReceived(jsonString: String) // ✅ 정상 JSON만
    }

    // ============================================================
    // Scan/Connect 설정
    // ============================================================
    private val logLock = Any()
    private val allowedDeviceNamePrefix: String = "JDY"
    private val minimumAcceptableRssi: Int = -85
    private val shouldLogOnlyJdyDevices: Boolean = true
    private val scanTimeoutMillis: Long = 15_000L

    // ✅ UI로 전달하는 flush 주기
    private val flushIntervalMs: Long = 200L

    // ============================================================
    // Thread/Handler
    // ============================================================

    private val mainHandler = Handler(Looper.getMainLooper())

    private val workerThread = HandlerThread("BleJsonClientWorker").apply {
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
            android.util.Log.e("PeriK3_BLE_RAW", "Worker crashed", e)
        }
        start()
    }
    private val workerHandler = Handler(workerThread.looper)

    // ============================================================
    // Runtime State
    // ============================================================

    private var bluetoothLeScanner = (applicationContext.getSystemService(BluetoothManager::class.java))
        .adapter
        .bluetoothLeScanner

    private var currentCallback: Callback? = null

    private var isScanning: Boolean = false
    private var isConnecting: Boolean = false

    private var connectedBluetoothGatt: BluetoothGatt? = null
    private var subscribedNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var discoveredWriteCharacteristic: BluetoothGattCharacteristic? = null

    private var lastSelectedDeviceName: String = ""
    private var lastSelectedDeviceAddress: String = ""
    private var lastSelectedDeviceRssi: Int = -999

    // ✅ RX 버퍼 (worker thread only)
    private val rxBuffer = StringBuilder()

    // ✅ 200ms마다 마지막 정상 JSON 1개만 전달
    @Volatile private var latestValidJson: String? = null

    // ✅ 로그도 200ms마다 묶어서 전달 (메인 post 폭주 방지)
    private val pendingLogs: ArrayDeque<String> = ArrayDeque()

    // ✅ 버퍼 폭주 방지
    private val maxRxBufferChars = 200_000
    private val keepTailChars = 20_000

    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            enqueueLog("SCAN 타임아웃 → 중지")
            stopScanInternal()
        }
    }

    // ✅ flush 루프(고정 주기) : “한번 꼬이면 멈춤” 방지
    @Volatile private var flushLoopStarted = false
    private val flushRunnable = object : Runnable {
        override fun run() {
            flushOnceWorker()
            workerHandler.postDelayed(this, flushIntervalMs)
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    @SuppressLint("MissingPermission")
    fun startScanAndConnect(
        bluetoothAdapter: BluetoothAdapter,
        callback: Callback
    ) {
        currentCallback = callback

        if (!flushLoopStarted) {
            flushLoopStarted = true
            workerHandler.postDelayed(flushRunnable, flushIntervalMs)
        }

        if (isScanning || isConnecting || connectedBluetoothGatt != null) {
            enqueueLog("이미 스캔/연결 중 또는 연결 상태입니다. stopAndClose 후 재시도하세요.")
            return
        }

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            enqueueLog("BluetoothLeScanner를 가져오지 못했습니다.")
            return
        }

        enqueueLog("SCAN 시작 (allowedPrefix='$allowedDeviceNamePrefix', minRssi=$minimumAcceptableRssi)")
        isScanning = true
        isConnecting = false

        lastSelectedDeviceName = ""
        lastSelectedDeviceAddress = ""
        lastSelectedDeviceRssi = -999

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
        mainHandler.postDelayed(scanTimeoutRunnable, scanTimeoutMillis)
    }

    @SuppressLint("MissingPermission")
    fun stopAndClose() {
        stopScanInternal()
        disconnectAndCloseGatt("stopAndClose 호출")
        currentCallback = null
        // ✅ workerThread는 여기서 quit하지 말자 (예상치 못한 중단 방지)
        // workerThread.quitSafely()
    }

    // ============================================================
    // Scan
    // ============================================================

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device ?: return

            val deviceName = device.name ?: result.scanRecord?.deviceName ?: "UNKNOWN"
            val deviceAddress = device.address ?: "NO_ADDRESS"
            val rssi = result.rssi

            if (!shouldLogOnlyJdyDevices || isJdyDeviceName(deviceName)) {
                enqueueLog("SCAN 발견: name=$deviceName addr=$deviceAddress rssi=$rssi")
            }

            if (!isJdyDeviceName(deviceName)) return
            if (rssi < minimumAcceptableRssi) return
            if (isConnecting || connectedBluetoothGatt != null) return

            lastSelectedDeviceName = deviceName
            lastSelectedDeviceAddress = deviceAddress
            lastSelectedDeviceRssi = rssi

            enqueueLog("✅ JDY 조건 일치 → 연결 시도: name=$deviceName addr=$deviceAddress rssi=$rssi")
            stopScanInternal()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            enqueueLog("SCAN 실패 errorCode=$errorCode")
            isScanning = false
        }
    }

    private fun isJdyDeviceName(deviceName: String): Boolean {
        if (deviceName.isBlank()) return false
        if (deviceName == "UNKNOWN") return false
        return deviceName.startsWith(allowedDeviceNamePrefix, ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (!isScanning) return

        mainHandler.removeCallbacks(scanTimeoutRunnable)
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            enqueueLog("stopScan 예외: ${e.message}")
        }

        isScanning = false
        enqueueLog("SCAN 중지")
    }

    // ============================================================
    // Connect / GATT
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        isConnecting = true
        val gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(applicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(applicationContext, false, gattCallback)
        }
        connectedBluetoothGatt = gatt
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            enqueueLog("GATT 상태변경: status=$status success=$ok newState=$newState")

            if (!ok) {
                safePostMain { currentCallback?.onConnectionStateChanged(false) }
                disconnectAndCloseGatt("연결 실패(status=$status)")
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnecting = false
                enqueueLog("✅ GATT CONNECTED: name=$lastSelectedDeviceName addr=$lastSelectedDeviceAddress rssi=$lastSelectedDeviceRssi")
                safePostMain { currentCallback?.onConnectionStateChanged(true) }
                enqueueLog("discoverServices started=${gatt.discoverServices()}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                enqueueLog("GATT DISCONNECTED")
                safePostMain { currentCallback?.onConnectionStateChanged(false) }
                disconnectAndCloseGatt("연결 해제")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            enqueueLog("onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectAndCloseGatt("서비스 탐색 실패(status=$status)")
                return
            }

            val fixedService = gatt.getService(PERIK3_SERVICE_UUID)
            val fixedChar = fixedService?.getCharacteristic(PERIK3_DATA_CHARACTERISTIC_UUID)
            val notifyChar = fixedChar ?: findFirstNotifiableCharacteristic(gatt)

            if (notifyChar == null) {
                enqueueLog("❌ NOTIFY 가능한 Characteristic을 찾지 못했습니다.")
                return
            }

            subscribedNotifyCharacteristic = notifyChar
            discoveredWriteCharacteristic = notifyChar

            enqueueLog("✅ NOTIFY 대상: service=${notifyChar.service.uuid} char=${notifyChar.uuid}")
            subscribeToNotifications(gatt, notifyChar)
        }

        @Deprecated("Android 12 이하 호환")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { handleIncomingBytes(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingBytes(value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            enqueueLog("onDescriptorWrite status=$status desc=${descriptor.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enqueueLog("✅ NOTIFY 구독 완료 -> sendGetStatus()")
                sendGetStatus()
            } else {
                enqueueLog("❌ NOTIFY 구독 실패 status=$status")
            }
        }
    }

    private fun findFirstNotifiableCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val services = gatt.services ?: return null
        for (s in services) {
            for (c in s.characteristics ?: emptyList()) {
                val hasNotify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val hasIndicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (hasNotify || hasIndicate) return c
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val ok = gatt.setCharacteristicNotification(characteristic, true)
        enqueueLog("setCharacteristicNotification ok=$ok")

        val cccd = characteristic.getDescriptor(UUID.fromString(CCCD_UUID_STRING))
        if (cccd == null) {
            enqueueLog("❌ CCCD descriptor(0x2902) 없음")
            return
        }

        val hasNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        cccd.value = if (hasNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

        enqueueLog("writeDescriptor(CCCD) started=${gatt.writeDescriptor(cccd)}")
    }

    // ============================================================
    // RX 처리 (worker thread)
    // ============================================================

    private fun handleIncomingBytes(value: ByteArray) {
        workerHandler.post {
            val chunk = try {
                String(value, Charsets.UTF_8)
            } catch (_: Exception) {
                value.joinToString(" ") { "%02X".format(it) }
            }
            if (chunk.isEmpty()) return@post

            rxBuffer.append(chunk)

            if (rxBuffer.length > maxRxBufferChars) {
                rxBuffer.delete(0, rxBuffer.length - keepTailChars)
                enqueueLog("RX buffer trimmed (too large)")
            }

            extractJsonObjectsFromBufferWorker()
        }
    }

    // ============================================================
    // ✅ [핵심 수정] 좀비 데이터 자동 복구 파서
    // 괄호가 안 닫힌 채로 너무 오래 버티면 강제로 끊어냅니다.
    // ============================================================
    private fun extractJsonObjectsFromBufferWorker() {
        var loopSafetyCount = 0
        val maxLoopsPerCall = 50

        // JSON 하나가 4000자를 넘을 리 없다고 가정 (BLE 패킷 특성상)
        // 이 길이를 넘도록 '}'가 안 나오면 앞부분을 잘라버림
        val maxSingleJsonLength = 4096

        while (loopSafetyCount < maxLoopsPerCall) {
            loopSafetyCount++

            val buf = rxBuffer.toString()
            val start = buf.indexOf('{')

            // 1. 여는 괄호가 아예 없으면? -> 데이터가 더 쌓일 때까지 대기
            if (start < 0) {
                // 단, 쓰레기 데이터가 너무 쌓이면 정리
                if (rxBuffer.length > keepTailChars) {
                    rxBuffer.delete(0, rxBuffer.length - keepTailChars)
                    Log.w("PeriK3_BLE_RAW", "Garbage trimmed (No '{' found)")
                }
                return
            }

            // 2. '{' 앞부분의 쓰레기 데이터 제거
            if (start > 0) {
                rxBuffer.delete(0, start)
                continue // 다시 루프 시작 (인덱스 0이 '{'가 됨)
            }

            // 3. 괄호 짝 맞추기 시작
            var depth = 0
            var inString = false
            var escape = false
            var endIndex = -1

            // 안전장치: 너무 길어지면 포기하기 위한 플래그
            var isTooLong = false

            for (i in 0 until rxBuffer.length) {
                // 제한 길이 초과 체크
                if (i > maxSingleJsonLength) {
                    isTooLong = true
                    break
                }

                val c = rxBuffer[i]
                if (inString) {
                    if (escape) escape = false
                    else when (c) {
                        '\\' -> escape = true
                        '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            // 깊이가 0이 되면 하나의 JSON 완성
                            if (depth == 0) {
                                endIndex = i
                                break
                            }
                        }
                    }
                }
            }

            // 4-A. 너무 길어져서 강제 폐기 (좀비 데이터 탈출)
            if (isTooLong) {
                Log.e("PeriK3_BLE_RAW", "🚨 JSON Too Long/Corrupted (Zombie data). Dropping start.")
                // 맨 앞의 '{' 하나를 지워서 다음 '{'를 찾도록 유도
                rxBuffer.delete(0, 1)
                continue
            }

            // 4-B. 아직 닫는 괄호가 안 옴 (데이터 수신 중)
            if (endIndex < 0) {
                return
            }

            // 5. JSON 추출 성공
            val json = rxBuffer.substring(0, endIndex + 1).trim()
            rxBuffer.delete(0, endIndex + 1) // 추출한 부분 버퍼에서 삭제

            // 유효성 검사 및 전송
            val ok = try {
                if (!json.startsWith("{") || !json.endsWith("}")) false
                else {
                    JSONObject(json) // 파싱 확인
                    true
                }
            } catch (_: Exception) {
                false
            }

            if (ok) {
                Log.d("PeriK3_BLE_RAW", "✅ JSON OK: ${json.take(60)}...") // Logcat에서 확인
                latestValidJson = json
            } else {
                Log.w("PeriK3_BLE_RAW", "⚠️ Broken JSON Skipped")
            }
        }
    }

    // ============================================================
    // flush (worker -> main) : 200ms 고정 주기
    // ============================================================

    private fun flushOnceWorker() {
        val logs = mutableListOf<String>()

        synchronized(logLock) {
            while (pendingLogs.isNotEmpty()) {
                logs.add(pendingLogs.removeFirst())
            }
        }

        val lastJson = latestValidJson
        latestValidJson = null

        if (logs.isEmpty() && lastJson.isNullOrBlank()) return

        val cb = currentCallback ?: return

        safePostMain {
            try {
                if (logs.isNotEmpty()) {
                    cb.onLog(buildBleLogPayload(logs))
                }
                if (!lastJson.isNullOrBlank()) {
                    cb.onJsonStringReceived(lastJson)
                }
            } catch (e: Exception) {
                android.util.Log.e("PeriK3_BLE_RAW", "Callback error", e)
            }
        }
    }

    private fun buildBleLogPayload(logs: List<String>): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r","\\r").replace("\n","\\n")
        val joined = logs.joinToString(",") { "\"${esc(it)}\"" }
        return "{\"type\":\"BLE_LOG\",\"count\":${logs.size},\"logs\":[${joined}]}"
    }

    private fun enqueueLog(msg: String) {
        val shortMsg = if (msg.length > 250) msg.take(250) + "..." else msg
        synchronized(logLock) {
            pendingLogs.addLast(shortMsg)
            while (pendingLogs.size > 200) {
                pendingLogs.removeFirst()
            }
        }
    }

    private fun safePostMain(block: () -> Unit) {
        try {
            mainHandler.post { block() }
        } catch (e: Exception) {
            android.util.Log.e("PeriK3_BLE_RAW", "main post failed", e)
        }
    }

    // ============================================================
    // TX (기존 유지)
    // ============================================================

    @SuppressLint("MissingPermission")
    fun writeAsciiCommand(commandString: String): Boolean {
        val gatt = connectedBluetoothGatt ?: run {
            enqueueLog("❌ WRITE 실패: GATT 없음(미연결)")
            return false
        }
        val ch = discoveredWriteCharacteristic ?: subscribedNotifyCharacteristic ?: run {
            enqueueLog("❌ WRITE 실패: write characteristic 없음")
            return false
        }

        val payloadBytes = commandString.toByteArray(Charset.forName("UTF-8"))
        ch.value = payloadBytes

        ch.writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val started = gatt.writeCharacteristic(ch)
        enqueueLog("WRITE: '$commandString' started=$started")
        return started
    }

    fun sendMcuCommandPacket(commandId: Int, stateId: Int = 0, parameter1: Int = 0, parameter2: Int = 0): Boolean {
        val payload = "${commandId},${stateId},${parameter1},${parameter2}"
        val checksum = calculateXorChecksumOfAscii(payload).toString(16).uppercase().padStart(2, '0')
        val packet = "\$${payload}*${checksum}\r\n"
        enqueueLog("MCU TX: ${packet.replace("\r", "\\r").replace("\n", "\\n")}")
        return writeAsciiCommand(packet)
    }

    private fun calculateXorChecksumOfAscii(payloadText: String): Int {
        var checksumValue = 0
        val asciiBytes = payloadText.toByteArray(Charsets.US_ASCII)
        for (b in asciiBytes) checksumValue = checksumValue xor (b.toInt() and 0xFF)
        return checksumValue and 0xFF
    }

    fun sendStartMeasurement(): Boolean = sendMcuCommandPacket(commandId = 0)
    fun sendStopMeasurement(): Boolean = sendMcuCommandPacket(commandId = 1)
    fun sendResetSystem(): Boolean = sendMcuCommandPacket(commandId = 2)
    fun sendGetStatus(): Boolean = sendMcuCommandPacket(commandId = 3)
    fun sendSetMode(stateId: Int = 0, param1: Int = 0, param2: Int = 0): Boolean =
        sendMcuCommandPacket(commandId = 4, stateId = stateId, parameter1 = param1, parameter2 = param2)
    fun sendCalibrate(): Boolean = sendMcuCommandPacket(commandId = 5)
    fun sendGetState(stateId: Int = 0): Boolean = sendMcuCommandPacket(commandId = 6, stateId = stateId)

    // ============================================================
    // GATT 정리
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun disconnectAndCloseGatt(reason: String) {
        enqueueLog("disconnectAndCloseGatt: $reason")
        try { connectedBluetoothGatt?.disconnect() } catch (_: Exception) {}
        try { connectedBluetoothGatt?.close() } catch (_: Exception) {}
        connectedBluetoothGatt = null
        subscribedNotifyCharacteristic = null
        discoveredWriteCharacteristic = null
        isConnecting = false
    }

    companion object {
        private const val CCCD_UUID_STRING: String = "00002902-0000-1000-8000-00805f9b34fb"
        private val PERIK3_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val PERIK3_DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }
}