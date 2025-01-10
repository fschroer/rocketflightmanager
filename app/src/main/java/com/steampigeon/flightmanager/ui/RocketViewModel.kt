package com.steampigeon.flightmanager.ui

import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.mutualmobile.composesensors.AccelerometerSensorState
import com.mutualmobile.composesensors.MagneticFieldSensorState
import com.steampigeon.flightmanager.BluetoothService
import com.steampigeon.flightmanager.data.DeployMode
import com.steampigeon.flightmanager.data.RocketState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.experimental.and
import kotlin.math.sqrt
import com.steampigeon.flightmanager.data.FlightStates
import com.steampigeon.flightmanager.data.LocatorConfig
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * [RocketViewModel] holds rocket locator status
 */

class RocketViewModel() : ViewModel() {
    companion object {
        const val SAMPLES_PER_SECOND = 20
        private const val ALTIMETER_SCALE = 10
        private const val ACCELEROMETER_SCALE = 2048
    }

    private val _azimuth = MutableStateFlow<Float>(0f)
    val azimuth: StateFlow<Float> = _azimuth.asStateFlow()
    private val _lastAzimuth = MutableStateFlow<Float>(0f)
    val averageAzimuth: StateFlow<Float> = _lastAzimuth.asStateFlow()
    private val _distanceToLocator = MutableStateFlow<Int>(0)
    val distanceToLocator: StateFlow<Int> = _distanceToLocator.asStateFlow()
    private val _azimuthToLocator = MutableStateFlow<Float>(0f)
    val azimuthToLocator: StateFlow<Float> = _azimuthToLocator.asStateFlow()

    /**
     * Display state
     */
    private val _rocketState = MutableStateFlow(RocketState())
    val rocketState: StateFlow<RocketState> = _rocketState.asStateFlow()

    private val _remoteLocatorConfig = MutableStateFlow<LocatorConfig>(LocatorConfig())
    val remoteLocatorConfig: StateFlow<LocatorConfig> = _remoteLocatorConfig.asStateFlow()

    fun collectLocatorData(service: BluetoothService) {
        viewModelScope.launch {
            service.data.collect { locatorMessage ->
                val currentTime = System.currentTimeMillis()
                _rocketState.update { currentState -> currentState.copy(lastMessageTime = currentTime) }
                if (locatorMessage.copyOfRange(0, 3)
                        .contentEquals(BluetoothService.prelaunchMessageHeader)
                ) {
                    val rawGForce = sqrt(
                        (_rocketState.value.accelerometer.x * _rocketState.value.accelerometer.x
                                + _rocketState.value.accelerometer.y * _rocketState.value.accelerometer.y
                                + _rocketState.value.accelerometer.z * _rocketState.value.accelerometer.z).toFloat()
                    )
                    _rocketState.update { currentState ->
                        currentState.copy(
                            latitude = gpsCoord(locatorMessage, 11),
                            longitude = gpsCoord(locatorMessage, 19),
                            qInd = (locatorMessage[27] - 48).toUByte(),
                            satellites = locatorMessage[28].toUByte(),
                            hdop = byteArrayToFloat(locatorMessage, 29),
                            altimeterStatus = (locatorMessage[40].and(8)
                                .toInt() ushr 3) == 1,
                            accelerometerStatus = (locatorMessage[40].and(4)
                                .toInt() ushr 2) == 1,
                            deployChannel1Armed = (locatorMessage[40].and(2)
                                .toInt() ushr 1) == 1,
                            deployChannel2Armed = (locatorMessage[40].and(1).toInt()) == 1,
                            altitudeAboveGroundLevel = byteArrayToUShort(
                                locatorMessage,
                                41
                            ).toFloat() / ALTIMETER_SCALE,
                            accelerometer = RocketState.Accelerometer(
                                byteArrayToShort(locatorMessage, 43),
                                byteArrayToShort(locatorMessage, 45),
                                byteArrayToShort(locatorMessage, 47)
                            ),
                            gForce = rawGForce / ACCELEROMETER_SCALE,
                            orientation =
                            when {
                                _rocketState.value.accelerometer.x.toFloat() / rawGForce < -0.5 -> "up"
                                _rocketState.value.accelerometer.x.toFloat() / rawGForce > 0.5 -> "down"
                                else -> "side"
                            },
                            batteryVoltage = byteArrayToUShort(locatorMessage, 71),
                        )
                    }
                    _remoteLocatorConfig.update { currentState ->
                        currentState.copy(
                            deployMode = DeployMode.fromUByte(locatorMessage[49].toUByte()),
                            launchDetectAltitude = byteArrayToUShort(locatorMessage, 50).toInt(),
                            droguePrimaryDeployDelay = locatorMessage[52].toInt(),
                            drogueBackupDeployDelay = locatorMessage[53].toInt(),
                            mainPrimaryDeployAltitude = byteArrayToUShort(locatorMessage, 54).toInt(),
                            mainBackupDeployAltitude = byteArrayToUShort(locatorMessage, 56).toInt(),
                            deploySignalDuration = locatorMessage[58].toInt(),
                            deviceName = String(
                                locatorMessage.copyOfRange(59, 71),
                                Charsets.UTF_8
                            ).trimEnd('\u0000'),
                        )
                    }
                } else if (locatorMessage.copyOfRange(0, 3)
                        .contentEquals(BluetoothService.telemetryMessageHeader)
                ) {
                    _rocketState.update { currentState ->
                        currentState.copy(
                            latitude = gpsCoord(locatorMessage, 11),
                            longitude = gpsCoord(locatorMessage, 19),
                            qInd = locatorMessage[27].toUByte(),
                            satellites = locatorMessage[28].toUByte(),
                            hdop = byteArrayToFloat(locatorMessage, 29),
                            flightState = FlightStates.fromUByte(locatorMessage[40].toUByte())
                                ?: currentState.flightState,
                        )
                    }
                    val inFlight = (_rocketState.value.flightState
                        ?: FlightStates.WaitingLaunch) > FlightStates.Launched && (_rocketState.value.flightState
                        ?: FlightStates.WaitingLaunch) < FlightStates.Landed
                    for (i in 0..(if (inFlight) SAMPLES_PER_SECOND else 1) - 1) {
                        _rocketState.value.agl[i] =
                            byteArrayToFloat(locatorMessage, 40 + i * 4) / ALTIMETER_SCALE
                    }
                }
            }
        }
    }

