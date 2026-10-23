package com.example.cvkegps.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The horizon has radius 1. The point overhead has the zenith radius. */
data class SkyPosition(val x: Double, val y: Double)

/**
 * Angles are in radians. Omit invalid angles and satellites below the horizon.
 * A zenith radius above zero keeps the centre clear; radius still changes in direct proportion to elevation.
 */
fun skyPosition(
    azimuthRadians: Double,
    elevationRadians: Double,
    headingRadians: Double = 0.0,
    zenithRadius: Double = 0.0,
): SkyPosition? {
    if (!azimuthRadians.isFinite() || azimuthRadians !in 0.0..(2 * PI) ||
        !elevationRadians.isFinite() || elevationRadians !in 0.0..(PI / 2) || !headingRadians.isFinite() ||
        !zenithRadius.isFinite() || zenithRadius < 0.0 || zenithRadius >= 1.0
    ) return null
    val radius = zenithRadius + (1 - zenithRadius) * (1 - elevationRadians / (PI / 2))
    val angle = azimuthRadians - headingRadians
    return SkyPosition(sin(angle) * radius, -cos(angle) * radius)
}

fun normalizedAzimuth(radians: Double): Double = ((radians % (2 * PI)) + 2 * PI) % (2 * PI)

/** The smaller angle between two directions, from 0 to pi. */
fun angularDistance(a: Double, b: Double): Double = abs(normalizedAzimuth(a - b + PI) - PI)

/** East declination is positive. Satellite azimuth uses true north. */
fun trueHeading(magneticRadians: Double, declinationRadians: Double): Double? =
    if (magneticRadians.isFinite() && declinationRadians.isFinite())
        normalizedAzimuth(magneticRadians + declinationRadians) else null

/** Whole degrees from 0 to 359. A value that rounds to 360 is north. */
fun wholeDegrees(radians: Double): Int = Math.toDegrees(normalizedAzimuth(radians)).roundToInt() % 360
