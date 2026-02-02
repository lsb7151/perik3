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
        fun onJsonStringReceived(jsonString: String)
    }

    // ============================================================
    // 설정
    // ============================================================

    private val allowedDeviceNamePrefix: String = "JDY"
    private val minimumAcceptableRssi: Int = -85
    private val shouldLogOnlyJdyDevices: Boolean = true
    private val scanTimeoutMillis: Long = 15_000L
    private val flushIntervalMs: Long = 200L

    // ============================================================
    // Thread / Handler
    // ============================================================

    private val mainHandler = Handler(Looper.getMainLooper())

    private val workerThread = HandlerThread("BleJsonClientWorker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    // ============================================================
    // BLE runtime
    // ============================================================

    private var bluetoothLeScanner = (applicationContext.getSystemService(BluetoothManager::class.java))
        .adapter
        .bluetoothLeScanner

    @Volatile private var currentCallback: Callback? = null

    private var isScanning: Boolean = false
    private var isConnecting: Boolean = false

    private var connectedBluetoothGatt: BluetoothGatt? = null
    private var subscribedNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var discoveredWriteCharacteristic: BluetoothGattCharacteristic? = null

    private var lastSelectedDeviceName: String = ""
    private var lastSelectedDeviceAddress: String = ""
    private var lastSelectedDeviceRssi: Int = -999

    // ============================================================
    // Buffers (worker thread에서만 변경)
    // ============================================================

    private val rxBuffer = StringBuilder()

    private val pendingBleLogs: ArrayDeque<String> = ArrayDeque()
    private val pendingRxPackets: ArrayDeque<String> = ArrayDeque()

    // ✅ 동시성 보호용(혹시라도 logInternal이 main에서 들어오면 대비)
    private val lock = Any()

    // ============================================================
    // Timer flush (200ms 고정 tick)
    // ============================================================

    private val flushTicker = object : Runnable {
        override fun run() {
            flushNowOnWorker()
            workerHandler.postDelayed(this, flushIntervalMs)
        }
    }

    init {
        // ✅ 200ms 주기 flush 시작
        workerHandler.postDelayed(flushTicker, flushIntervalMs)
        Log.d("PeriK3_BLE_RAW", "BleJsonClient init, workerThread started")
    }

    private val scanTimeoutRunnable: Runnable = Runnable {
        if (isScanning) {
            logInternal("SCAN timeout -> stop")
            stopScanInternal()
        }
    }

    // ============================================================
    // 외부 API
    // ============================================================

    @SuppressLint("MissingPermission")
    fun startScanAndConnect(
        bluetoothAdapter: BluetoothAdapter,
        callback: Callback
    ) {
        Log.d("PeriK3_BLE_RAW", "startScanAndConnect() called") // ✅ 여기부터 안 뜨면 호출 자체가 안 됨
        currentCallback = callback

        if (isScanning || isConnecting || connectedBluetoothGatt != null) {
            logInternal("이미 스캔/연결 중 또는 연결 상태입니다. stopAndClose 후 재시도하세요.")
            return
        }

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            logInternal("BluetoothLeScanner를 가져오지 못했습니다.")
            return
        }

        logInternal("SCAN start prefix='$allowedDeviceNamePrefix' minRssi=$minimumAcceptableRssi")
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
        Log.d("PeriK3_BLE_RAW", "stopAndClose() called")
        stopScanInternal()
        disconnectAndCloseGatt("stopAndClose")
        currentCallback = null

        // ticker 중단 + thread 종료
        workerHandler.removeCallbacks(flushTicker)
        workerThread.quitSafely()
    }

    // ============================================================
    // ScanCallback
    // ============================================================

    private val scanCallback: ScanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device ?: return

            val deviceName: String =
                device.name
                    ?: result.scanRecord?.deviceName
                    ?: "UNKNOWN"

            val deviceAddress: String = device.address ?: "NO_ADDRESS"
            val rssi: Int = result.rssi

            if (!shouldLogOnlyJdyDevices || isJdyDeviceName(deviceName)) {
                logInternal("SCAN found name=$deviceName addr=$deviceAddress rssi=$rssi")
            }

            if (!isJdyDeviceName(deviceName)) return
            if (rssi < minimumAcceptableRssi) return
            if (isConnecting || connectedBluetoothGatt != null) return

            lastSelectedDeviceName = deviceName
            lastSelectedDeviceAddress = deviceAddress
            lastSelectedDeviceRssi = rssi

            logInternal("MATCH -> connect try name=$deviceName addr=$deviceAddress rssi=$rssi")

            stopScanInternal()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            logInternal("SCAN failed errorCode=$errorCode")
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
            logInternal("stopScan exception: ${e.message}")
        }

        isScanning = false
        logInternal("SCAN stopped")
    }

    // ============================================================
    // Connect / GATT
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        isConnecting = true
        Log.d("PeriK3_BLE_RAW", "connectGatt() called")

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
            Log.d("PeriK3_BLE_RAW", "onConnectionStateChange status=$status newState=$newState")

            val ok = status == BluetoothGatt.GATT_SUCCESS
            logInternal("GATT state status=$status ok=$ok newState=$newState")

            if (!ok) {
                mainHandler.post { currentCallback?.onConnectionStateChanged(false) }
                disconnectAndCloseGatt("connect fail status=$status")
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnecting = false
                mainHandler.post { currentCallback?.onConnectionStateChanged(true) }
                val started = gatt.discoverServices()
                logInternal("discoverServices started=$started")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mainHandler.post { currentCallback?.onConnectionStateChanged(false) }
                disconnectAndCloseGatt("disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d("PeriK3_BLE_RAW", "onServicesDiscovered status=$status")
            logInternal("onServicesDiscovered status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectAndCloseGatt("service discover fail status=$status")
                return
            }

            val fixedService = gatt.getService(PERIK3_SERVICE_UUID)
            val fixedChar = fixedService?.getCharacteristic(PERIK3_DATA_CHARACTERISTIC_UUID)
            val notifyChar = fixedChar ?: findFirstNotifiableCharacteristic(gatt)

            if (notifyChar == null) {
                logInternal("NO notifiable characteristic")
                return
            }

            subscribedNotifyCharacteristic = notifyChar
            discoveredWriteCharacteristic = notifyChar

            logInternal("notify char=${notifyChar.uuid} service=${notifyChar.service.uuid}")
            subscribeToNotifications(gatt, notifyChar)
        }

        @Deprecated("Android 12 이하")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val v = characteristic.value ?: return
            onCharacteristicChanged(gatt, characteristic, v)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // ✅ RX는 worker로 넘겨서 처리 (메인/BT스레드 블로킹 방지)
            workerHandler.post {
                handleIncomingBytesOnWorker(value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d("PeriK3_BLE_RAW", "onDescriptorWrite status=$status desc=${descriptor.uuid}")
            logInternal("onDescriptorWrite status=$status desc=${descriptor.uuid}")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                logInternal("NOTIFY subscribed -> sendGetStatus()")
                sendGetStatus()
            } else {
                logInternal("NOTIFY subscribe fail status=$status")
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
        val setOk = gatt.setCharacteristicNotification(characteristic, true)
        logInternal("setCharacteristicNotification ok=$setOk")

        val cccd = characteristic.getDescriptor(UUID.fromString(CCCD_UUID_STRING))
        if (cccd == null) {
            logInternal("CCCD not found (0x2902)")
            return
        }

        val hasNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        cccd.value = if (hasNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        val started = gatt.writeDescriptor(cccd)
        logInternal("writeDescriptor(CCCD) started=$started")
    }

    // ============================================================
    // RX parsing (worker only)
    // ============================================================

    private fun handleIncomingBytesOnWorker(value: ByteArray) {
        val chunk = try {
            String(value, Charsets.UTF_8)
        } catch (_: Exception) {
            value.joinToString(" ") { "%02X".format(it) }
        }
        if (chunk.isEmpty()) return

        rxBuffer.append(chunk)
        extractPacketsFromBufferOnWorker()
    }

    private fun extractPacketsFromBufferOnWorker() {
        while (true) {
            val buf = rxBuffer.toString()

            val crlf = buf.indexOf("\r\n")
            if (crlf >= 0) {
                val line = buf.substring(0, crlf)
                rxBuffer.delete(0, crlf + 2)
                handleLineOnWorker(line)
                continue
            }

            val lf = buf.indexOf("\n")
            if (lf >= 0) {
                val line = buf.substring(0, lf).trimEnd('\r')
                rxBuffer.delete(0, lf + 1)
                handleLineOnWorker(line)
                continue
            }

            val start = buf.indexOf('{')
            if (start < 0) break

            // brace 기반 JSON object 추출
            var depth = 0
            var inString = false
            var escape = false
            var endIndex = -1

            for (i in start until buf.length) {
                val c = buf[i]
                if (inString) {
                    if (escape) {
                        escape = false
                    } else {
                        when (c) {
                            '\\' -> escape = true
                            '"' -> inString = false
                        }
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                endIndex = i
                                break
                            }
                        }
                    }
                }
            }

            if (endIndex < 0) {
                if (rxBuffer.length > 120_000) {
                    rxBuffer.delete(0, rxBuffer.length - 20_000)
                }
                break
            }

            val json = buf.substring(start, endIndex + 1)
            rxBuffer.delete(0, endIndex + 1)
            enqueueRxPacketOnWorker(json)
        }

        if (rxBuffer.length > 200_000) rxBuffer.setLength(0)
    }

    private fun handleLineOnWorker(line: String) {
        val t = line.trim()
        if (t.isEmpty()) return

        val jsons = extractJsonObjectsLenient(t)
        if (jsons.isNotEmpty()) {
            jsons.forEach { enqueueRxPacketOnWorker(it) }
        } else {
            enqueueRxPacketOnWorker(t) // JSON 아니면 그냥 text로도 넣음
        }
    }

    private fun extractJsonObjectsLenient(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in text.indices) {
            when (text[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && start >= 0) {
                        out.add(text.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }

    private fun enqueueRxPacketOnWorker(packet: String) {
        val trimmed = packet.trim()
        if (trimmed.isEmpty()) return

        synchronized(lock) {
            pendingRxPackets.addLast(trimmed)
            while (pendingRxPackets.size > 600) pendingRxPackets.removeFirst()
        }

        // 유효 JSON 여부는 "로그용"으로만 체크(드랍 표시)
        val normalized = normalizeMaybeEscapedJson(trimmed)
        if (normalized != null) {
            val ok = try { JSONObject(normalized); true } catch (_: Exception) { false }
            if (!ok) logInternal("RX invalid json dropped: ${trimmed.take(240)}")
        }
    }

    private fun normalizeMaybeEscapedJson(s: String): String? {
        var t = s.trim()
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            t = t.substring(1, t.length - 1)
        }
        if (t.contains("\\\"")) {
            t = t
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\r", "\r")
                .replace("\\n", "\n")
        }
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        return t
    }

    // ============================================================
    // TX
    // ============================================================

    @SuppressLint("MissingPermission")
    fun writeAsciiCommand(commandString: String): Boolean {
        val gatt = connectedBluetoothGatt ?: run {
            logInternal("WRITE fail: no gatt")
            return false
        }

        val ch = discoveredWriteCharacteristic ?: subscribedNotifyCharacteristic ?: run {
            logInternal("WRITE fail: no characteristic")
            return false
        }

        ch.value = commandString.toByteArray(Charset.forName("UTF-8"))
        ch.writeType =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val started = gatt.writeCharacteristic(ch)
        logInternal("WRITE: '$commandString' started=$started")
        return started
    }

    fun sendMcuCommandPacket(commandId: Int, stateId: Int = 0, p1: Int = 0, p2: Int = 0): Boolean {
        val payload = "${commandId},${stateId},${p1},${p2}"
        val cs = calculateXorChecksumOfAscii(payload).toString(16).uppercase().padStart(2, '0')
        val packet = "\$${payload}*${cs}\r\n"
        logInternal("MCU TX: ${packet.replace("\r", "\\r").replace("\n", "\\n")}")
        return writeAsciiCommand(packet)
    }

    private fun calculateXorChecksumOfAscii(payloadText: String): Int {
        var checksumValue = 0
        for (b in payloadText.toByteArray(Charsets.US_ASCII)) {
            checksumValue = checksumValue xor (b.toInt() and 0xFF)
        }
        return checksumValue and 0xFF
    }

    fun sendStartMeasurement(): Boolean = sendMcuCommandPacket(0)
    fun sendStopMeasurement(): Boolean = sendMcuCommandPacket(1)
    fun sendResetSystem(): Boolean = sendMcuCommandPacket(2)
    fun sendGetStatus(): Boolean = sendMcuCommandPacket(3)
    fun sendSetMode(stateId: Int = 0, p1: Int = 0, p2: Int = 0): Boolean = sendMcuCommandPacket(4, stateId, p1, p2)
    fun sendCalibrate(): Boolean = sendMcuCommandPacket(5)
    fun sendGetState(stateId: Int = 0): Boolean = sendMcuCommandPacket(6, stateId)

    // ============================================================
    // close
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun disconnectAndCloseGatt(reason: String) {
        logInternal("disconnectAndCloseGatt: $reason")
        try { connectedBluetoothGatt?.disconnect() } catch (_: Exception) {}
        try { connectedBluetoothGatt?.close() } catch (_: Exception) {}
        connectedBluetoothGatt = null
        subscribedNotifyCharacteristic = null
        discoveredWriteCharacteristic = null
        isConnecting = false
    }

    // ============================================================
    // flush (worker tick)
    // ============================================================

    private fun flushNowOnWorker() {
        val cb = currentCallback ?: return

        val logsToFlush = mutableListOf<String>()
        val rxToFlush = mutableListOf<String>()

        synchronized(lock) {
            while (pendingBleLogs.isNotEmpty()) logsToFlush.add(pendingBleLogs.removeFirst())
            while (pendingRxPackets.isNotEmpty()) rxToFlush.add(pendingRxPackets.removeFirst())
        }

        if (logsToFlush.isNotEmpty()) {
            val payload = buildBleLogPayload(logsToFlush)
            Log.d("PeriK3_BLE", payload)
            mainHandler.post { cb.onLog(payload) }
        }

        if (rxToFlush.isNotEmpty()) {
            val payload = buildBleRxPayload(rxToFlush)
            // 길이만 출력 (전체 찍으면 logcat에서 안 보이는 경우 많음)
            Log.d("PeriK3_BLE_RAW", "flush RX batch count=${rxToFlush.size} len=${payload.length}")
            mainHandler.post { cb.onJsonStringReceived(payload) }
        }
    }

    private fun buildBleLogPayload(logs: List<String>): String {
        fun esc(s: String) =
            s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")

        val joined = logs.joinToString(",") { "\"${esc(it)}\"" }
        return "{\"type\":\"BLE_LOG\",\"count\":${logs.size},\"logs\":[${joined}]}"
    }

    private fun buildBleRxPayload(packets: List<String>): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val joined = packets.joinToString(",") { "\"${esc(it)}\"" }
        return "{\"type\":\"BLE_RX\",\"count\":${packets.size},\"packets\":[${joined}]}"
    }

    // ============================================================
    // logInternal (즉시 RAW 로그 + 큐 적재)
    // ============================================================

    private fun logInternal(message: String) {
        // ✅ flush 기다리기 전에 "호출됐는지"부터 확실히 보이게
        Log.d("PeriK3_BLE_RAW", message)

        workerHandler.post {
            synchronized(lock) {
                pendingBleLogs.addLast(message)
                while (pendingBleLogs.size > 600) pendingBleLogs.removeFirst()
            }
        }
    }

    companion object {
        private const val CCCD_UUID_STRING: String = "00002902-0000-1000-8000-00805f9b34fb"
        private val PERIK3_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val PERIK3_DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }
}