package org.fossify.clock.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.compose.AndroidFragment
import com.metro.livetile.contract.MetroLiveTileProtocol
import com.metro.ui.components.MetroPivot
import com.metro.ui.theme.LocalMetroBackground
import com.metro.ui.theme.MetroColors
import com.metro.ui.theme.MetroTheme
import org.fossify.clock.BuildConfig
import org.fossify.clock.R
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.getEnabledAlarms
import org.fossify.clock.extensions.handleFullScreenNotificationsPermission
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.fragments.AlarmFragment
import org.fossify.clock.fragments.ClockFragment
import org.fossify.clock.fragments.StopwatchFragment
import org.fossify.clock.fragments.TimerFragment
import org.fossify.clock.helpers.OPEN_TAB
import org.fossify.clock.helpers.TAB_ALARM
import org.fossify.clock.helpers.TAB_CLOCK
import org.fossify.clock.helpers.TAB_STOPWATCH
import org.fossify.clock.helpers.TAB_TIMER
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Metro presentation shell for Clock. It reuses the proven Fossify alarm/timer/stopwatch
 * fragments (backend authoritative) inside a Windows 10 Mobile pivot, replacing the Material
 * bottom-navigation chrome. Feature screens migrate to native Compose incrementally.
 */
class MetroClockActivity : SimpleActivity(), ClockHost {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLaunched(BuildConfig.APPLICATION_ID)
        updateWidgets()
        migrateFirstDayOfWeek()
        ensureBackgroundThread { alarmController.rescheduleEnabledAlarms() }

        getEnabledAlarms { enabledAlarms ->
            if (!enabledAlarms.isNullOrEmpty()) {
                handleFullScreenNotificationsPermission { granted ->
                    if (!granted) {
                        toast(org.fossify.commons.R.string.notifications_disabled)
                    }
                }
            }
        }

        val initialTab = resolveInitialTab()
        setContent {
            MetroTheme(
                accentColor = MetroColors.Blue,
                darkTheme = isSystemInDarkTheme()
            ) {
                MetroClockShell(initialTab = initialTab)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun updateClockTabAlarm() = Unit

    private fun resolveInitialTab(): Int {
        val data = intent?.data
        if (data != null && data.scheme == MetroLiveTileProtocol.CLOCK_SCHEME) {
            return when (data.host) {
                MetroLiveTileProtocol.CLOCK_HOST_ALARMS -> TAB_ALARM
                MetroLiveTileProtocol.CLOCK_HOST_TIMER -> TAB_TIMER
                MetroLiveTileProtocol.CLOCK_HOST_STOPWATCH -> TAB_STOPWATCH
                MetroLiveTileProtocol.CLOCK_HOST_WORLD -> TAB_CLOCK
                else -> config.defaultTab
            }
        }
        return intent.getIntExtra(OPEN_TAB, config.defaultTab)
    }

    @Deprecated("Mirrors the upstream one-time migration in MainActivity")
    private fun migrateFirstDayOfWeek() {
        if (config.migrateFirstDayOfWeek) {
            config.migrateFirstDayOfWeek = false
            config.firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
        }
    }
}

@Composable
private fun MetroClockShell(initialTab: Int, modifier: Modifier = Modifier) {
    val titles = listOf("alarms", "world clock", "timer", "stopwatch")
    val initialIndex = when (initialTab) {
        TAB_ALARM -> 0
        TAB_CLOCK -> 1
        TAB_TIMER -> 2
        TAB_STOPWATCH -> 3
        else -> 0
    }
    val background = LocalMetroBackground.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .statusBarsPadding()
    ) {
        Text(
            text = "Clock",
            style = MaterialTheme.typography.headlineLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Light
            ),
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
        )

        MetroPivot(
            titles = titles,
            initialIndex = initialIndex,
            translateHeader = false,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> AndroidFragment<AlarmFragment>(modifier = Modifier.fillMaxSize())
                1 -> AndroidFragment<ClockFragment>(modifier = Modifier.fillMaxSize())
                2 -> AndroidFragment<TimerFragment>(modifier = Modifier.fillMaxSize())
                3 -> AndroidFragment<StopwatchFragment>(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
