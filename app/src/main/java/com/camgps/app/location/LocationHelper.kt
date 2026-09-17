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
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0.5f)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        // Fallback / Hardware GPS direct listener
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    0.5f,
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
        scope.launch {
            // First update immediate coordinates so display refreshes with 0 delay
            val current = _locationFlow.value
            _locationFlow.value = current.copy(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracy = location.accuracy,
                timestamp = System.currentTimeMillis(),
                hasGpsLock = true
            )

            // Resolve reverse geocoded address asynchronously
            resolveAddress(location.latitude, location.longitude)
        }
    }

    private suspend fun resolveAddress(latitude: Double, longitude: Double) {
        withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                        if (addresses.isNotEmpty()) {
                            updateAddressData(latitude, longitude, addresses[0])
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        updateAddressData(latitude, longitude, addresses[0])
                    }
                }
            } catch (_: Exception) {
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
