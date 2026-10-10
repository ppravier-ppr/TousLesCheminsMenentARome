package com.example.everystreettracker.sync

import android.location.Location
import com.example.everystreettracker.data.db.TrackPointEntity
import kotlin.math.max
import kotlin.math.min

data class CleanResult(
    val cleanedPoints: List<TrackPointEntity>,
    val originalPointCount: Int,
    val cleanedPointCount: Int,
    val originalDistanceMeters: Double,
    val cleanedDistanceMeters: Double,
    val pointsRemoved: Int
)

object TrackCleaner {

    // Maximum human running/sprinting speed: 9.0 m/s ≈ 32.4 km/h
    const val MAX_HUMAN_SPEED_MPS = 9.0f

    // Minimum displacement between consecutive points to avoid stationary GPS jitter
    const val MIN_STEP_DISTANCE_METERS = 2.0

    // Threshold for unrecorded single jumps (in continuous pedestrian tracking, steps are 2-10m)
    const val MAX_VALID_SINGLE_STEP_METERS = 150.0

    /**
     * Cleans a list of track points to eradicate:
     * 1. Indoor building glitches & 1+ km triangle excursions (when GPS is lost inside a building
     *    for several minutes, cell-tower / network location produces a false fix far away,
     *    and then returns near the building).
     * 2. Aberrant points exceeding maximum human running speed (vehicles, teleports).
     * 3. Boundary errors at the start and end of tracks.
     * 4. Stationary micro-jitter (< 2.0 m).
     * 
     * Runs iteratively until complete convergence so that 100% of the cleaning
     * happens in a single call ("en un seul coup").
     */
    fun cleanTrackPoints(points: List<TrackPointEntity>, originalDistance: Double): CleanResult {
        if (points.size < 2) {
            return CleanResult(points, points.size, points.size, originalDistance, 0.0, 0)
        }

        // Sort by timestamp and discard empty/zero coordinates
        var current = points.sortedBy { it.timestamp }
            .filter { it.latitude != 0.0 && it.longitude != 0.0 }

        if (current.size < 2) {
            return CleanResult(current, points.size, current.size, originalDistance, 0.0, points.size - current.size)
        }

        var iteration = 0
        val maxIterations = 25

        // Iterative convergence loop: continues until no further points can be cleaned
        while (iteration < maxIterations) {
            iteration++
            val countBefore = current.size

            // Step A: Boundary trimming (spikes at the start or end of the track)
            current = trimBoundaries(current)

            // Step B: Outlier spike & triangle detector (catches high-speed spikes AND time-independent building glitches)
            current = removeSpikeExcursions(current)

            // Step C: Speed cap, unrecorded large leap validation, and micro-jitter filter
            current = filterSpeedAndJitter(current)

            if (current.size == countBefore) {
                // Fixed point achieved! Cleaned completely in one single execution
                break
            }
        }

        // Final realistic distance calculation
        val finalDistance = calculateRealisticDistance(current)

        return CleanResult(
            cleanedPoints = current,
            originalPointCount = points.size,
            cleanedPointCount = current.size,
            originalDistanceMeters = originalDistance,
            cleanedDistanceMeters = finalDistance,
            pointsRemoved = points.size - current.size
        )
    }

    /**
     * Trims initial or trailing GPS errors before satellite lock was acquired or after recording ended.
     */
    private fun trimBoundaries(points: List<TrackPointEntity>): List<TrackPointEntity> {
        if (points.size < 3) return points
        var startIdx = 0
        var endIdx = points.size - 1

        // Check start: if point 0 is an outlier relative to points 1, 2, 3
        while (startIdx < endIdx - 2) {
            val p0 = points[startIdx]
            val p1 = points[startIdx + 1]
            val p2 = points[startIdx + 2]
            val d01 = distanceBetween(p0.latitude, p0.longitude, p1.latitude, p1.longitude)
            val d12 = distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            val dt01 = max(1.0, (p1.timestamp - p0.timestamp) / 1000.0)

            if ((d01 > 80.0 || (d01 / dt01 > MAX_HUMAN_SPEED_MPS)) && d12 < 50.0) {
                startIdx++
            } else {
                break
            }
        }

        // Check end: if the last point is an outlier
        while (endIdx > startIdx + 2) {
            val plast = points[endIdx]
            val pprev = points[endIdx - 1]
            val pprev2 = points[endIdx - 2]
            val dLast = distanceBetween(pprev.latitude, pprev.longitude, plast.latitude, plast.longitude)
            val dPrev = distanceBetween(pprev2.latitude, pprev2.longitude, pprev.latitude, pprev.longitude)
            val dtLast = max(1.0, (plast.timestamp - pprev.timestamp) / 1000.0)

            if ((dLast > 80.0 || (dLast / dtLast > MAX_HUMAN_SPEED_MPS)) && dPrev < 50.0) {
                endIdx--
            } else {
                break
            }
        }

        return if (startIdx == 0 && endIdx == points.size - 1) {
            points
        } else {
            points.subList(startIdx, endIdx + 1)
        }
    }

