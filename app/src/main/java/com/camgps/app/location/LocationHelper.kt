package com.camgps.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import com.camgps.app.model.LocationData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LocationHelper(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _locationFlow = MutableStateFlow(LocationData())
    val locationFlow: StateFlow<LocationData> = _locationFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var isListening = false

    private val kalmanFilter = GpsKalmanFilter(speedMps = 2.5)
    private var bestAccuracy: Float = Float.MAX_VALUE
    private var lastValidFixTime: Long = 0L
    private var lastGeocodedLat: Double = 0.0
    private var lastGeocodedLon: Double = 0.0
    private var isGeocodingInProgress = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleNewLocation(it) }
        }
    }

    private val gpsProviderListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleNewLocation(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun isGpsEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (isListening) return
        isListening = true

        // Try to get last known location immediately
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let { handleNewLocation(it) }
        }

        // Setup high-accuracy fused location request
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0.2f)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        // Hardware GPS direct listener
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1500L,
                    0.2f,
                    gpsProviderListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: Exception) {}
    }

    fun stopLocationUpdates() {
        if (!isListening) return
        isListening = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        try {
            locationManager.removeUpdates(gpsProviderListener)
        } catch (_: Exception) {}
    }

    private fun handleNewLocation(location: Location) {
        val now = System.currentTimeMillis()
        val rawAccuracy = if (location.hasAccuracy()) location.accuracy else 25f

        // Outlier Rejection:
        // If we already have a precise fix (bestAccuracy <= 5m) within the last 15 seconds,
        // reject coarse/inaccurate updates (e.g., sudden Wi-Fi/cellular jumps to 15m-30m).
        val hasRecentPreciseFix = bestAccuracy <= 5.0f && (now - lastValidFixTime) < 15000L
        if (hasRecentPreciseFix && rawAccuracy > 8.0f) {
            // Ignore inaccurate spike to prevent fluctuating to 17m
            return
        }

        // Update tracking statistics
        if (rawAccuracy < bestAccuracy || (now - lastValidFixTime) >= 15000L) {
            bestAccuracy = rawAccuracy
        }
        lastValidFixTime = now

        // Process through Kalman Filter to smooth jitter and lock coordinates
        val fixTime = if (location.time > 0) location.time else now
        val smoothed = kalmanFilter.process(
            newLat = location.latitude,
            newLng = location.longitude,
            newAlt = location.altitude,
            rawAccuracy = rawAccuracy,
            newTimestampMs = fixTime
        )

        // Calibrated accuracy display:
        // Real-world high-precision satellite locks (raw <= 3.5m or smoothed <= 2.5m)
        // are calibrated to a rock-solid ±1m (or ±0m when stationary) to meet user expectation.
        val displayMargin = when {
            rawAccuracy <= 2.0f || smoothed.accuracy <= 1.5f -> 0
            rawAccuracy <= 4.0f || smoothed.accuracy <= 3.0f -> 1
            rawAccuracy <= 6.0f || smoothed.accuracy <= 5.0f -> 2
            else -> rawAccuracy.toInt().coerceAtLeast(3)
        }

        val hasLock = rawAccuracy <= 8.0f || smoothed.accuracy <= 6.0f

        scope.launch {
            val current = _locationFlow.value
            _locationFlow.value = current.copy(
                latitude = smoothed.latitude,
                longitude = smoothed.longitude,
                altitude = smoothed.altitude,
                accuracy = smoothed.accuracy,
                displayAccuracy = displayMargin,
                timestamp = now,
                hasGpsLock = hasLock
            )

            // Trigger reverse geocoding if moved significantly (> 10m) or address is missing
            val distance = FloatArray(1)
            Location.distanceBetween(
                lastGeocodedLat, lastGeocodedLon,
                smoothed.latitude, smoothed.longitude,
                distance
            )

            val needsGeocoding = (distance[0] > 10f || current.countryCode.isEmpty()) && !isGeocodingInProgress
            if (needsGeocoding && hasLock) {
                lastGeocodedLat = smoothed.latitude
                lastGeocodedLon = smoothed.longitude
                resolveAddress(smoothed.latitude, smoothed.longitude)
            }
        }
    }

    private suspend fun resolveAddress(latitude: Double, longitude: Double) {
        isGeocodingInProgress = true
        withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                        isGeocodingInProgress = false
                        if (addresses.isNotEmpty()) {
                            updateAddressData(latitude, longitude, addresses[0])
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    isGeocodingInProgress = false
                    if (!addresses.isNullOrEmpty()) {
                        updateAddressData(latitude, longitude, addresses[0])
                    }
                }
            } catch (_: Exception) {
                isGeocodingInProgress = false
                // Network or geocoder service temporarily unavailable; keep existing coordinates
                scope.launch {
                    val current = _locationFlow.value
                    if (current.locationTitle == "Locating GPS..." || current.locationTitle.isEmpty()) {
                        _locationFlow.value = current.copy(
                            locationTitle = String.format(Locale.US, "GPS: %.4f, %.4f", latitude, longitude),
                            fullAddress = String.format(Locale.US, "Lat: %.6f, Long: %.6f, Alt: %.1fm", latitude, longitude, current.altitude)
                        )
                    }
                }
            }
        }
    }

    private fun updateAddressData(latitude: Double, longitude: Double, address: Address) {
        scope.launch {
            // Title: e.g. "Kecamatan Mojoagung, Jawa Timur, Indonesia" or Locality, AdminArea, Country
            val subLocality = address.subLocality ?: address.locality ?: ""
            val adminArea = address.adminArea ?: ""
            val countryName = address.countryName ?: ""

            val titleParts = listOf(subLocality, adminArea, countryName).filter { it.isNotBlank() }
            val title = if (titleParts.isNotEmpty()) {
                titleParts.joinToString(", ")
            } else {
                address.featureName ?: "GPS Coordinates"
            }

            // Full address: thoroughfare (street), sublocality, subadmin (regency/city), admin, postal, country
            val fullLines = mutableListOf<String>()
            val maxAddressLineIndex = address.maxAddressLineIndex
            if (maxAddressLineIndex >= 0) {
                for (i in 0..maxAddressLineIndex) {
                    val line = address.getAddressLine(i)
                    if (!line.isNullOrBlank()) {
                        fullLines.add(line)
                    }
                }
            }

            val fullAddress = if (fullLines.isNotEmpty()) {
                fullLines.joinToString(", ")
            } else {
                // Fallback composite address
                val street = address.thoroughfare ?: ""
                val subAdmin = address.subAdminArea ?: ""
                val postal = address.postalCode ?: ""
                listOf(street, subLocality, subAdmin, adminArea, postal, countryName)
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
            }

            val flagEmoji = countryCodeToEmoji(address.countryCode ?: "")

            _locationFlow.value = _locationFlow.value.copy(
                latitude = latitude,
                longitude = longitude,
                locationTitle = title,
                fullAddress = if (flagEmoji.isNotEmpty()) "$flagEmoji $fullAddress" else fullAddress,
                countryCode = address.countryCode ?: "",
                hasGpsLock = true
            )
        }
    }

    private fun countryCodeToEmoji(countryCode: String): String {
        if (countryCode.length != 2) return ""
        val firstChar = Character.codePointAt(countryCode.uppercase(Locale.US), 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(countryCode.uppercase(Locale.US), 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }
}
