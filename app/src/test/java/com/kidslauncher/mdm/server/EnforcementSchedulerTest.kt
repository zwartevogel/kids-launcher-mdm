package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.AppRule
import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LOCAL-DEVIATION: the boundary maths behind [EnforcementScheduler]. Pure, so it is worth pinning
 * down here rather than discovering on a phone at 19:00.
 */
class EnforcementSchedulerTest {

    private fun at(hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.SEPTEMBER)
            set(Calendar.DAY_OF_MONTH, 15)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun hhmm(c: Calendar) =
        "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))

    @Test
    fun `picks the next window edge later today`() {
        val policy = PolicyResponse(
            weekdayStartMinutes = 14 * 60,
            weekdayEndMinutes = 19 * 60,
        )
        assertEquals("19:00", hhmm(EnforcementScheduler.nextBoundary(policy, at(15, 30))))
    }

    @Test
    fun `picks the earliest of several edges`() {
        val policy = PolicyResponse(
            weekdayStartMinutes = 14 * 60,
            weekdayEndMinutes = 19 * 60,
            bedtimeStartMinutes = 20 * 60 + 30,
            appRules = listOf(AppRule("com.example.game", startMinutes = 16 * 60)),
        )
        // 16:00 (the app's own window) comes before 19:00 and before bedtime.
        assertEquals("16:00", hhmm(EnforcementScheduler.nextBoundary(policy, at(15, 30))))
    }

    @Test
    fun `falls through to midnight when nothing is left today`() {
        val policy = PolicyResponse(
            weekdayStartMinutes = 14 * 60,
            weekdayEndMinutes = 19 * 60,
        )
        val next = EnforcementScheduler.nextBoundary(policy, at(23, 10))
        assertEquals("00:00", hhmm(next))
        // Tomorrow, not today - budgets and grants reset on the day boundary.
        assertEquals(16, next.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `a policy with no schedule at all still wakes at midnight`() {
        val next = EnforcementScheduler.nextBoundary(PolicyResponse(), at(10, 0))
        assertEquals("00:00", hhmm(next))
        assertEquals(16, next.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `a null policy is treated as no schedule rather than crashing`() {
        assertEquals("00:00", hhmm(EnforcementScheduler.nextBoundary(null, at(10, 0))))
    }
}
