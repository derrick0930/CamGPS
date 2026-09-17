package com.camgps.app.model

data class LocationData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    val locationTitle: String = "Locating GPS...",
    val fullAddress: String = "Acquiring satellite lock...",
    val countryCode: String = "",
    val hasGpsLock: Boolean = false
) {
    /**
     * Formats latitude and longitude with 6 decimal places, e.g.
     * "Lat -7.567445° Long 112.345482°"
     */
    val formattedCoordinates: String
        get() {
            return if (hasGpsLock) {
                String.format(java.util.Locale.US, "Lat %.6f° Long %.6f°", latitude, longitude)
            } else {
                "Lat --.------° Long --.------°"
            }
        }

    /**
     * Formatted date and time with timezone, e.g.
     * "Jumat, 19/06/2026 10:29 AM GMT +07:00"
     */
    fun getFormattedDateTime(timeMillis: Long = timestamp): String {
        val date = java.util.Date(timeMillis)
        val dayFormat = java.text.SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", java.util.Locale.getDefault())
        val tzFormat = java.text.SimpleDateFormat(" z", java.util.Locale.getDefault())
        
        val tz = java.util.TimeZone.getDefault()
        val offsetHours = tz.getOffset(timeMillis) / (1000 * 60 * 60)
        val offsetMinutes = Math.abs((tz.getOffset(timeMillis) / (1000 * 60)) % 60)
        val sign = if (offsetHours >= 0) "+" else "-"
        val gmtString = String.format(java.util.Locale.US, "GMT %s%02d:%02d", sign, Math.abs(offsetHours), offsetMinutes)

        return "${dayFormat.format(date)} $gmtString"
    }
}
