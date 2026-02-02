package com.coremotion.perik3.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class BleJsonClient(
    private val applicationContext: Context
) {

    interface Callback {
        fun onLog(logLine: String)
        fun onConnectionStateChanged(isConnected: Boolean)
        fun onJsonStringReceived(jsonString: String)
    }

    // ============================================================
    // 스캔/연결 설정
    // ============================================================

    private val allowedDeviceNamePrefix: String = "JDY"
    private val minimumAcceptableRssi: Int = -85
    private val shouldLogOnlyJdyDevices: Boolean = true
    private val scanTimeoutMillis: Long = 15_000L

    // ✅ 로그/수신 Throttle
    private val flushIntervalMs: Long = 200L

    // ============================================================
    // 런타임 상태
    // ============================================================

    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private var bluetoothLeScanner = (applicationContext.getSystemService(BluetoothManager::class.java))
        .adapter
        .bluetoothLeScanner

    private var currentCallback: Callback? = null

    private var isScanning: Boolean = false
    private var isConnecting: Boolean = false
    private var hasConnectedOnce: Boolean = false

    private var connectedBluetoothGatt: BluetoothGatt? = null
    private var subscribedNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var discoveredWriteCharacteristic: BluetoothGattCharacteristic? = null

    private var lastSelectedDeviceName: String = ""
    private var lastSelectedDeviceAddress: String = ""
    private var lastSelectedDeviceRssi: Int = -999

    // ✅ RX chunk 누적 버퍼
    private val rxBuffer: StringBuilder = StringBuilder()

    // ✅ (핵심) 로그/수신을 200ms마다 한 번에 내보내기 위한 버퍼
    private val pendingBleLogs = CopyOnWriteArrayList<String>()
    private val pendingRxPackets = CopyOnWriteArrayList<String>() // 라인/JSON 모두
    @Volatile private var latestJsonPacket: String? = null

    @Volatile private var isFlushScheduled: Boolean = false

    private val scanTimeoutRunnable: Runnable = Runnable {
        if (isScanning) {
            logInternal("SCAN 타임아웃 → 중지")
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

        logInternal("SCAN 시작 (allowedPrefix='$allowedDeviceNamePrefix', minRssi=$minimumAcceptableRssi)")
        isScanning = true
        isConnecting = false
        hasConnectedOnce = false

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
                logInternal("SCAN 발견: name=$deviceName addr=$deviceAddress rssi=$rssi")
            }

            if (!isJdyDeviceName(deviceName)) return
            if (rssi < minimumAcceptableRssi) return
            if (isConnecting || connectedBluetoothGatt != null) return

            lastSelectedDeviceName = deviceName
            lastSelectedDeviceAddress = deviceAddress
            lastSelectedDeviceRssi = rssi

            logInternal("✅ JDY 조건 일치 → 연결 시도: name=$deviceName addr=$deviceAddress rssi=$rssi")

            stopScanInternal()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            logInternal("SCAN 실패 errorCode=$errorCode")
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
        } catch (exception: Exception) {
            logInternal("stopScan 예외: ${exception.message}")
        }

        isScanning = false
        logInternal("SCAN 중지")
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
            val isSuccess = status == BluetoothGatt.GATT_SUCCESS
            logInternal("GATT 상태변경: status=$status success=$isSuccess newState=$newState")

            if (!isSuccess) {
                mainHandler.post { currentCallback?.onConnectionStateChanged(false) }
                disconnectAndCloseGatt("연결 실패(status=$status)")
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                hasConnectedOnce = true
                isConnecting = false

                logInternal("✅ GATT CONNECTED: name=$lastSelectedDeviceName addr=$lastSelectedDeviceAddress rssi=$lastSelectedDeviceRssi")
                mainHandler.post { currentCallback?.onConnectionStateChanged(true) }

                val discoverStarted = gatt.discoverServices()
                logInternal("discoverServices started=$discoverStarted")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logInternal("GATT DISCONNECTED")
                mainHandler.post { currentCallback?.onConnectionStateChanged(false) }
                disconnectAndCloseGatt("연결 해제")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logInternal("onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectAndCloseGatt("서비스 탐색 실패(status=$status)")
                return
            }

            val fixedService = gatt.getService(PERIK3_SERVICE_UUID)
            val fixedDataCharacteristic = fixedService?.getCharacteristic(PERIK3_DATA_CHARACTERISTIC_UUID)

            val notifyCharacteristic = fixedDataCharacteristic ?: findFirstNotifiableCharacteristic(gatt)
            if (notifyCharacteristic == null) {
                logInternal("❌ NOTIFY 가능한 Characteristic을 찾지 못했습니다. (FFE0/FFE1도 없음)")
                return
            }

            subscribedNotifyCharacteristic = notifyCharacteristic
            discoveredWriteCharacteristic = notifyCharacteristic

            logInternal("✅ NOTIFY 대상: service=${notifyCharacteristic.service.uuid} char=${notifyCharacteristic.uuid}")
            logInternal("✅ WRITE 후보: service=${notifyCharacteristic.service.uuid} char=${notifyCharacteristic.uuid}")

            subscribeToNotifications(gatt, notifyCharacteristic)
        }

        @Deprecated("Android 12 이하 콜백 호환")
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
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            logInternal("onDescriptorWrite status=$status desc=${descriptor.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logInternal("✅ NOTIFY 구독 완료 -> sendGetStatus()")
                sendGetStatus()
                // ✅ 필요하면 여기서 START까지 자동으로 붙여도 됨
                // sendStartMeasurement()
            } else {
                logInternal("❌ NOTIFY 구독 실패 status=$status")
            }
        }
    }

    // ============================================================
    // Service/Characteristic 탐색 & 구독
    // ============================================================

    private fun findFirstNotifiableCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val services = gatt.services ?: return null
        for (service in services) {
            for (characteristic in service.characteristics ?: emptyList()) {
                val hasNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val hasIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (hasNotify || hasIndicate) {
                    return characteristic
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val setOk = gatt.setCharacteristicNotification(characteristic, true)
        logInternal("setCharacteristicNotification ok=$setOk")

        val cccdUuid: UUID = UUID.fromString(CCCD_UUID_STRING)
        val cccdDescriptor = characteristic.getDescriptor(cccdUuid)

        if (cccdDescriptor == null) {
            logInternal("❌ CCCD(0x2902) descriptor를 찾지 못했습니다. (notify 설정 불가)")
            return
        }

        val hasNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val enableValue = if (hasNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        cccdDescriptor.value = enableValue

        val writeOk = gatt.writeDescriptor(cccdDescriptor)
        logInternal("writeDescriptor(CCCD) started=$writeOk")
    }

    // ============================================================
    // RX 처리: bytes -> packet 추출 (라인/JSON) + 200ms flush
    // ============================================================

    private fun handleIncomingBytes(value: ByteArray) {
        val chunkText = try {
            String(value, Charsets.UTF_8)
        } catch (_: Exception) {
            value.joinToString(" ") { "%02X".format(it) }
        }

        if (chunkText.isEmpty()) return

        rxBuffer.append(chunkText)
        extractPacketsFromBuffer()
    }

    private fun extractPacketsFromBuffer() {
        while (true) {
            val buf = rxBuffer.toString()

            // 1) CRLF 라인
            val crlf = buf.indexOf("\r\n")
            if (crlf >= 0) {
                val line = buf.substring(0, crlf)
                rxBuffer.delete(0, crlf + 2)

                if (line.isNotBlank()) {
                    val jsons = extractJsonObjectsFromText(line)
                    if (jsons.isNotEmpty()) {
                        jsons.forEach { enqueueRxPacket(it) }   // ✅ JSON만 넣는다
                    } else {
                        enqueueRxPacket(line)                   // JSON 없으면 그냥 로그용
                    }
                }
                continue
            }
            // 2) LF 라인
            val lf = buf.indexOf("\n")
            if (lf >= 0) {
                val line = buf.substring(0, lf).trimEnd('\r')
                rxBuffer.delete(0, lf + 1)

                if (line.isNotBlank()) {
                    val jsons = extractJsonObjectsFromText(line)
                    if (jsons.isNotEmpty()) {
                        jsons.forEach { enqueueRxPacket(it) }
                    } else {
                        enqueueRxPacket(line)
                    }
                }
                continue
            }

            // 3) JSON 완성({ ... }) 단위 추출 (brace depth)
            val start = buf.indexOf('{')
            if (start < 0) break

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
                // JSON 미완성 -> 더 모으기
                if (rxBuffer.length > 100_000) {
                    rxBuffer.delete(0, rxBuffer.length - 20_000)
                }
                break
            }

            val json = buf.substring(start, endIndex + 1)
            rxBuffer.delete(0, endIndex + 1)
            enqueueRxPacket(json)
        }

        if (rxBuffer.length > 200_000) {
            rxBuffer.setLength(0)
        }
    }

    private fun extractJsonObjectsFromText(text: String): List<String> {
        val result = mutableListOf<String>()

        val firstBrace = text.indexOf('{')
        if (firstBrace < 0) return emptyList()

        var depth = 0
        var inString = false
        var escape = false
        var objStart = -1

        for (i in firstBrace until text.length) {
            val c = text[i]

            if (inString) {
                if (escape) {
                    escape = false
                } else {
                    when (c) {
                        '\\' -> escape = true
                        '"' -> inString = false
                    }
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        result.add(text.substring(objStart, i + 1))
                        objStart = -1
                    }
                }
            }
        }

        return result
    }

    private fun enqueueRxPacket(packet: String) {
        val trimmed = packet.trim()
        pendingRxPackets.add(trimmed)

        // ✅ JSON이면 "가장 최신 1개"만 유지
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            latestJsonPacket = trimmed
        }

        scheduleFlushIfNeeded()
    }

    // ============================================================
    // TX: MCU command (checksum XOR) - 기존 유지
    // ============================================================

    @SuppressLint("MissingPermission")
    fun writeAsciiCommand(commandString: String): Boolean {
        val gatt = connectedBluetoothGatt ?: run {
            logInternal("❌ WRITE 실패: GATT 없음(미연결)")
            return false
        }

        val writeCharacteristic = discoveredWriteCharacteristic ?: subscribedNotifyCharacteristic ?: run {
            logInternal("❌ WRITE 실패: write characteristic 없음")
            return false
        }

        val payloadBytes = commandString.toByteArray(Charset.forName("UTF-8"))
        writeCharacteristic.value = payloadBytes

        writeCharacteristic.writeType = if (
            writeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val started = gatt.writeCharacteristic(writeCharacteristic)
        logInternal("WRITE: '$commandString' started=$started")
        return started
    }

    fun sendMcuCommandPacket(
        commandId: Int,
        stateId: Int = 0,
        parameter1: Int = 0,
        parameter2: Int = 0
    ): Boolean {
        val payloadText = "${commandId},${stateId},${parameter1},${parameter2}"
        val checksumValue = calculateXorChecksumOfAscii(payloadText)
        val checksumHexUppercase = checksumValue.toString(16).uppercase().padStart(2, '0')

        val packetText = "\$${payloadText}*${checksumHexUppercase}\r\n"
        logInternal("MCU TX: ${packetText.replace("\r", "\\r").replace("\n", "\\n")}")

        return writeAsciiCommand(packetText)
    }

    private fun calculateXorChecksumOfAscii(payloadText: String): Int {
        var checksumValue = 0
        val asciiBytes = payloadText.toByteArray(Charsets.US_ASCII)
        for (singleByte in asciiBytes) {
            checksumValue = checksumValue xor (singleByte.toInt() and 0xFF)
        }
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
        logInternal("disconnectAndCloseGatt: $reason")

        try { connectedBluetoothGatt?.disconnect() } catch (_: Exception) { }
        try { connectedBluetoothGatt?.close() } catch (_: Exception) { }

        connectedBluetoothGatt = null
        subscribedNotifyCharacteristic = null
        discoveredWriteCharacteristic = null
        isConnecting = false
    }

    // ============================================================
    // ✅ 200ms flush (BLE_LOG + BLE_RX + JSON callback)
    // ============================================================

    private fun scheduleFlushIfNeeded() {
        if (isFlushScheduled) return
        isFlushScheduled = true
        mainHandler.postDelayed({ flushNow() }, flushIntervalMs)
    }

    private fun flushNow() {
        isFlushScheduled = false

        val cb = currentCallback ?: return

        // 1) 200ms 동안 쌓인 BLE 내부 로그는 "로그용 JSON"으로 묶어서 onLog로만 보냄
        val logsToFlush: List<String> = buildList {
            while (pendingBleLogs.isNotEmpty()) add(pendingBleLogs.removeFirst())
        }

        if (logsToFlush.isNotEmpty()) {
            val escaped = logsToFlush.map { it.replace("\\", "\\\\").replace("\"", "\\\"") }
            val payload = buildString {
                append("{\"type\":\"BLE_LOG\",\"count\":")
                append(escaped.size)
                append(",\"logs\":[")
                escaped.forEachIndexed { idx, s ->
                    if (idx > 0) append(",")
                    append("\"").append(s).append("\"")
                }
                append("]}")
            }
            // ✅ 로그는 onLog로만
            mainHandler.post { cb.onLog(payload) }
            android.util.Log.d("PeriK3_BLE", payload)
        }

        // 2) 200ms 동안 들어온 JSON 중 "마지막 1개"는 RAW 그대로 onJsonStringReceived로 보냄
        val lastJson = latestJsonPacket
        latestJsonPacket = null

        if (!lastJson.isNullOrBlank()) {
            // ✅ 여기 절대 래핑하지 말고 RAW JSON 그대로 전달
            mainHandler.post { cb.onJsonStringReceived(lastJson) }
        }
    }

    private fun buildJsonLog(type: String, logs: List<String>): String {
        // JSON 안전하게 escape
        fun esc(s: String): String {
            return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
        }

        val joined = logs.joinToString(separator = ",") { "\"${esc(it)}\"" }
        return "{\"type\":\"$type\",\"count\":${logs.size},\"logs\":[${joined}]}"
    }

    // ============================================================
    // ✅ 내부 로그는 pending에 쌓고 200ms flush로만 내보냄
    // ============================================================

    private fun logInternal(message: String) {
        pendingBleLogs.add(message)
        scheduleFlushIfNeeded()
    }

    companion object {
        private const val CCCD_UUID_STRING: String = "00002902-0000-1000-8000-00805f9b34fb"
        private val PERIK3_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val PERIK3_DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }
}
