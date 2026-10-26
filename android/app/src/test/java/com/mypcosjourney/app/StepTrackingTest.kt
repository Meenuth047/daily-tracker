package com.mypcosjourney.app

import org.junit.Assert.*
import org.junit.Test

class StepTrackingTest {

    // Helper simulating the StepCounterPlugin algorithm
    class StepTrackerCore {
        var todayDate: String = "2026-10-26"
        var baselineRaw: Float = 0f
        var lastSeenRaw: Float = -1f
        var rebootOffset: Float = 0f

        // Session
        var sessionActive: Boolean = false
        var sessionType: String = "lunch"
        var sessionStartRaw: Float = 0f
        var sessionPaused: Boolean = false
        var sessionPauseStartRaw: Float = 0f
        var sessionPausedDelta: Float = 0f

        val historyDailySteps = mutableMapOf<String, Int>()

        fun onSensorReading(newDate: String, rawSteps: Float) {
            // Check reboot
            if (lastSeenRaw >= 0 && rawSteps < lastSeenRaw) {
                val stepsBeforeReboot = maxOf(0f, lastSeenRaw - baselineRaw)
                rebootOffset += stepsBeforeReboot
                baselineRaw = 0f
            }

            // Check midnight rollover
            if (newDate != todayDate) {
                // Archive previous day's steps
                val prevSteps = computeTodaySteps(if (lastSeenRaw >= 0) lastSeenRaw else rawSteps)
                historyDailySteps[todayDate] = prevSteps

                // Reset for new day
                todayDate = newDate
                baselineRaw = rawSteps
                rebootOffset = 0f
            } else if (lastSeenRaw < 0) {
                // Initial baseline
                baselineRaw = rawSteps
            }

            lastSeenRaw = rawSteps
        }

        fun computeTodaySteps(currentRaw: Float): Int {
            val rawDelta = if (currentRaw >= baselineRaw) currentRaw - baselineRaw else currentRaw
            return maxOf(0, (rawDelta + rebootOffset).toInt())
        }

        fun startSession(type: String) {
            sessionActive = true
            sessionType = type
            sessionStartRaw = if (lastSeenRaw >= 0) lastSeenRaw else 0f
            sessionPaused = false
            sessionPauseStartRaw = 0f
            sessionPausedDelta = 0f
        }

        fun pauseSession() {
            if (sessionActive && !sessionPaused) {
                sessionPaused = true
                sessionPauseStartRaw = lastSeenRaw
            }
        }

        fun resumeSession() {
            if (sessionActive && sessionPaused) {
                val delta = if (lastSeenRaw >= sessionPauseStartRaw) lastSeenRaw - sessionPauseStartRaw else 0f
                sessionPausedDelta += delta
                sessionPaused = false
            }
        }

        fun stopSession(): Int {
            if (!sessionActive) return 0
            var pausedDelta = sessionPausedDelta
            if (sessionPaused) {
                val pauseDelta = if (lastSeenRaw >= sessionPauseStartRaw) lastSeenRaw - sessionPauseStartRaw else 0f
                pausedDelta += pauseDelta
            }
            val rawDelta = if (lastSeenRaw >= sessionStartRaw) lastSeenRaw - sessionStartRaw else lastSeenRaw
            val sessionSteps = maxOf(0, (rawDelta - pausedDelta).toInt())
            sessionActive = false
            sessionPaused = false
            return sessionSteps
        }
    }

    @Test
    fun testNormalDailyStepCounting() {
        val tracker = StepTrackerCore()
        // Morning starts with sensor at 50,000 (steps since last reboot)
        tracker.onSensorReading("2026-10-26", 50000f)
        assertEquals(0, tracker.computeTodaySteps(50000f))

        // Walks 3,500 steps
        tracker.onSensorReading("2026-10-26", 53500f)
        assertEquals(3500, tracker.computeTodaySteps(53500f))
    }

