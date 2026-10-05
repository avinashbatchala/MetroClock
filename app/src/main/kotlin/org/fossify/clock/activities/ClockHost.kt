package org.fossify.clock.activities

/**
 * Host abstraction used by [org.fossify.clock.fragments.AlarmFragment] to refresh the clock
 * page after an alarm change. Both the legacy [MainActivity] and the Metro
 * [MetroClockActivity] implement it, so the alarm fragment is host-agnostic.
 */
interface ClockHost {
    fun updateClockTabAlarm()
}
