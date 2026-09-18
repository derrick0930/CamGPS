package com.camgps.app.location

import kotlin.math.sqrt

/**
 * 1D/Decoupled Kalman Filter for smoothing GPS coordinates (Latitude, Longitude, Altitude).
 * Filters out high-frequency satellite noise and multipath jitter while preserving real movement.
 */
class GpsKalmanFilter(
    private val speedMps: Double = 3.0 // Assumed average motion speed in meters per second
) {
    private var timestampMs: Long = 0
    var latitude: Double = 0.0
        private set
    var longitude: Double = 0.0
        private set
    var altitude: Double = 0.0
        private set
    var accuracy: Float = 0f
        private set
    private var variance: Double = -1.0 // Negative indicates uninitialized

    val isInitialized: Boolean
        get() = variance >= 0

    fun reset() {
        variance = -1.0
        timestampMs = 0
        latitude = 0.0
        longitude = 0.0
        altitude = 0.0
        accuracy = 0f
    }

    /**
     * Updates the filter with a new raw location measurement.
     * Returns smoothed [latitude, longitude, altitude, smoothedAccuracy].
     */
    fun process(
        newLat: Double,
        newLng: Double,
        newAlt: Double,
        rawAccuracy: Float,
        newTimestampMs: Long
    ): SmoothedResult {
        val safeAccuracy = rawAccuracy.coerceAtLeast(0.5f)

        if (variance < 0) {
            // First fix: initialize filter
            timestampMs = newTimestampMs
            latitude = newLat
            longitude = newLng
            altitude = newAlt
            accuracy = safeAccuracy
            variance = (safeAccuracy * safeAccuracy).toDouble()
            return SmoothedResult(latitude, longitude, altitude, accuracy)
        }

        val deltaSeconds = ((newTimestampMs - timestampMs).coerceAtLeast(0)) / 1000.0
        timestampMs = newTimestampMs

        if (deltaSeconds > 0) {
            // Process noise increases uncertainty over time
            variance += deltaSeconds * speedMps * speedMps
        }

        // Measurement noise
        val measurementVariance = (safeAccuracy * safeAccuracy).toDouble()

        // Kalman gain
        val kalmanGain = variance / (variance + measurementVariance)

        // State update
        latitude += kalmanGain * (newLat - latitude)
        longitude += kalmanGain * (newLng - longitude)
        altitude += kalmanGain * (newAlt - altitude)

        // Variance update
        variance = (1.0 - kalmanGain) * variance
        accuracy = sqrt(variance).toFloat().coerceAtLeast(0.5f)

        return SmoothedResult(latitude, longitude, altitude, accuracy)
    }

    data class SmoothedResult(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float
    )
}
