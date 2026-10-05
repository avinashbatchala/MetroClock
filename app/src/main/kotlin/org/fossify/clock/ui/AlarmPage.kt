package org.fossify.clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metro.ui.components.MetroButton
import com.metro.ui.components.MetroDialogBox
import com.metro.ui.components.MetroIconButton
import com.metro.ui.components.MetroSectionHeader
import com.metro.ui.components.MetroTimePicker
import com.metro.ui.components.MetroToggle
import com.metro.ui.theme.LocalMetroAccentColor
import com.metro.ui.theme.LocalMetroBackground
import com.metro.ui.theme.LocalMetroForeground
import com.metro.ui.theme.LocalMetroSubtleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.cancelAlarmClock
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.createNewAlarm
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.handleFullScreenNotificationsPermission
import org.fossify.clock.extensions.rotateWeekdays
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.helpers.getCurrentDayMinutes
import org.fossify.clock.helpers.updateNonRecurringAlarmDay
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.fossify.commons.dialogs.SelectAlarmSoundDialog
import org.fossify.commons.extensions.addBit
import org.fossify.commons.extensions.removeBit
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import android.media.AudioManager
import android.media.RingtoneManager
import org.fossify.clock.helpers.PICK_AUDIO_FILE_INTENT_ID

/**
 * Native Metro alarms page. Reads/writes the proven Fossify SQLite backend and schedules via
 * AlarmController; the composition only supplies the Windows UI.
 */
@Composable
fun AlarmPage(activity: SimpleActivity, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var alarms by remember { mutableStateOf<List<Alarm>>(emptyList()) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    val accent = LocalMetroAccentColor.current
    val fg = LocalMetroForeground.current
    val subtle = LocalMetroSubtleText.current
    val use24 = activity.config.use24HourFormat

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            activity.dbHelper.getAlarms().sortedBy { it.timeInMinutes }
        }
        alarms = loaded
    }

    LaunchedEffect(Unit) { reload() }

    // Refresh when alarms change elsewhere (ringing, dismiss, snooze, reschedule).
    DisposableEffect(Unit) {
        val listener = object {
            @Subscribe(threadMode = ThreadMode.MAIN)
            fun onAlarmEvent(event: AlarmEvent.Refresh) {
                scope.launch { reload() }
            }
        }
        EventBus.getDefault().register(listener)
        onDispose { EventBus.getDefault().unregister(listener) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalMetroBackground.current)
    ) {
        if (alarms.isEmpty()) {
            Text(
                text = "No alarms",
                style = MaterialTheme.typography.titleMedium.copy(color = subtle),
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(alarms, key = { it.id }) { alarm ->
                    MetroAlarmRow(
                        alarm = alarm,
                        use24Hour = use24,
                        accent = accent,
                        fg = fg,
                        subtle = subtle,
                        onToggle = { enabled ->
                            scope.launch {
                                if (activity.dbHelper.updateAlarmEnabledState(alarm.id, enabled)) {
                                    alarm.isEnabled = enabled
                                    applyAlarmState(activity, alarm)
                                    reload()
                                }
                            }
                        },
                        onClick = { editing = alarm.copy() }
                    )
                }
                item { Spacer(modifier = Modifier.height(96.dp)) }
            }
        }

        MetroIconButton(
            onClick = {
                val newAlarm = activity.createNewAlarm(
                    timeInMinutes = 7 * 60,
                    weekDays = 0
                ).copy(isEnabled = true)
                editing = newAlarm
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(56.dp)
                .border(2.dp, accent, RectangleShape)
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = accent,
                    fontWeight = FontWeight.Light
                )
            )
        }
    }

    val draft = editing
    if (draft != null) {
        AlarmEditor(
            activity = activity,
            initial = draft,
            onDismiss = { editing = null },
            onSaved = {
                editing = null
                scope.launch { reload() }
            },
            onDeleted = {
                editing = null
                scope.launch { reload() }
            }
        )
    }
}

