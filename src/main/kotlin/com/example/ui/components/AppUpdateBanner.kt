package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.FocusIndigo
import com.example.ui.viewmodel.RoutineViewModel
import com.example.update.UpdateState

/**
 * Thin, non-blocking update banner shown directly under the top bar.
 * Only visible while an update is available or downloading; everything else
 * lives in Settings → Updates.
 */
@Composable
fun AppUpdateBanner(viewModel: RoutineViewModel) {
    val state by viewModel.updateState.collectAsStateWithLifecycle()
    val dismissed by viewModel.updateBannerDismissed.collectAsStateWithLifecycle()
    val language by viewModel.appLanguage.collectAsStateWithLifecycle()
    val isArabic = language == "ar"

    if (dismissed) return

    when (val current = state) {
        is UpdateState.Available -> {
            BannerShell {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = FocusIndigo,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isArabic) {
                            "يتوفر تحديث ${current.release.version}"
                        } else {
                            "Update ${current.release.version} is available"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (current.release.releaseNotes.isNotBlank()) {
                        Text(
                            text = current.release.releaseNotes,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Button(
                    onClick = { viewModel.downloadAndInstallUpdate() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(if (isArabic) "تحديث الآن" else "Update now", fontSize = 12.sp)
                }
                TextButton(onClick = { viewModel.dismissUpdateBanner() }) {
                    Text(
                        if (isArabic) "لاحقًا" else "Later",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        is UpdateState.Downloading -> {
            BannerShell {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (isArabic) {
                            "جارٍ تنزيل التحديث… ${current.percent}%"
                        } else {
                            "Downloading update… ${current.percent}%"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    LinearProgressIndicator(
                        progress = { current.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        else -> Unit
    }
}

@Composable
private fun BannerShell(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(
        color = FocusIndigo.copy(alpha = 0.10f),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}
