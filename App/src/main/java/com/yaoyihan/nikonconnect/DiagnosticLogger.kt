package com.yaoyihan.nikonconnect

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagnosticState(
    val diagnosticId: String? = null,
    val lastError: String? = null,
    val lastEvent: String? = null,
    val lastErrorCode: String? = null,
    val flapping: Boolean = false,
)

data class DiagnosticUsbSnapshot(
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val interfaceCount: Int,
    val serialHash: String?,
    val permissionGranted: Boolean,
    val mtpCapable: Boolean,
    val mtpSessionOpen: Boolean,
) {
    fun toJson() = JSONObject().apply {
        put("vendorId", vendorId)
        put("productId", productId)
        put("deviceClass", deviceClass)
        put("deviceSubclass", deviceSubclass)
        put("deviceProtocol", deviceProtocol)
        put("interfaceCount", interfaceCount)
        put("serialHash", serialHash ?: JSONObject.NULL)
        put("permissionGranted", permissionGranted)
        put("mtpCapable", mtpCapable)
        put("mtpSessionOpen", mtpSessionOpen)
    }
}

data class DiagnosticNetworkSnapshot(
    val ssidMasked: String?,
    val boundToApp: Boolean,
    val wifiTransport: Boolean,
) {
    fun toJson() = JSONObject().apply {
        put("ssid", ssidMasked ?: JSONObject.NULL)
        put("boundToApp", boundToApp)
        put("wifiTransport", wifiTransport)
    }
}

data class DiagnosticSnapshot(
    val transport: String?,
    val cameraBrand: String?,
    val cameraModel: String?,
    val network: DiagnosticNetworkSnapshot?,
    val usb: DiagnosticUsbSnapshot?,
    val mtpSessionState: String,
) {
    fun toJson() = JSONObject().apply {
        put("transport", transport ?: JSONObject.NULL)
        put("cameraBrand", cameraBrand ?: JSONObject.NULL)
        put("cameraModel", cameraModel ?: JSONObject.NULL)
        put("network", network?.toJson() ?: JSONObject.NULL)
        put("usb", usb?.toJson() ?: JSONObject.NULL)
        put("mtpSessionState", mtpSessionState)
        put("app", appJson())
    }
}

data class DiagnosticEventInput(
    val event: String,
    val connectionPhase: String,
    val transport: String? = null,
    val cameraBrand: String? = null,
    val cameraModel: String? = null,
    val level: String = "INFO",
    val result: String = "SUCCESS",
    val errorCode: String? = null,
    val message: String = "",
    val durationMs: Long? = null,
    val network: DiagnosticNetworkSnapshot? = null,
    val usb: DiagnosticUsbSnapshot? = null,
    val error: Throwable? = null,
)