@Composable
private fun MetroAlarmRow(
    alarm: Alarm,
    use24Hour: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
    subtle: androidx.compose.ui.graphics.Color,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    var enabled by remember(alarm.id, alarm.isEnabled) { mutableStateOf(alarm.isEnabled) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatAlarmTime(alarm.timeInMinutes, use24Hour),
                style = MaterialTheme.typography.displaySmall.copy(
                    color = fg,
                    fontWeight = FontWeight.Light
                ),
                maxLines = 1
            )
            Text(
                text = alarmDaysString(alarm),
                style = MaterialTheme.typography.bodyMedium.copy(color = subtle),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (alarm.label.isNotBlank()) {
                Text(
                    text = alarm.label,
                    style = MaterialTheme.typography.bodySmall.copy(color = subtle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        MetroToggle(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                onToggle(it)
            }
        )
    }
}

@Composable
private fun AlarmEditor(
    activity: SimpleActivity,
    initial: Alarm,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit
) {
    val fg = LocalMetroForeground.current
    val subtle = LocalMetroSubtleText.current
    val accent = LocalMetroAccentColor.current
    var draft by remember { mutableStateOf(initial) }
    val use24 = activity.config.use24HourFormat
    val dayLetters = remember {
        activity.resources.getStringArray(org.fossify.commons.R.array.week_day_letters)
    }
    val dayOrder = remember { activity.rotateWeekdays(arrayListOf(0, 1, 2, 3, 4, 5, 6)) }

    MetroDialogBox(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            MetroTimePicker(
                hour = draft.timeInMinutes / 60,
                minute = draft.timeInMinutes % 60,
                use24Hour = use24,
                onHourChange = { draft = draft.copy(timeInMinutes = it * 60 + draft.timeInMinutes % 60) },
                onMinuteChange = { draft = draft.copy(timeInMinutes = (draft.timeInMinutes / 60) * 60 + it) }
            )

            MetroSectionHeader("repeat")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                dayOrder.forEach { dayIndex ->
                    val bit = 1 shl dayIndex
                    val selected = draft.isRecurring() && (draft.days and bit) != 0
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(if (selected) accent else androidx.compose.ui.graphics.Color.Transparent, RectangleShape)
                            .border(2.dp, if (selected) accent else subtle, RectangleShape)
                            .clickable {
                                val base = if (!draft.isRecurring()) 0 else draft.days
                                val newDays = if (selected) base.removeBit(bit) else base.addBit(bit)
                                draft = draft.copy(days = newDays)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayLetters.getOrNull(dayIndex) ?: "",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (selected) androidx.compose.ui.graphics.Color.White else fg,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
            if (!draft.isRecurring()) {
                Text(
                    text = if (draft.timeInMinutes > getCurrentDayMinutes()) "(today)" else "(tomorrow)",
                    style = MaterialTheme.typography.bodySmall.copy(color = subtle),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            MetroSectionHeader("label")
            BasicTextField(
                value = draft.label,
                onValueChange = { draft = draft.copy(label = it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = fg),
                cursorBrush = SolidColor(fg),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RectangleShape)
                    .border(1.dp, subtle, RectangleShape)
                    .padding(12.dp)
            )

            MetroSectionHeader("options")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Vibrate",
                    style = MaterialTheme.typography.bodyLarge.copy(color = fg),
                    modifier = Modifier.weight(1f)
                )
                MetroToggle(
                    checked = draft.vibrate,
                    onCheckedChange = { draft = draft.copy(vibrate = it) }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        SelectAlarmSoundDialog(
                            activity = activity,
                            currentUri = draft.soundUri,
                            audioStream = AudioManager.STREAM_ALARM,
                            pickAudioIntentId = PICK_AUDIO_FILE_INTENT_ID,
                            type = RingtoneManager.TYPE_ALARM,
                            loopAudio = true,
                            onAlarmPicked = { picked ->
                                if (picked != null) draft = draft.copy(soundTitle = picked.title, soundUri = picked.uri)
                            },
                            onAlarmSoundDeleted = { }
                        )
                    }
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = "Sound",
                    style = MaterialTheme.typography.bodyLarge.copy(color = fg),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = draft.soundTitle,
                    style = MaterialTheme.typography.bodyMedium.copy(color = subtle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (draft.id != 0) {
                    MetroButton(
                        text = "delete",
                        onClick = {
                            activity.dbHelper.deleteAlarms(arrayListOf(draft))
                            activity.updateWidgets()
                            onDeleted()
                        },
                        outlined = true
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                MetroButton(text = "cancel", onClick = onDismiss, outlined = true)
                MetroButton(
                    text = "save",
                    onClick = { saveAlarm(activity, draft) { onSaved() } },
                    outlined = false
                )
            }
        }
    }
}

private fun saveAlarm(activity: SimpleActivity, alarm: Alarm, onSaved: () -> Unit) {
    updateNonRecurringAlarmDay(alarm)
    alarm.isEnabled = true
    alarm.oneShot = false

    activity.handleFullScreenNotificationsPermission { granted ->
        if (!granted) {
            activity.toast(org.fossify.commons.R.string.notifications_disabled)
            return@handleFullScreenNotificationsPermission
        }
        if (alarm.id == 0) {
            val newId = activity.dbHelper.insertAlarm(alarm)
            if (newId == -1) {
                activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                return@handleFullScreenNotificationsPermission
            }
            alarm.id = newId
        } else if (!activity.dbHelper.updateAlarm(alarm)) {
            activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
            return@handleFullScreenNotificationsPermission
        }
        activity.config.alarmLastConfig = alarm
        applyAlarmState(activity, alarm)
        onSaved()
    }
}

private fun applyAlarmState(activity: SimpleActivity, alarm: Alarm) {
    if (alarm.isEnabled) {
        activity.alarmController.scheduleNextOccurrence(alarm = alarm, showToasts = true)
    } else {
        activity.cancelAlarmClock(alarm)
        activity.updateWidgets()
    }
}

private fun formatAlarmTime(timeInMinutes: Int, use24Hour: Boolean): String {
    val hour24 = (timeInMinutes / 60) % 24
    val minute = timeInMinutes % 60
    return if (use24Hour) {
        "%02d:%02d".format(hour24, minute)
    } else {
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val amPm = if (hour24 < 12) "AM" else "PM"
        "%d:%02d %s".format(hour12, minute, amPm)
    }
}

private fun alarmDaysString(alarm: Alarm): String {
    if (!alarm.isRecurring()) return "once"
    val letters = charArrayOf('M', 'T', 'W', 'T', 'F', 'S', 'S')
    val selected = (0 until 7).filter { (alarm.days and (1 shl it)) != 0 }
    return when {
        selected.size == 7 -> "every day"
        selected == listOf(0, 1, 2, 3, 4) -> "weekdays"
        selected == listOf(5, 6) -> "weekends"
        else -> selected.joinToString(", ") { letters[it].toString() }
    }
}
