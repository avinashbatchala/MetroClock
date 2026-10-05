package org.fossify.clock.extensions

import android.content.Context
import org.fossify.commons.R

/**
 * MetroClock workaround for a bug in the pinned `org.fossify:commons` (<= 6.2.0):
 * `getSelectedDaysString()` casts an `Arrays$ArrayList` to `ArrayList` and crashes with a
 * `ClassCastException` for any recurring, non-every-day alarm. Implements the same
 * formatting locally so the alarm list is usable. Remove once commons is fixed upstream.
 */
fun Context.getSelectedDaysStringCompat(days: Int): String {
    val dayBits = arrayListOf(1, 2, 4, 8, 16, 32, 64)
    val dayNames = resources.getStringArray(R.array.week_days_short).toMutableList()
    if (config.isSundayFirst) {
        dayBits.add(0, dayBits.removeAt(dayBits.lastIndex))
        dayNames.add(0, dayNames.removeAt(dayNames.lastIndex))
    }
    val selected = ArrayList<String>()
    dayBits.forEachIndexed { index, bit ->
        if (days and bit != 0) {
            dayNames.getOrNull(index)?.let { selected.add(it) }
        }
    }
    return selected.joinToString(", ")
}
