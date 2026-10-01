package com.yaoyihan.nikonconnect

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import info.skyblond.nsp.data.DiscoveredCamera
import info.skyblond.nsp.data.PairedCamera
import info.skyblond.nsp.service.CameraConnectionService
import info.skyblond.nsp.service.ConnectionState
import info.skyblond.nsp.service.GpsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class GpsSyncViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val bound: Boolean = false,
        val connection: ConnectionState = ConnectionState.Idle,
        val saved: List<PairedCamera> = emptyList(),
        val discovered: List<DiscoveredCamera> = emptyList(),
        val gps: GpsState = GpsState(),
        val logs: List<String> = emptyList(),
        val place: PlaceNameState = PlaceNameState()
    )

    private val mutable = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutable.asStateFlow()
    private val jobs = mutableListOf<Job>()
    private var service: CameraConnectionService? = null
    private var bound = false
    private val placeStore = PlaceNameStore(application)
    private var placeSettings = placeStore.read()
    private val placeResolver = TencentPlaceNames(application)
    private var placeJob: Job? = null
    private var placeVisible = false
    private var lastPlaceAttempt: Long? = null
    private data class CachedPlace(val lat: Double, val lng: Double, val address: String, val time: Long)
    private var cachedPlace: CachedPlace? = null
    private val placeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        viewModelScope.launch {
            val updated = placeStore.read()
            if (updated != placeSettings) {
                placeSettings = updated
                placeJob?.cancel()
                cachedPlace = null
                mutable.update { it.copy(place = PlaceNameState()) }
                resolvePlace()
            }
        }
    }

    init { placeStore.prefs.registerOnSharedPreferenceChangeListener(placeListener) }

    fun setPlaceVisible(visible: Boolean) {
        placeVisible = visible
        if (visible) resolvePlace() else {
            placeJob?.cancel()
            mutable.update { it.copy(place = it.place.copy(loading = false)) }
        }
    }

    private fun validPlaceFix(gps: GpsState): Boolean = gps.hasFix &&
        gps.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        gps.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
        gps.lastFixTime?.let { System.currentTimeMillis() - it in 0L..120_000L } == true

    private fun resolvePlace() {
        val settings = placeSettings
        if (!settings.enabled || settings.key.isBlank()) {
            placeJob?.cancel()
            mutable.update { it.copy(place = PlaceNameState(message = if (settings.enabled) "请在设置中填写腾讯地图 Key" else "可在设置中开启地点名称")) }
            return
        }
        if (!placeVisible) return
        val gps = mutable.value.gps
        if (!validPlaceFix(gps)) {
            placeJob?.cancel()
            mutable.update { it.copy(place = PlaceNameState(message = "等待有效定位后解析地点")) }
            return
        }
        val lat = gps.latitude!!
        val lng = gps.longitude!!
        val cache = cachedPlace
        val close = cache != null && nearby(lat, lng, cache.lat, cache.lng)
        if (close && SystemClock.elapsedRealtime() - cache!!.time < 600_000) {
            placeJob?.cancel()
            mutable.update { it.copy(place = PlaceNameState(address = cache.address, message = "腾讯地图 · 附近地点")) }
            return
        }
        if (!close && mutable.value.place.address != null) {
            mutable.update { it.copy(place = PlaceNameState(message = "位置已变化，等待刷新地点")) }
        }
        if (placeJob?.isActive == true) return
        placeJob = viewModelScope.launch {
            // Keep requests at least a minute apart, including failed attempts and settings changes.
            val wait = lastPlaceAttempt?.let { 60_000 - (SystemClock.elapsedRealtime() - it) } ?: 0
            if (wait > 0) delay(wait)
            val current = mutable.value.gps
            if (!placeVisible || !validPlaceFix(current) || settings != placeSettings) return@launch
            val requestLat = current.latitude!!
            val requestLng = current.longitude!!
            lastPlaceAttempt = SystemClock.elapsedRealtime()
            mutable.update { it.copy(place = PlaceNameState(message = "正在解析地点…", loading = true)) }
            try {
                val address = withContext(Dispatchers.IO) { placeResolver.resolve(requestLat, requestLng, settings.key) }
                cachedPlace = CachedPlace(requestLat, requestLng, address, SystemClock.elapsedRealtime())
                val latest = mutable.value.gps
                val stillNearby = validPlaceFix(latest) && nearby(requestLat, requestLng, latest.latitude!!, latest.longitude!!)
                mutable.update { it.copy(place = if (stillNearby) PlaceNameState(address, "腾讯地图 · 附近地点") else PlaceNameState(message = "位置已变化，等待刷新地点")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // Never expose request URLs or API keys in the UI or diagnostic logs.
                mutable.update { it.copy(place = PlaceNameState(message = (error as? PlaceNameException)?.message ?: "地点解析失败，请检查网络后重试")) }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = (binder as? CameraConnectionService.LocalBinder)?.service ?: return
            service = connected
            mutable.update { it.copy(bound = true) }
            jobs.forEach { it.cancel() }
            jobs.clear()
            jobs += connected.state.onEach { v -> mutable.update { it.copy(connection = v) } }.launchIn(viewModelScope)
            jobs += connected.savedCameras.onEach { v -> mutable.update { it.copy(saved = v) } }.launchIn(viewModelScope)
            jobs += connected.discoveredCameras.onEach { v -> mutable.update { it.copy(discovered = v) } }.launchIn(viewModelScope)
            jobs += connected.gpsState.onEach { v ->
                mutable.update { it.copy(gps = v) }
                resolvePlace()
            }.launchIn(viewModelScope)
            jobs += connected.events.onEach { message ->
                mutable.update { it.copy(logs = (listOf(message) + it.logs).take(100)) }
            }.launchIn(viewModelScope)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            jobs.forEach { it.cancel() }
            jobs.clear()
            mutable.update { it.copy(bound = false) }
        }
    }

    fun bind() {
        if (bound) return
        val context = getApplication<Application>()
        bound = context.bindService(Intent(context, CameraConnectionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun startService() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, Intent(context, CameraConnectionService::class.java))
    }

    fun scan() {
        startService()
        service?.startPairingScan()
    }

    fun pair(camera: DiscoveredCamera) {
        startService()
        service?.selectDiscoveredCamera(camera)
    }

    fun connect(camera: PairedCamera) {
        startService()
        service?.connectToSavedCamera(camera)
    }

    fun recoverCameraId(camera: PairedCamera) {
        startService()
        service?.startAutoExtract(camera)
    }

    fun stop() = service?.disconnect()

    override fun onCleared() {
        placeJob?.cancel()
        placeStore.prefs.unregisterOnSharedPreferenceChangeListener(placeListener)
        jobs.forEach { it.cancel() }
        if (bound) getApplication<Application>().unbindService(connection)
        bound = false
        super.onCleared()
    }
}