/** App-private structured diagnostics. Logging failures are intentionally swallowed. */
class DiagnosticLogger(private val context: Context) : AutoCloseable {
    private val lock = Any()
    private val root = context.filesDir.resolve("diagnostics")
    private val eventsFile = root.resolve("events.jsonl")
    private val latestErrorFile = root.resolve("latest_error.json")
    private val sessionId = UUID.randomUUID().toString().replace("-", "").take(6)
    private val pending = ArrayDeque<String>()
    private var latestErrorJson: String? = null
    private var lastUsbConnected: Boolean? = null
    private val reconnectTimes = ArrayDeque<Long>()
    private var flappingReportedAt = 0L
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "camera-bridge-diagnostics").apply { isDaemon = true }
    }
    private val _state = MutableStateFlow(readInitialState())
    val state = _state.asStateFlow()

    init {
        runCatching { root.mkdirs(); pruneLocked() }
        executor.scheduleWithFixedDelay(::flush, 5, 5, TimeUnit.SECONDS)
    }

    fun log(input: DiagnosticEventInput): String? = runCatching {
        val now = System.currentTimeMillis()
        val failed = input.level == "ERROR" || input.result == "FAILED" || input.event == "USB_CONNECTION_FLAPPING"
        val diagnosticId = if (failed) diagnosticId(input.transport) else _state.value.diagnosticId
        val event = eventJson(input, now, diagnosticId)
        synchronized(lock) {
            pending.addLast(event.toString())
            if (failed) {
                latestErrorJson = event.put("diagnosticId", diagnosticId ?: JSONObject.NULL).toString()
                _state.value = DiagnosticState(
                    diagnosticId = diagnosticId,
                    lastError = input.message.sanitize(),
                    lastEvent = input.event,
                    lastErrorCode = input.errorCode,
                    flapping = input.event == "USB_CONNECTION_FLAPPING" || _state.value.flapping,
                )
            }
        }
        if (failed) executor.execute(::flush)
        diagnosticId
    }.getOrNull()

    fun recordUsbConnectionState(connected: Boolean, snapshot: DiagnosticUsbSnapshot?) {
        val shouldReport = synchronized(lock) {
            val previous = lastUsbConnected
            if (previous == connected) return@synchronized false
            lastUsbConnected = connected
            val now = System.currentTimeMillis()
            while (reconnectTimes.peekFirst()?.let { now - it > FLAP_WINDOW_MS } == true) reconnectTimes.removeFirst()
            if (connected && previous == false) reconnectTimes.addLast(now)
            val report = reconnectTimes.size >= 2 && now - flappingReportedAt > FLAP_WINDOW_MS
            if (report) flappingReportedAt = now
            report
        }
        if (shouldReport) {
            log(DiagnosticEventInput(
                event = "USB_CONNECTION_FLAPPING",
                connectionPhase = "USB_UNSTABLE",
                transport = "USB",
                level = "ERROR",
                result = "DETECTED",
                message = "USB 连接在 60 秒内反复断开并重连",
                usb = snapshot,
            ))
        }
    }

    fun exportPackage(snapshot: DiagnosticSnapshot): File {
        flush()
        val exportRoot = context.cacheDir.resolve("diagnostics").apply { mkdirs() }
        val output = exportRoot.resolve("Camera_Bridge_Diagnostic_${timestampForFile()}.zip")
        val eventText = runCatching { eventsFile.takeIf(File::exists)?.readText() ?: "" }.getOrDefault("")
        val errorText = synchronized(lock) { latestErrorJson } ?: runCatching { latestErrorFile.takeIf(File::exists)?.readText() }.getOrNull() ?: JSONObject().put("error", JSONObject.NULL).toString()
        val summary = JSONObject().apply {
            put("generatedAt", nowIso())
            put("sessionId", sessionId)
            put("latestDiagnosticId", state.value.diagnosticId ?: JSONObject.NULL)
            put("latestError", state.value.lastError ?: JSONObject.NULL)
            put("latestEvent", state.value.lastEvent ?: JSONObject.NULL)
            put("usbConnectionFlapping", state.value.flapping)
            put("retention", JSONObject().put("maxBytes", MAX_BYTES).put("maxAgeDays", MAX_AGE_DAYS))
            put("eventsWindow", JSONObject().put("beforeMs", 60_000).put("afterMs", 30_000))
            put("eventBytes", eventText.toByteArray().size)
            put("app", appJson())
        }
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zipEntry(zip, "summary.json", summary.toString(2))
            zipEntry(zip, "events.jsonl", eventText)
            zipEntry(zip, "latest_error.json", errorText)
            zipEntry(zip, "device_snapshot.json", snapshot.toJson().toString(2))
        }
        return output
    }

    fun clear() = runCatching {
        synchronized(lock) {
            pending.clear()
            latestErrorJson = null
            reconnectTimes.clear()
            lastUsbConnected = null
            flappingReportedAt = 0L
            eventsFile.delete()
            latestErrorFile.delete()
            _state.value = DiagnosticState()
        }
    }

    fun copyText(): String {
        val current = state.value
        return buildString {
            append("诊断编号：").append(current.diagnosticId ?: "无").append('\n')
            append("最近事件：").append(current.lastEvent ?: "无").append('\n')
            append("错误信息：").append(current.lastError ?: "无").append('\n')
            current.lastErrorCode?.let { append("错误码：").append(it).append('\n') }
        }
    }

    override fun close() {
        runCatching { executor.shutdownNow() }
        flush()
    }

    private fun eventJson(input: DiagnosticEventInput, now: Long, diagnosticId: String?): JSONObject = JSONObject().apply {
        put("timestamp", nowIso(now))
        put("sessionId", sessionId)
        put("level", input.level)
        put("transport", input.transport ?: JSONObject.NULL)
        put("cameraBrand", input.cameraBrand ?: JSONObject.NULL)
        put("cameraModel", input.cameraModel ?: JSONObject.NULL)
        put("connectionPhase", input.connectionPhase)
        put("event", input.event)
        put("result", input.result)
        put("errorCode", input.errorCode ?: JSONObject.NULL)
        put("message", input.message.sanitize())
        put("durationMs", input.durationMs ?: JSONObject.NULL)
        put("network", input.network?.toJson() ?: JSONObject.NULL)
        put("usb", input.usb?.toJson() ?: JSONObject.NULL)
        put("app", appJson())
        diagnosticId?.let { put("diagnosticId", it) }
        input.error?.let { put("stackTrace", it.stackTraceText()) }
    }

    private fun flush() = runCatching {
        synchronized(lock) {
            if (pending.isEmpty() && latestErrorJson == null) {
                pruneLocked()
                return@synchronized
            }
            root.mkdirs()
            if (pending.isNotEmpty()) {
                eventsFile.appendText(pending.joinToString(separator = "", postfix = "") { "$it\n" })
                pending.clear()
            }
            latestErrorJson?.let {
                latestErrorFile.writeText(it)
                latestErrorJson = null
            }
            pruneLocked()
        }
    }

    private fun pruneLocked() {
        if (!eventsFile.exists()) return
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val kept = eventsFile.useLines { lines ->
            lines.filter { line ->
                runCatching {
                    val timestamp = JSONObject(line).optString("timestamp")
                    OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() >= cutoff
                }.getOrDefault(true)
            }.toList()
        }
        val joined = kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n")
        if (joined.toByteArray().size > MAX_BYTES) {
            val bytes = joined.toByteArray()
            val start = (bytes.size - MAX_BYTES).coerceAtLeast(0)
            val trimmed = String(bytes, start, bytes.size - start, Charsets.UTF_8).substringAfter('\n', "")
            eventsFile.writeText(trimmed)
        } else eventsFile.writeText(joined)
    }

    private fun readInitialState(): DiagnosticState = runCatching {
        if (!latestErrorFile.exists()) return@runCatching DiagnosticState()
        val json = JSONObject(latestErrorFile.readText())
        DiagnosticState(
            diagnosticId = json.optString("diagnosticId").takeIf(String::isNotBlank),
            lastError = json.optString("message").takeIf(String::isNotBlank),
            lastEvent = json.optString("event").takeIf(String::isNotBlank),
            lastErrorCode = json.optString("errorCode").takeIf(String::isNotBlank),
            flapping = json.optString("event") == "USB_CONNECTION_FLAPPING",
        )
    }.getOrDefault(DiagnosticState())

    private fun diagnosticId(transport: String?): String = "${transport?.takeIf { it.isNotBlank() } ?: "APP"}-${timestampForFile()}"

    private fun zipEntry(zip: ZipOutputStream, name: String, contents: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(contents.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun nowIso(time: Long = System.currentTimeMillis()): String = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(time), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    private fun timestampForFile(): String = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())

    private companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        const val MAX_AGE_DAYS = 7
        const val MAX_AGE_MS = MAX_AGE_DAYS * 24L * 60L * 60L * 1000L
        const val FLAP_WINDOW_MS = 60_000L
    }
}

