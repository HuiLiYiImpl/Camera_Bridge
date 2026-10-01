package info.skyblond.nsp.service

import info.skyblond.nsp.ble.GeoPayloadGenerator
import info.skyblond.nsp.data.PairedCamera
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.StateFlow

/** Coordinates and time are supplied by the location layer after quality checks. */
data class NikonLocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val timestamp: Instant
)

interface NikonLocationClient {
    val state: StateFlow<ConnectionState>
    fun connect(camera: PairedCamera)
    fun disconnect()
    fun updateLocation(location: NikonLocationFix)
}

/** Thin adapter around nsg's pairing/GATT state machine. No Android Location or UI types. */
class NsgNikonLocationClient(
    private val session: NikonPairingSession,
    override val state: StateFlow<ConnectionState>
) : NikonLocationClient {
    override fun connect(camera: PairedCamera) = session.connectToSavedCamera(camera)
    override fun disconnect() = session.disconnect()
    override fun updateLocation(location: NikonLocationFix) {
        session.writeGeo(
            GeoPayloadGenerator.build(
                location.latitude,
                location.longitude,
                location.altitudeMeters,
                location.timestamp.atZone(ZoneOffset.UTC)
            )
        )
    }
}
