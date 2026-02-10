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
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.UUID

class BleJsonClient(
    private val applicationContext: Context
) {

    interface Callback {
        fun onLog(logLine: String)
        fun onConnectionStateChanged(isConnected: Boolean)
        fun onJsonStringReceived(jsonString: String) // 정상 JSON만
    }

    // ============================================================
    // Scan/Connect 설정
    // ============================================================
    private val logLock = Any()
    private val allowedDeviceNamePrefix: String = "JDY"
    private val minimumAcceptableRssi: Int = -85
    private val shouldLogOnlyJdyDevices: Boolean = true
    private val scanTimeoutMillis: Long = 15_000L

    // UI로 전달하는 flush 주기
    private val flushIntervalMs: Long = 10L
    private val minFlushIntervalMs: Long = 20L

    // MTU/우선순위 요청 값
    private val desiredMtu: Int = 247

    // ============================================================
    // Thread/Handler
    // ============================================================
    private val mainHandler = Handler(Looper.getMainLooper())

    private val workerThread = HandlerThread("BleJsonClientWorker").apply {
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
            Log.e("PeriK3_BLE_RAW", "Worker crashed", e)
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

    // ============================================================
    // RX 버퍼 (worker thread only)
    // ============================================================
    private val rxBuffer = StringBuilder()

    // 정상 JSON 큐 (유실 방지). worker thread only
    private val validJsonQueue: ArrayDeque<String> = ArrayDeque()
    private val maxValidJsonQueued = 500

    // 로그도 묶어서 전달 (메인 post 폭주 방지)
    private val pendingLogs: ArrayDeque<String> = ArrayDeque()

    // 버퍼 폭주 방지
    private val maxRxBufferChars = 2000_000
    private val keepTailChars = 2000_000

    // RAW RX 디버그
    private val rawDumpBuffer = ArrayDeque<String>()
    private val maxRawDumpLinesPerFlush = 30
    private val debugDumpRawRxToLogcat: Boolean = true

    // ============================================================
    // ✅ 개선 1) notification마다 post 폭주 방지: ByteArray 큐 + drain 방식
    // ============================================================
    private val pendingByteChunks: ArrayDeque<ByteArray> = ArrayDeque()
    @Volatile private var drainScheduled = false

    // ============================================================
    // ✅ 개선 2) UTF-8 스트리밍 디코더 안정화 (REPLACE 모드 + carry)
    // ============================================================
    private val utf8Decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private var utf8CarryBytes: ByteArray = ByteArray(32)
    private var utf8CarryLen: Int = 0

    private fun decodeUtf8StreamingSafe(bytes: ByteArray): String {
        val merged = if (utf8CarryLen > 0) {
            ByteArray(utf8CarryLen + bytes.size).also {
                System.arraycopy(utf8CarryBytes, 0, it, 0, utf8CarryLen)
                System.arraycopy(bytes, 0, it, utf8CarryLen, bytes.size)
            }
        } else {
            bytes
        }

        // reset해도 OK: carry를 직접 붙여서 프레임 경계 문제를 해결함
        utf8Decoder.reset()

        val inBuf = ByteBuffer.wrap(merged)
        val outBuf = CharBuffer.allocate(merged.size)

        utf8Decoder.decode(inBuf, outBuf, false)

        // 남은 바이트(underflow)는 carry로 보관
        val remaining = inBuf.remaining()
        if (remaining > 0) {
            if (utf8CarryBytes.size < remaining) {
                utf8CarryBytes = ByteArray(maxOf(remaining, utf8CarryBytes.size * 2))
            }
            utf8CarryLen = remaining
            inBuf.get(utf8CarryBytes, 0, remaining)
        } else {
            utf8CarryLen = 0
        }

        outBuf.flip()
        return outBuf.toString()
    }

    private fun indexOfChar(sb: StringBuilder, ch: Char, fromIndex: Int = 0): Int {
        val start = if (fromIndex < 0) 0 else fromIndex
        for (i in start until sb.length) {
            if (sb[i] == ch) return i
        }
        return -1
    }

    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            enqueueLog("SCAN 타임아웃 → 중지")
            stopScanInternal()
        }
    }

    @Volatile private var flushLoopStarted = false
    private val flushRunnable = object : Runnable {
        override fun run() {
            flushOnceWorker()
            workerHandler.postDelayed(this, maxOf(flushIntervalMs, minFlushIntervalMs))
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
            workerHandler.postDelayed(flushRunnable, maxOf(flushIntervalMs, minFlushIntervalMs))
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

            enqueueLog("JDY 조건 일치 → 연결 시도: name=$deviceName addr=$deviceAddress rssi=$rssi")
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
                enqueueLog("GATT CONNECTED: name=$lastSelectedDeviceName addr=$lastSelectedDeviceAddress rssi=$lastSelectedDeviceRssi")
                safePostMain { currentCallback?.onConnectionStateChanged(true) }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        val prioOk = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        enqueueLog("requestConnectionPriority(HIGH) started=$prioOk")
                    } catch (e: Exception) {
                        enqueueLog("requestConnectionPriority 예외: ${e.message}")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        // PHY_OPTION_NO_PREFERRED 상수 없으면 0 쓰는게 맞음
                        gatt.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M,
                            BluetoothDevice.PHY_LE_2M,
                            0
                        )
                        enqueueLog("setPreferredPhy(2M) requested")
                    } catch (e: Exception) {
                        enqueueLog("setPreferredPhy 예외: ${e.message}")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        val mtuOk = gatt.requestMtu(desiredMtu)
                        enqueueLog("requestMtu($desiredMtu) started=$mtuOk")
                    } catch (e: Exception) {
                        enqueueLog("requestMtu 예외: ${e.message}")
                    }
                }

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
                enqueueLog("NOTIFY 가능한 Characteristic을 찾지 못했습니다.")
                return
            }

            subscribedNotifyCharacteristic = notifyChar
            discoveredWriteCharacteristic = notifyChar

            enqueueLog("NOTIFY 대상: service=${notifyChar.service.uuid} char=${notifyChar.uuid}")
            subscribeToNotifications(gatt, notifyChar)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            enqueueLog("onMtuChanged mtu=$mtu status=$status")
        }

        /*        @Deprecated("Android 12 이하 호환")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    characteristic.value?.let { handleIncomingBytes(it) }
                }*/

        /*   override fun onCharacteristicChanged(
               gatt: BluetoothGatt,
               characteristic: BluetoothGattCharacteristic,
               value: ByteArray
           ) {
               handleIncomingBytes(value)
           }*/
        // gattCallback 내부 수정
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray // 최신 API 기준
        ) {
            // 1. 바이트를 그대로 스트링으로 변환 (디코딩 실패 대비)
            val rawString = String(value, Charsets.UTF_8)

            // 2. 패킷의 길이와 내용을 즉시 로그로 출력
            // [RAW_RX] 표시를 붙여서 필터링하기 쉽게 합니다.
            Log.d("PeriK3_RAW", "Size: ${value.size} | Data: $rawString")

            // 기존의 worker 처리 로직 호출
            handleIncomingBytes(value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            enqueueLog("onDescriptorWrite status=$status desc=${descriptor.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enqueueLog("NOTIFY 구독 완료 -> sendGetStatus()")
                sendGetStatus()
            } else {
                enqueueLog("NOTIFY 구독 실패 status=$status")
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
            enqueueLog("CCCD descriptor(0x2902) 없음")
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
        // ✅ drain 방식: post 폭주 방지
        synchronized(pendingByteChunks) {
            pendingByteChunks.addLast(value.clone())
        }
        if (!drainScheduled) {
            drainScheduled = true
            workerHandler.post {
                drainScheduled = false
                drainIncomingWorker()
            }
        }
    }

    private fun drainIncomingWorker() {
        while (true) {
            val bytes: ByteArray = synchronized(pendingByteChunks) {
                if (pendingByteChunks.isEmpty()) return
                pendingByteChunks.removeFirst()
            }

            // ✅ 디코딩은 REPLACE 모드라 웬만하면 안전하게 문자열이 나옴
            val chunk = try {
                decodeUtf8StreamingSafe(bytes)
            } catch (e: Exception) {
                // ✅ 중요: 여기서 hex 문자열을 rxBuffer에 넣으면 파서가 망가진다.
                // 디버그 로그만 남기고 버림.
                enqueueLog("UTF-8 decode exception. drop chunk size=${bytes.size} err=${e.message}")
                continue
            }

            if (chunk.isEmpty()) continue

            // RAW debug line 저장(너무 길면 잘라서)
            synchronized(rawDumpBuffer) {
                rawDumpBuffer.addLast(if (chunk.length > 800) chunk.take(800) + "..." else chunk)
                while (rawDumpBuffer.size > 300) rawDumpBuffer.removeFirst()
            }

            rxBuffer.append(chunk)

            if (rxBuffer.length > maxRxBufferChars) {
                rxBuffer.delete(0, rxBuffer.length - keepTailChars)
                enqueueLog("RX buffer trimmed (too large)")
            }

            extractJsonObjectsFromBufferWorker()
        }
    }

    // ============================================================
    // JSON 파서 (좀비 자동 복구)
    // ============================================================
    private fun extractJsonObjectsFromBufferWorker() {
        var loopSafetyCount = 0
        val maxLoopsPerCall = 500

        val maxSingleJsonLength = 4096

        while (loopSafetyCount < maxLoopsPerCall) {
            loopSafetyCount++

            val start = indexOfChar(rxBuffer, '{', 0)

            if (start < 0) {
                // '{'가 아예 없으면 쓰레기만 쌓인 상태일 수 있음 → 꼬리만 유지
                if (rxBuffer.length > keepTailChars) {
                    rxBuffer.delete(0, rxBuffer.length - keepTailChars)
                    Log.w("PeriK3_BLE_RAW", "Garbage trimmed (No '{' found)")
                }
                return
            }

            if (start > 0) {
                // '{' 이전 쓰레기 제거
                rxBuffer.delete(0, start)
                continue
            }

            var depth = 0
            var inString = false
            var escape = false
            var endIndex = -1

            val limit = minOf(rxBuffer.length, maxSingleJsonLength + 1)
            for (i in 0 until limit) {
                val c = rxBuffer[i]
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

            // 닫힘이 없고 너무 길면 좀비로 보고 재동기화
            if (endIndex < 0 && rxBuffer.length > maxSingleJsonLength) {
                Log.e("PeriK3_BLE_RAW", "JSON Too Long/Corrupted. Drop '{' and resync.")
                rxBuffer.delete(0, 1)
                continue
            }

            if (endIndex < 0) return

            val json = rxBuffer.substring(0, endIndex + 1).trim()
            rxBuffer.delete(0, endIndex + 1)

            val ok = try {
                if (!json.startsWith("{") || !json.endsWith("}")) false
                else {
                    JSONObject(json)
                    true
                }
            } catch (_: Exception) {
                false
            }

            if (ok) {
                synchronized(validJsonQueue) {
                    validJsonQueue.addLast(json)
                    while (validJsonQueue.size > maxValidJsonQueued) {
                        validJsonQueue.removeFirst()
                    }
                }
                if (debugDumpRawRxToLogcat) {
                    Log.d("PeriK3_BLE_RAW", "JSON OK: ${json.take(120)}...")
                }
            } else {
                Log.w("PeriK3_BLE_RAW", "Broken JSON Skipped: ${json.take(120)}")
            }
        }
    }

    // ============================================================
    // flush (worker -> main)
    // ============================================================
    private fun flushOnceWorker() {
        val logs = mutableListOf<String>()
        val rawLines = mutableListOf<String>()

        synchronized(logLock) {
            while (pendingLogs.isNotEmpty()) logs.add(pendingLogs.removeFirst())
        }

        synchronized(rawDumpBuffer) {
            while (rawDumpBuffer.isNotEmpty() && rawLines.size < maxRawDumpLinesPerFlush) {
                rawLines.add(rawDumpBuffer.removeFirst())
            }
        }

        val jsonsToDeliver = mutableListOf<String>()
        synchronized(validJsonQueue) {
            // ✅ 메인 스레드 폭주 방지: 한 번에 너무 많이 보내지 않기
            val maxPerFlush = 10
            while (validJsonQueue.isNotEmpty() && jsonsToDeliver.size < maxPerFlush) {
                jsonsToDeliver.add(validJsonQueue.removeFirst())
            }
        }

        if (logs.isEmpty() && rawLines.isEmpty() && jsonsToDeliver.isEmpty()) return
        val cb = currentCallback ?: return

        safePostMain {
            try {
                if (logs.isNotEmpty()) {
                    cb.onLog(buildBleLogPayload(logs))
                }

                if (rawLines.isNotEmpty()) {
                    val joined = rawLines.joinToString("\n")
                    val safe = joined
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")

                    cb.onLog("{\"type\":\"BLE_RAW_DUMP\",\"lines\":${rawLines.size},\"data\":\"$safe\"}")
                }

                if (jsonsToDeliver.isNotEmpty()) {
                    for (j in jsonsToDeliver) {
                        cb.onJsonStringReceived(j)
                    }
                }
            } catch (e: Exception) {
                Log.e("PeriK3_BLE_RAW", "Callback error", e)
            }
        }
    }

    private fun buildBleLogPayload(logs: List<String>): String {
        fun esc(s: String) = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")

        val joined = logs.joinToString(",") { "\"${esc(it)}\"" }
        return "{\"type\":\"BLE_LOG\",\"count\":${logs.size},\"logs\":[${joined}]}"
    }

    private fun enqueueLog(msg: String) {
        val shortMsg = if (msg.length > 250) msg.take(250) + "..." else msg
        synchronized(logLock) {
            pendingLogs.addLast(shortMsg)
            while (pendingLogs.size > 200) pendingLogs.removeFirst()
        }
    }

    private fun safePostMain(block: () -> Unit) {
        try {
            mainHandler.post { block() }
        } catch (e: Exception) {
            Log.e("PeriK3_BLE_RAW", "main post failed", e)
        }
    }

    // ============================================================
    // TX
    // ============================================================
    @SuppressLint("MissingPermission")
    fun writeAsciiCommand(commandString: String): Boolean {
        val gatt = connectedBluetoothGatt ?: run {
            enqueueLog("WRITE 실패: GATT 없음(미연결)")
            return false
        }
        val ch = discoveredWriteCharacteristic ?: subscribedNotifyCharacteristic ?: run {
            enqueueLog("WRITE 실패: write characteristic 없음")
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
    fun sendCalibrate(): Boolean =
        sendMcuCommandPacket(
            commandId = 5,
            stateId = 3,
            parameter1 = 0,
            parameter2 = 0
        )
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

        // 수신 관련 상태도 리셋
        synchronized(pendingByteChunks) { pendingByteChunks.clear() }
        synchronized(validJsonQueue) { validJsonQueue.clear() }
        synchronized(rawDumpBuffer) { rawDumpBuffer.clear() }
        rxBuffer.clear()
        utf8CarryLen = 0
    }

    companion object {
        private const val CCCD_UUID_STRING: String = "00002902-0000-1000-8000-00805f9b34fb"
        private val PERIK3_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val PERIK3_DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }
}