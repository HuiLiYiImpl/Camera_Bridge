package com.yaoyihan.nikonconnect

enum class RemoteCapturePhase { IDLE, COUNTDOWN, PREPARING, WAITING, CONFIRMED, UNKNOWN, FAILED }

data class RemoteCaptureState(
    val phase: RemoteCapturePhase = RemoteCapturePhase.IDLE,
    val secondsRemaining: Int = 0,
    val message: String = "连接相机 Wi-Fi 后即可拍摄",
) {
    val inProgress: Boolean get() = phase == RemoteCapturePhase.COUNTDOWN ||
        phase == RemoteCapturePhase.PREPARING || phase == RemoteCapturePhase.WAITING
}

data class RemoteCaptureResult(val confirmed: Boolean, val message: String)

/** Implemented only by transports that can submit a shutter command. */
interface RemoteCameraControl {
    val supportsRemoteCapture: Boolean
    val supportsLiveView: Boolean
    val supportsTouchFocus: Boolean
    fun startLiveView()
    fun stopLiveView()
    fun exitRemoteControl() { stopLiveView() }
    fun liveViewFrame(): CameraLiveViewFrame
    fun focusAt(point: NormalizedFocusPoint, geometry: LiveViewFocusGeometry, isCancelled: () -> Boolean): RemoteFocusResult
    fun shootingParameters(): CameraShootingParameters
    fun parameterDescriptor(parameter: RemoteParameter): CameraParameterDescriptor
    fun setParameter(parameter: RemoteParameter, value: Long): CameraShootingParameters
    fun capture(isCancelled: () -> Boolean, onSending: () -> Unit): RemoteCaptureResult
}

data class CameraShootingParameters(
    val shutter: String? = null,
    val aperture: String? = null,
    val iso: String? = null,
    val mode: String? = null,
    val focalLength: String? = null,
    val battery: Int? = null,
    val compensation: String? = null,
    val whiteBalance: String? = null,
    val pictureControl: String? = null,
)

data class RemoteParameterEditor(
    val parameter: RemoteParameter? = null,
    val descriptor: CameraParameterDescriptor? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val busy: Boolean get() = loading || saving
}

data class RemoteLiveViewState(
    val frame: android.graphics.Bitmap? = null,
    val focusGeometry: LiveViewFocusGeometry? = null,
    val parameters: CameraShootingParameters = CameraShootingParameters(),
    val streaming: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val message: String = "连接相机后显示实时画面",
)

internal class LiveViewImageException(message: String) : java.io.IOException(message)

internal class PtpResponseException(val operation: Int, val response: Int) : java.io.IOException(
    when (response) {
        0x2005 -> "当前连接不支持此操作"
        0x200F -> "相机拒绝遥控，请确认相机处于照片拍摄状态"
        0x2019 -> "相机正忙，请稍后再拍摄"
        0x201D, 0x2006 -> "相机不支持当前拍摄参数"
        0xA004 -> "相机状态不允许拍摄，请检查拍摄模式和对焦"
        else -> "相机未接受操作"
    } + "（操作 0x${operation.toString(16)}，响应 0x${response.toString(16)}）"
)