    @Test
    fun testMidnightRollover() {
        val tracker = StepTrackerCore()
        // Day 1
        tracker.onSensorReading("2026-10-26", 10000f)
        tracker.onSensorReading("2026-10-26", 18200f)
        assertEquals(8200, tracker.computeTodaySteps(18200f))

        // Midnight rollover to Day 2
        tracker.onSensorReading("2026-10-27", 18210f)
        // Day 1 should be archived with 8200 steps
        assertEquals(8200, tracker.historyDailySteps["2026-10-26"])
        // Day 2 steps should start from 0
        assertEquals(0, tracker.computeTodaySteps(18210f))

        // Day 2 walk 4,100 steps
        tracker.onSensorReading("2026-10-27", 22310f)
        assertEquals(4100, tracker.computeTodaySteps(22310f))
    }

    @Test
    fun testDeviceRebootCounterReset() {
        val tracker = StepTrackerCore()
        // Device was up, baseline 12,000
        tracker.onSensorReading("2026-10-26", 12000f)
        // User walks 3,000 steps (raw is 15,000)
        tracker.onSensorReading("2026-10-26", 15000f)
        assertEquals(3000, tracker.computeTodaySteps(15000f))

        // Phone reboots! Raw counter resets to 150 steps
        tracker.onSensorReading("2026-10-26", 150f)
        // The 3,000 steps walked before reboot should be preserved!
        // Total steps = 3,000 + 150 = 3,150
        assertEquals(3150, tracker.computeTodaySteps(150f))

        // User continues walking another 1,000 steps (raw reaches 1,150)
        tracker.onSensorReading("2026-10-26", 1150f)
        assertEquals(4150, tracker.computeTodaySteps(1150f))
    }

    @Test
    fun testWalkingSessionWithPauseResume() {
        val tracker = StepTrackerCore()
        tracker.onSensorReading("2026-10-26", 20000f)

        // Start lunch walk
        tracker.startSession("lunch")

        // Walks 1,200 steps
        tracker.onSensorReading("2026-10-26", 21200f)

        // Pauses walk (e.g. at traffic light or stop)
        tracker.pauseSession()

        // Walks 200 steps while paused (e.g. stepping inside a store, paused)
        tracker.onSensorReading("2026-10-26", 21400f)

        // Resumes walk
        tracker.resumeSession()

        // Walks another 800 steps
        tracker.onSensorReading("2026-10-26", 22200f)

        // Stop session
        val sessionSteps = tracker.stopSession()

        // Expected session steps = 1,200 + 800 = 2,000 steps (paused 200 excluded)
        assertEquals(2000, sessionSteps)

        // Daily steps should still be total 2,200 steps
        assertEquals(2200, tracker.computeTodaySteps(22200f))
    }

    @Test
    fun testSeparationOfSessionAndDailySteps() {
        val tracker = StepTrackerCore()
        tracker.onSensorReading("2026-10-26", 1000f)

        // User walks casually before lunch: 500 steps
        tracker.onSensorReading("2026-10-26", 1500f)

        // Lunch walk session: 1,800 steps
        tracker.startSession("lunch")
        tracker.onSensorReading("2026-10-26", 3300f)
        val lunchSteps = tracker.stopSession()
        assertEquals(1800, lunchSteps)

        // Casual afternoon steps: 700 steps
        tracker.onSensorReading("2026-10-26", 4000f)

        // Evening walk session: 2,100 steps
        tracker.startSession("evening")
        tracker.onSensorReading("2026-10-26", 6100f)
        val eveningSteps = tracker.stopSession()
        assertEquals(2100, eveningSteps)

        // Verify:
        // Lunch session steps: 1,800
        // Evening session steps: 2,100
        // Daily steps: exactly 5,100 (1000->6100)
        // No double-counting!
        assertEquals(1800, lunchSteps)
        assertEquals(2100, eveningSteps)
        assertEquals(5100, tracker.computeTodaySteps(6100f))
    }
}