    /**
     * Eliminates "triangle" spikes and excursions:
     * When a path departs from point A to a cluster of outlier points [p_i ... p_j] and then
     * returns to point B which is near A (or on the natural continuation from A).
     * 
     * Supports TWO detection modes:
     * 1. Impossible Speed Detour: excursion required impossible speed (> 32.4 km/h) to complete.
     * 2. Indoor Building Glitch (Time-Independent): GPS was lost inside a building for several
     *    minutes, so time elapsed makes speed look slow, but location leaped 70m-2000m away to
     *    a cell-tower / network position and came right back to the building (forming an acute needle).
     */
    private fun removeSpikeExcursions(points: List<TrackPointEntity>): List<TrackPointEntity> {
        val n = points.size
        if (n < 3) return points

        val toRemove = BooleanArray(n) { false }
        var i = 1

        while (i < n - 1) {
            if (toRemove[i]) {
                i++
                continue
            }

            val a = points[i - 1]
            var foundSpike = false
            var bestSpikeEnd = -1

            // Check excursion windows of length W from 1 up to min(60, n - 1 - i)
            // Testing larger windows first to catch clusters of glitch points
            val maxW = min(60, n - 1 - i)
            for (w in maxW downTo 1) {
                val j = i + w - 1
                val b = points[j + 1]

                val dtAB = max(1.0, (b.timestamp - a.timestamp) / 1000.0)
                val distAB = distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude)
                val directSpeed = distAB / dtAB

                val dAFirst = distanceBetween(a.latitude, a.longitude, points[i].latitude, points[i].longitude)
                val dLastB = distanceBetween(points[j].latitude, points[j].longitude, b.latitude, b.longitude)

                // Measure the detour through [i..j]
                var maxDistFromA = dAFirst
                var maxDistFromB = distanceBetween(b.latitude, b.longitude, points[i].latitude, points[i].longitude)
                var detourDist = dAFirst

                for (k in i until j) {
                    val step = distanceBetween(points[k].latitude, points[k].longitude, points[k + 1].latitude, points[k + 1].longitude)
                    detourDist += step
                    maxDistFromA = max(maxDistFromA, distanceBetween(a.latitude, a.longitude, points[k + 1].latitude, points[k + 1].longitude))
                    maxDistFromB = max(maxDistFromB, distanceBetween(b.latitude, b.longitude, points[k + 1].latitude, points[k + 1].longitude))
                }
                detourDist += dLastB

                val detourSpeed = detourDist / dtAB

                // Cosine of angle at apex using Law of Cosines:
                // cos(theta) = (dAFirst^2 + dLastB^2 - distAB^2) / (2 * dAFirst * dLastB)
                val cosTheta = if (dAFirst > 1.0 && dLastB > 1.0) {
                    val num = (dAFirst * dAFirst) + (dLastB * dLastB) - (distAB * distAB)
                    val denom = 2.0 * dAFirst * dLastB
                    (num / denom).coerceIn(-1.0, 1.0)
                } else {
                    -1.0
                }

                // -------------------------------------------------------------
                // DETECTOR 1: Impossible Speed Spike
                // -------------------------------------------------------------
                val isDetourSpeedImpossible = detourSpeed > MAX_HUMAN_SPEED_MPS
                val isDistantFromOrigin = maxDistFromA >= 35.0
                val isDirectFeasible = directSpeed <= MAX_HUMAN_SPEED_MPS
                val isHugeDetourRatio = detourDist > (2.5 * distAB) && maxDistFromA > (1.8 * distAB)

                val isSpeedSpike = isDetourSpeedImpossible && isDistantFromOrigin && (isDirectFeasible || isHugeDetourRatio)

                // -------------------------------------------------------------
                // DETECTOR 2: Indoor Building Glitch / Needle Spike (Time-Independent)
                // When in a building, minutes elapse, so detourSpeed might be < 9 m/s,
                // but location leaped 50m-2000m away to a cell tower and returned near A.
                // -------------------------------------------------------------
                val isJumpSignificant = (dAFirst >= 45.0 || dLastB >= 45.0) && maxDistFromA >= 45.0
                val isAcuteNeedle = cosTheta >= 0.35 // angle <= 69.5 degrees (sharp turn-back towards origin)
                val isDetourMassive = detourDist >= (2.0 * max(10.0, distAB))
                val isBaseSmall = distAB <= 400.0 || distAB <= (0.5 * maxDistFromA)

                val isBuildingGlitch = isJumpSignificant && isAcuteNeedle && isDetourMassive && isBaseSmall

                if (isSpeedSpike || isBuildingGlitch) {
                    foundSpike = true
                    bestSpikeEnd = j
                    break
                }
            }

            if (foundSpike && bestSpikeEnd != -1) {
                for (k in i..bestSpikeEnd) {
                    toRemove[k] = true
                }
                i = bestSpikeEnd + 1
            } else {
                i++
            }
        }

