package com.example

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.auth.GoogleAccount
import com.example.data.sync.SyncState
import com.example.desktop.platform.DesktopApp
import com.example.desktop.platform.ToastBus
import com.example.ui.components.MorePagesSheet
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.components.AppUpdateBanner
import com.example.ui.screens.BackupSettingsDialog
import com.example.ui.screens.CustomPageScreen
import com.example.ui.screens.FocusTimerScreen
import com.example.ui.screens.NotesAndBoardsScreen
import com.example.ui.screens.RoutineScreen
import com.example.ui.screens.ScheduleScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AppEdition
import com.example.ui.viewmodel.NavTab
import com.example.ui.viewmodel.RoutineViewModel
import com.example.util.LocalAppLanguage
import com.example.util.appStrings
import kotlinx.coroutines.delay

private const val APP_TITLE = "Wave Focus"

fun main() = application {
    val windowState = rememberWindowState(width = 1180.dp, height = 840.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = APP_TITLE,
        state = windowState,
        icon = painterResource("wave_focus_icon.png")
    ) {
        WaveFocusDesktopApp()
    }
}

@Composable
private fun WaveFocusDesktopApp() {
    val viewModel = remember { RoutineViewModel(DesktopApp.context) }
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val layoutDirection = if (appLanguage == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalAppLanguage provides appLanguage
    ) {
        MyApplicationTheme(themeMode = themeMode) {
            Box(modifier = Modifier.fillMaxSize()) {
                DailyRoutineApp(viewModel = viewModel, themeMode = themeMode)
                ToastOverlay()
            }
        }
    }
}

@Composable
private fun ToastOverlay() {
    val message by ToastBus.message.collectAsStateWithLifecycle()
    val current = message ?: return

    LaunchedEffect(current) {
        delay(2800)
        ToastBus.consume()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .padding(bottom = 28.dp)
                .widthIn(max = 560.dp)
        ) {
            Text(
                text = current,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }
}

/** Soft PWA-style ambient background: two tinted glows over the base colour. */
@Composable
private fun Modifier.pwaBackground(): Modifier {
    val base = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    return drawBehind {
        drawRect(base)
        drawRect(
            Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.16f), primary.copy(alpha = 0f)),
                center = Offset(size.width, 0f),
                radius = size.width * 0.9f
            )
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = 0.12f), secondary.copy(alpha = 0f)),
                center = Offset(0f, 0f),
                radius = size.width * 0.7f
            )
        )
    }
}