fun diagnosticUsbSnapshot(device: UsbDevice?, permissionGranted: Boolean, mtpSessionOpen: Boolean = false): DiagnosticUsbSnapshot? {
    device ?: return null
    val serialHash = runCatching { device.serialNumber }.getOrNull()?.let(::sha256Short)
    val mtpCapable = device.deviceClass == android.hardware.usb.UsbConstants.USB_CLASS_STILL_IMAGE ||
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_STILL_IMAGE }
    return DiagnosticUsbSnapshot(
        vendorId = device.vendorId,
        productId = device.productId,
        deviceClass = device.deviceClass,
        deviceSubclass = device.deviceSubclass,
        deviceProtocol = device.deviceProtocol,
        interfaceCount = device.interfaceCount,
        serialHash = serialHash,
        permissionGranted = permissionGranted,
        mtpCapable = mtpCapable,
        mtpSessionOpen = mtpSessionOpen,
    )
}

fun diagnosticNetworkSnapshot(ssid: String?, boundToApp: Boolean): DiagnosticNetworkSnapshot = DiagnosticNetworkSnapshot(
    ssidMasked = ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }?.let { value ->
        if (value.length <= 4) "••••" else "${value.take(2)}…${value.takeLast(2)}"
    },
    boundToApp = boundToApp,
    wifiTransport = true,
)

private fun appJson() = JSONObject().apply {
    put("version", BuildConfig.VERSION_NAME)
    put("androidVersion", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT)
    put("device", listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim())
}

private fun String.sanitize(): String = replace(
    Regex("(?i)(password|token|authorization|bearer|secret)\\s*[:=]\\s*[^\\s,;]+"),
    "$1=[redacted]",
).replace(Regex("(?i)([A-Za-z]:\\\\|/storage/|/data/user/\\d+/)[^\\s]+"), "[path]").take(4_000)

private fun Throwable.stackTraceText(): String = StringWriter().also { writer -> printStackTrace(PrintWriter(writer)) }.toString().sanitize().take(8_000)

private fun sha256Short(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