        val result = ArrayList<TrackPointEntity>(n)
        for (idx in 0 until n) {
            if (!toRemove[idx]) {
                result.add(points[idx])
            }
        }
        return result
    }

    /**
     * Filters out points exceeding human running speed, unrecorded massive leaps, and removes micro-jitter (< 2.0m).
     */
    private fun filterSpeedAndJitter(points: List<TrackPointEntity>): List<TrackPointEntity> {
        if (points.size < 2) return points

        val kept = ArrayList<TrackPointEntity>(points.size)
        var anchor: TrackPointEntity? = null

        for (idx in points.indices) {
            val pt = points[idx]

            // 1. Discard native recorded speed > 9.0 m/s
            if (pt.speed > MAX_HUMAN_SPEED_MPS) {
                continue
            }

            if (anchor == null) {
                anchor = pt
                kept.add(pt)
                continue
            }

            val dist = distanceBetween(anchor.latitude, anchor.longitude, pt.latitude, pt.longitude)

            // 2. Micro-jitter: skip tiny displacements (< 2.0m)
            if (dist < MIN_STEP_DISTANCE_METERS) {
                continue
            }

            val dt = max(1.0, (pt.timestamp - anchor.timestamp) / 1000.0)
            val impliedSpeed = dist / dt

            // In continuous pedestrian tracking, steps are 2-10m.
            // A single unrecorded step >= 150m indicates a GPS gap (building/tunnel) or teleportation.
            val isSingleStepTooLarge = dist >= MAX_VALID_SINGLE_STEP_METERS

            if (impliedSpeed <= MAX_HUMAN_SPEED_MPS && !isSingleStepTooLarge) {
                // Completely valid continuous human walking/running point
                kept.add(pt)
                anchor = pt
            } else {
                // Implied speed is too high (> 32.4 km/h) OR unrecorded single leap is too large (>= 150m).
                // Check if this is a genuine relocation where walking continues in the new area (e.g. took metro)
                if (isSustainedNewSegment(points, idx)) {
                    // Start new segment in the new area without drawing a line across the city
                    kept.add(pt)
                    anchor = pt
                }
                // Otherwise: isolated aberrant point or indoor false fix, drop it!
            }
        }

        return kept
    }

    /**
     * Checks if the point at startIndex is the start of a sustained, genuine movement segment
     * in a new location (at least 6 points with valid walking speed and >= 15m total movement).
     */
    private fun isSustainedNewSegment(points: List<TrackPointEntity>, startIndex: Int): Boolean {
        if (startIndex + 6 >= points.size) return false
        var current = points[startIndex]
        var totalDisplacement = 0.0

        for (offset in 1..6) {
            val next = points[startIndex + offset]
            val d = distanceBetween(current.latitude, current.longitude, next.latitude, next.longitude)
            val dt = max(1.0, (next.timestamp - current.timestamp) / 1000.0)
            if (d / dt > MAX_HUMAN_SPEED_MPS) {
                return false
            }
            totalDisplacement += d
            current = next
        }

        return totalDisplacement >= 15.0
    }

    /**
     * Calculates the true walking/running distance, only summing segments that are physically realistic.
     */
    fun calculateRealisticDistance(points: List<TrackPointEntity>): Double {
        if (points.size < 2) return 0.0
        var totalDist = 0.0

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val dist = distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            val dt = max(1.0, (p2.timestamp - p1.timestamp) / 1000.0)

            // Only add distance if segment is within human running speed and not a teleportation jump
            if (dist / dt <= MAX_HUMAN_SPEED_MPS && dist >= MIN_STEP_DISTANCE_METERS && dist < MAX_VALID_SINGLE_STEP_METERS) {
                totalDist += dist
            }
        }

        return totalDist
    }

    fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }
}