    fun gpsCoord(byteArray: ByteArray, offset: Int): Double {
        require(offset >= 0 && offset + 8 <= byteArray.size) { "Invalid offset or length" }
        val doubleByteArray = byteArray.copyOfRange(offset, offset + 8).reversedArray()
        val doubleValue = ByteBuffer.wrap(doubleByteArray).getDouble()
        return doubleValue.toInt() / 100 + (doubleValue - (doubleValue.toInt() / 100 * 100)) / 60
    }

    fun byteArrayToFloat(byteArray: ByteArray, offset: Int): Float {
        require(offset >= 0 && offset + 4 <= byteArray.size) { "Invalid offset or length" }
        val floatByteArray = byteArray.copyOfRange(offset, offset + 4).reversedArray()
        return ByteBuffer.wrap(floatByteArray).getFloat()
    }
    fun byteArrayToUShort(byteArray: ByteArray, offset: Int): UShort {
        require(offset >= 0 && offset + 2 <= byteArray.size) { "Invalid offset or length" }
        return (byteArray[offset].toUByte() + byteArray[offset + 1].toUByte() * 256u).toUShort()
    }
    fun byteArrayToShort(byteArray: ByteArray, offset: Int): Short {
        require(offset >= 0 && offset + 2 <= byteArray.size) { "Invalid offset or length" }
        return (byteArray[offset].toUByte() + byteArray[offset + 1].toUByte() * 256u).toShort()
    }

    fun handheldDeviceAzimuth(accelerometerState: AccelerometerSensorState, magneticFieldState: MagneticFieldSensorState) {
        val lastAccelerometer = FloatArray(3)
        lastAccelerometer[0] = accelerometerState.xForce
        lastAccelerometer[1] = accelerometerState.yForce
        lastAccelerometer[2] = accelerometerState.zForce
        val lastMagnetometer = FloatArray(3)
        lastMagnetometer[0] = magneticFieldState.xStrength
        lastMagnetometer[1] = magneticFieldState.yStrength
        lastMagnetometer[2] = magneticFieldState.zStrength
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrix(rotationMatrix, null, lastAccelerometer, lastMagnetometer)
        val rotationMatrixB = FloatArray(9)
        SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, rotationMatrixB)
        SensorManager.getOrientation(rotationMatrixB, orientation)
        _lastAzimuth.value = _azimuth.value
        _azimuth.value = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
    }

    fun locatorVector(latLng1: LatLng, latLng2: LatLng) {
        val earthRadius = 6371000 // in meters

        val lat1Rad = Math.toRadians(latLng1.latitude)
        val lat2Rad = Math.toRadians(latLng2.latitude)
        val dLat = lat2Rad - lat1Rad
        val dLon = Math.toRadians(latLng2.longitude - latLng1.longitude)

        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        _distanceToLocator.value = (earthRadius * c).toInt()

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        _azimuthToLocator.value = ((bearing + 360) % 360).toFloat()
    }

}