package org.fossify.clock.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.metro.livetile.contract.ClockNextAlarmState
import com.metro.livetile.contract.ClockRunState
import com.metro.livetile.contract.ClockStopwatchState
import com.metro.livetile.contract.ClockTileState
import com.metro.livetile.contract.ClockTimerState
import com.metro.livetile.contract.MetroLiveTileProtocol
import com.metro.livetile.contract.toBundle
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.timerDb
import org.fossify.clock.helpers.Stopwatch
import org.fossify.clock.helpers.getTimeOfNextAlarm
import org.fossify.clock.models.TimerState

/**
 * Signature-protected, read-only bridge that lets MetroSuite consumers (the launcher) query
 * Clock's live-tile state. It returns raw timestamps/monotonic anchors rather than formatted
 * ticking text, so consumers can render countdowns locally without per-second IPC.
 *
 * Meaningful transitions call [notifyChanged]; consumers observe the URI and re-query.
 */
class ClockLiveTileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // The framework does not apply android:readPermission to call(), so enforce the
        // signature permission ourselves (defence in depth beyond the manifest declaration).
        enforceReadPermission()
        return when (method) {
            MetroLiveTileProtocol.METHOD_GET_CLOCK_STATE -> buildState().toBundle()
            else -> Bundle().apply {
                putInt(MetroLiveTileProtocol.EXTRA_PROTOCOL_VERSION, 0)
            }
        }
    }

    private fun enforceReadPermission() {
        val ctx = context ?: return
        if (ctx.checkCallingPermission(MetroLiveTileProtocol.PERMISSION_READ_TILE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Missing ${MetroLiveTileProtocol.PERMISSION_READ_TILE_STATE}")
        }
    }

    private fun buildState(): ClockTileState {
        val now = System.currentTimeMillis()
        val timers = activeTimers()
        val stopwatches = activeStopwatches()
        return ClockTileState(
            updatedAt = now,
            currentEpochMillis = now,
            nextAlarm = nextAlarm(),
            timer = timers.firstOrNull(),
            stopwatch = stopwatches.firstOrNull(),
            timers = timers,
            stopwatches = stopwatches
        )
    }

    private fun nextAlarm(): ClockNextAlarmState? {
        val context = context ?: return null
        return runCatching {
            context.dbHelper.getEnabledAlarms()
                .mapNotNull { alarm ->
                    getTimeOfNextAlarm(alarm)?.let { calendar ->
                        ClockNextAlarmState(
                            alarmId = alarm.id,
                            triggerEpochMillis = calendar.timeInMillis,
                            label = alarm.label.ifBlank { null },
                            enabled = alarm.isEnabled
                        )
                    }
                }
                .minByOrNull { it.triggerEpochMillis }
        }.getOrNull()
    }

    private fun activeTimers(): List<ClockTimerState> {
        val context = context ?: return emptyList()
        val nowElapsed = SystemClock.elapsedRealtime()
        return runCatching {
            context.timerDb.getTimers().mapNotNull { timer ->
                when (val state = timer.state) {
                    is TimerState.Running -> ClockTimerState(
                        state = ClockRunState.RUNNING,
                        endElapsedRealtime = nowElapsed + state.tick,
                        remainingWhenPausedMillis = 0L,
                        label = timer.label.ifBlank { null },
                        id = timer.id
                    )
                    is TimerState.Paused -> ClockTimerState(
                        state = ClockRunState.PAUSED,
                        endElapsedRealtime = 0L,
                        remainingWhenPausedMillis = state.tick,
                        label = timer.label.ifBlank { null },
                        id = timer.id
                    )
                    else -> null
                }
            }.sortedBy { timer ->
                if (timer.state == ClockRunState.RUNNING) timer.endElapsedRealtime
                else nowElapsed + timer.remainingWhenPausedMillis
            }
        }.getOrDefault(emptyList())
    }

    private fun activeStopwatches(): List<ClockStopwatchState> {
        val snapshot = Stopwatch.snapshot()
        return when (snapshot.state) {
            Stopwatch.State.RUNNING -> listOf(
                ClockStopwatchState(
                    state = ClockRunState.RUNNING,
                    startElapsedRealtime = snapshot.startElapsedRealtime,
                    accumulatedElapsedMillis = snapshot.accumulatedElapsed
                )
            )
            Stopwatch.State.PAUSED -> listOf(
                ClockStopwatchState(
                    state = ClockRunState.PAUSED,
                    startElapsedRealtime = 0L,
                    accumulatedElapsedMillis = snapshot.accumulatedElapsed
                )
            )
            Stopwatch.State.STOPPED -> emptyList()
        }
    }

    companion object {
        val URI: Uri = Uri.parse("content://${MetroLiveTileProtocol.CLOCK_AUTHORITY}")

        /** Tells consumers (if any) to re-query after a meaningful state change. */
        fun notifyChanged(context: Context) {
            runCatching { context.contentResolver.notifyChange(URI, null) }
        }
    }
}
