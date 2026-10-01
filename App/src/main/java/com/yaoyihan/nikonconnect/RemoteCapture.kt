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
    fun capture(isCancelled: () -> Boolean, onSending: () -> Unit): RemoteCaptureResult
}

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
