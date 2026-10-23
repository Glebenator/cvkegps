package com.example.cvkegps.tracking

import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes GPX 1.1. Each route line becomes one track segment, so gaps stay unconnected, as on the map.
 * Altitude is not written: Android reports height above the WGS84 ellipsoid, and GPX readers expect height above sea level.
 */
object Gpx {
    const val MIME_TYPE = "application/gpx+xml"
    private val fileTime = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)

    fun fileName(startedMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "cvke-trip-${fileTime.format(Instant.ofEpochMilli(startedMillis).atZone(zone))}.gpx"

    fun write(out: Writer, name: String, lines: List<List<TrackSample>>) {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write("<gpx version=\"1.1\" creator=\"CVKE GPS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        out.write("  <trk>\n    <name>${escape(name)}</name>\n")
        for (line in lines) {
            out.write("    <trkseg>\n")
            for (sample in line) {
                // Locale.ROOT keeps a decimal point in every language.
                val lat = String.format(Locale.ROOT, "%.7f", Math.toDegrees(sample.latitudeRadians))
                val lon = String.format(Locale.ROOT, "%.7f", Math.toDegrees(sample.longitudeRadians))
                out.write("      <trkpt lat=\"$lat\" lon=\"$lon\"><time>${Instant.ofEpochMilli(sample.timeMillis)}</time></trkpt>\n")
            }
            out.write("    </trkseg>\n")
        }
        out.write("  </trk>\n</gpx>\n")
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