@Composable
fun DailyRoutineApp(
    viewModel: RoutineViewModel,
    themeMode: String
) {
    val strings = appStrings()
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val tasks by viewModel.tasksForSelectedDate.collectAsStateWithLifecycle()
    val activeTasks by viewModel.allActiveTasks.collectAsStateWithLifecycle()

    val timerMode by viewModel.timerMode.collectAsStateWithLifecycle()
    val totalSeconds by viewModel.timerTotalSeconds.collectAsStateWithLifecycle()
    val remainingSeconds by viewModel.timerRemainingSeconds.collectAsStateWithLifecycle()
    val isTimerRunning by viewModel.isTimerRunning.collectAsStateWithLifecycle()
    val linkedTask by viewModel.linkedRoutineTask.collectAsStateWithLifecycle()
    val todayFocusSessions by viewModel.todayFocusSessions.collectAsStateWithLifecycle()

    val calendarMonth by viewModel.calendarMonth.collectAsStateWithLifecycle()
    val scriptsForDate by viewModel.scriptsForSelectedDate.collectAsStateWithLifecycle()
    val allScripts by viewModel.allScripts.collectAsStateWithLifecycle()

    val analyticsData by viewModel.analyticsData.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val activeCustomPage by viewModel.activeCustomPage.collectAsStateWithLifecycle()
    val appEdition by viewModel.appEdition.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val cloudAccount by viewModel.cloudAccount.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

    val isArabic = appLanguage == "ar"
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showMorePagesSheet by remember { mutableStateOf(false) }

    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUserMessage()
        }
    }

    Row(modifier = Modifier.fillMaxSize().pwaBackground()) {
        PwaSideRail(
            currentTab = currentTab,
            isArabic = isArabic,
            appEdition = appEdition,
            cloudAccount = cloudAccount,
            syncState = syncState,
            hasCustomPage = activeCustomPage != null,
            onSelectTab = { viewModel.setTab(it) },
            onOpenMore = { showMorePagesSheet = true },
            onOpenSettings = { showSettingsDialog = true },
            onToggleEdition = { viewModel.toggleAppEdition() },
            onToggleLanguage = { viewModel.toggleLanguage() }
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        )

        Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
            PwaTopBar(
                title = when (currentTab) {
                    NavTab.ROUTINE -> strings.routine
                    NavTab.FOCUS -> strings.focus
                    NavTab.SCRIPTS -> strings.schedule
                    NavTab.NOTES -> strings.notes
                    NavTab.ANALYTICS -> strings.progress
                    NavTab.CUSTOM_PAGE -> activeCustomPage?.title?.take(24) ?: strings.workspace
                },
                isArabic = isArabic,
                languageLabel = if (isArabic) "English" else "العربية",
                appEdition = appEdition,
                onToggleEdition = { viewModel.toggleAppEdition() },
                onToggleLanguage = { viewModel.toggleLanguage() },
                onOpenMore = { showMorePagesSheet = true }
            )

            AppUpdateBanner(viewModel = viewModel)

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 1180.dp)
                        .fillMaxWidth()
                        .fillMaxHeight()
                ) {
                    Crossfade(
                        targetState = currentTab,
                        label = "tabCrossfade",
                        modifier = Modifier.fillMaxSize()
                    ) { tab ->
                        when (tab) {
                            NavTab.ROUTINE -> RoutineScreen(
                                viewModel = viewModel,
                                tasks = tasks,
                                selectedDate = selectedDate
                            )
                            NavTab.FOCUS -> FocusTimerScreen(
                                viewModel = viewModel,
                                timerMode = timerMode,
                                totalSeconds = totalSeconds,
                                remainingSeconds = remainingSeconds,
                                isRunning = isTimerRunning,
                                linkedTask = linkedTask,
                                activeTasks = activeTasks,
                                todaySessions = todayFocusSessions
                            )
                            NavTab.SCRIPTS -> ScheduleScreen(
                                viewModel = viewModel,
                                selectedDate = selectedDate,
                                calendarMonth = calendarMonth,
                                scriptsForDate = scriptsForDate,
                                allScripts = allScripts,
                                tasks = tasks,
                                allActiveTasks = activeTasks
                            )
                            NavTab.NOTES -> NotesAndBoardsScreen(
                                viewModel = viewModel
                            )
                            NavTab.ANALYTICS -> AnalyticsScreen(
                                viewModel = viewModel,
                                analyticsData = analyticsData
                            )
                            NavTab.CUSTOM_PAGE -> CustomPageScreen(
                                viewModel = viewModel
                            )
                        }
                    } // Crossfade
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                )
            }
        }
    }

    if (showMorePagesSheet) {
        MorePagesSheet(
            viewModel = viewModel,
            currentTab = currentTab,
            onOpenSettings = { showSettingsDialog = true },
            onDismiss = { showMorePagesSheet = false }
        )
    }

    if (showSettingsDialog) {
        BackupSettingsDialog(
            viewModel = viewModel,
            currentThemeMode = themeMode,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

// ---------------------------------------------------------------- chrome

private val RailWidth = 236.dp

@Composable
private fun PwaSideRail(
    currentTab: NavTab,
    isArabic: Boolean,
    appEdition: AppEdition,
    cloudAccount: GoogleAccount?,
    syncState: SyncState,
    hasCustomPage: Boolean,
    onSelectTab: (NavTab) -> Unit,
    onOpenMore: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleEdition: () -> Unit,
    onToggleLanguage: () -> Unit
) {
    val strings = appStrings()
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        modifier = Modifier.width(RailWidth).fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 18.dp)
        ) {
            // Wordmark
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource("wave_focus_icon.png"),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Wave Focus",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isArabic) "تركيز · روتين · إنجاز" else "Focus · Routine · Momentum",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Eyebrow(if (isArabic) "المساحة" else "WORKSPACE")

            RailNavItem(
                icon = Icons.Outlined.CheckCircle,
                selectedIcon = Icons.Default.CheckCircle,
                label = strings.routine,
                selected = currentTab == NavTab.ROUTINE,
                onClick = { onSelectTab(NavTab.ROUTINE) },
                testTag = "nav_tab_routine"
            )
            RailNavItem(
                icon = Icons.Outlined.Timer,
                selectedIcon = Icons.Default.Timer,
                label = strings.focus,
                selected = currentTab == NavTab.FOCUS,
                onClick = { onSelectTab(NavTab.FOCUS) },
                testTag = "nav_tab_focus"
            )
            RailNavItem(
                icon = Icons.Outlined.CalendarMonth,
                selectedIcon = Icons.Default.CalendarMonth,
                label = strings.schedule,
                selected = currentTab == NavTab.SCRIPTS,
                onClick = { onSelectTab(NavTab.SCRIPTS) },
                testTag = "nav_tab_schedule"
            )
            RailNavItem(
                icon = Icons.Outlined.Description,
                selectedIcon = Icons.Default.Description,
                label = strings.notes,
                selected = currentTab == NavTab.NOTES,
                onClick = { onSelectTab(NavTab.NOTES) },
                testTag = "nav_tab_notes"
            )
            RailNavItem(
                icon = Icons.Outlined.BarChart,
                selectedIcon = Icons.Default.BarChart,
                label = strings.progress,
                selected = currentTab == NavTab.ANALYTICS,
                onClick = { onSelectTab(NavTab.ANALYTICS) },
                testTag = "nav_tab_progress"
            )
            if (hasCustomPage) {
                RailNavItem(
                    icon = Icons.Outlined.Bookmark,
                    selectedIcon = Icons.Default.Bookmark,
                    label = strings.workspace,
                    selected = currentTab == NavTab.CUSTOM_PAGE,
                    onClick = { onSelectTab(NavTab.CUSTOM_PAGE) },
                    testTag = "nav_tab_workspace"
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Eyebrow(if (isArabic) "المزيد" else "MORE")

            RailNavItem(
                icon = Icons.Outlined.Bookmark,
                selectedIcon = Icons.Default.Bookmark,
                label = strings.morePages,
                selected = false,
                onClick = onOpenMore,
                testTag = "nav_more_pages"
            )
            RailNavItem(
                icon = Icons.Default.Settings,
                selectedIcon = Icons.Default.Settings,
                label = strings.settings,
                selected = false,
                onClick = onOpenSettings,
                testTag = "nav_settings"
            )

            Spacer(modifier = Modifier.weight(1f))

            // Cloud account chip
            val signedIn = cloudAccount != null
            Surface(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth().testTag("rail_account_chip")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    signedIn && syncState == SyncState.SYNCED -> Color(0xFF10B981)
                                    signedIn -> Color(0xFFFBBF24)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cloudAccount?.displayName?.takeIf { it.isNotBlank() }
                                ?: if (signedIn) (if (isArabic) "حساب Google" else "Google account")
                                else (if (isArabic) "المزامنة متوقفة" else "Sync off"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = cloudAccount?.email ?: (if (isArabic) "سجّل الدخول للمزامنة" else "Sign in to sync"),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = if (signedIn) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (signedIn) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    onClick = onToggleEdition,
                    shape = RoundedCornerShape(999.dp),
                    color = if (appEdition == AppEdition.ISLAMIC) {
                        Color(0xFF0F766E).copy(alpha = 0.22f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    },
                    border = if (appEdition == AppEdition.ISLAMIC) {
                        BorderStroke(1.dp, Color(0xFF0F766E))
                    } else null,
                    modifier = Modifier.weight(1f).testTag("top_hijri_version_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 7.dp)
                    ) {
                        Icon(
                            imageVector = if (appEdition == AppEdition.ISLAMIC) Icons.Default.CalendarMonth else Icons.Default.Mosque,
                            contentDescription = if (appEdition == AppEdition.ISLAMIC) strings.switchToStandardEdition else strings.switchToIslamicEdition,
                            tint = if (appEdition == AppEdition.ISLAMIC) Color(0xFF5EEAD4) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (appEdition == AppEdition.ISLAMIC) {
                                if (isArabic) "ميلادي" else "Gregorian"
                            } else {
                                if (isArabic) "هجري" else "Hijri"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (appEdition == AppEdition.ISLAMIC) Color(0xFF5EEAD4) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    onClick = onToggleLanguage,
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f).testTag("top_language_toggle_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = if (isArabic) "Switch to English" else "التبديل إلى العربية",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isArabic) "English" else "العربية",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp)
    )
}

@Composable
private fun RailNavItem(
    icon: ImageVector,
    selectedIcon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(13.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth().testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(11.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun PwaTopBar(
    title: String,
    isArabic: Boolean,
    languageLabel: String,
    appEdition: AppEdition,
    onToggleEdition: () -> Unit,
    onToggleLanguage: () -> Unit,
    onOpenMore: () -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 26.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isArabic) "كل يوم، خطوة إلى الأمام" else "One calm step at a time",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            GhostChip(
                text = languageLabel,
                icon = Icons.Default.Language,
                onClick = onToggleLanguage,
                testTag = "top_language_toggle_button_small"
            )
            Spacer(modifier = Modifier.width(8.dp))
            GhostChip(
                text = if (appEdition == AppEdition.ISLAMIC) {
                    if (isArabic) "هجري" else "Hijri"
                } else {
                    if (isArabic) "ميلادي" else "Gregorian"
                },
                icon = if (appEdition == AppEdition.ISLAMIC) Icons.Default.CalendarMonth else Icons.Default.Mosque,
                highlighted = appEdition == AppEdition.ISLAMIC,
                onClick = onToggleEdition,
                testTag = "top_hijri_version_button_small"
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onOpenMore,
                modifier = Modifier.size(38.dp).testTag("top_right_menu_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = appStrings().morePages,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
            thickness = 1.dp
        )
    }
}

@Composable
private fun GhostChip(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
    highlighted: Boolean = false
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (highlighted) Color(0xFF0F766E).copy(alpha = 0.2f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = if (highlighted) BorderStroke(1.dp, Color(0xFF0F766E)) else null,
        modifier = Modifier.testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) Color(0xFF5EEAD4) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) Color(0xFF5EEAD4) else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
