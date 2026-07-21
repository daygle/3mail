package com.threemail.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.threemail.android.notifications.NotificationHelper
import com.threemail.android.push.PushController
import com.threemail.android.ui.navigation.Screen
import com.threemail.android.ui.navigation.ThreeMailNavHost
import com.threemail.android.ui.theme.ThemeViewModel
import com.threemail.android.ui.theme.ThreeMailTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Drives the IMAP IDLE push foreground service. Push is (re)started from
     * [onResume] rather than from [ThreeMailApplication.onCreate] on purpose:
     * the service runs as a `dataSync` foreground service, and starting a
     * foreground service is only permitted from a foreground app state on
     * API 31+. Kicking it off from the Application's cold-start path races the
     * launch/permission window and can trip
     * `ForegroundServiceStartNotAllowedException`, which the platform escalates
     * to an uncatchable crash. Starting it here keeps the FGS start safely
     * inside the app's foreground lifetime.
     */
    @Inject
    lateinit var pushController: PushController

    /**
     * Runtime POST_NOTIFICATIONS prompt (required on API 33+). The result is
     * intentionally ignored - channels degrade gracefully when the permission
     * is denied, but the launcher badge won't surface until the user grants
     * the permission.
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* result intentionally ignored: see class doc */ }

    /**
     * Message id a notification tap asked us to open, or null. Set from the
     * launch intent (cold start) and from [onNewIntent] (warm start, since the
     * activity is `singleTop`-reused). Consumed once by the nav effect below.
     */
    private val deepLinkMessageId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        deepLinkMessageId.value = extractMessageId(intent)
        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val settings by themeViewModel.settings.collectAsState()
            ThreeMailTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.useDynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    ThreeMailNavHost(navController = navController)

                    // Consume a pending notification deep-link once the nav host
                    // is composed, opening the tapped message.
                    val pendingMessageId by deepLinkMessageId.collectAsState()
                    LaunchedEffect(pendingMessageId) {
                        val id = pendingMessageId ?: return@LaunchedEffect
                        navController.navigate(Screen.MessageDetail.createRoute(id))
                        deepLinkMessageId.value = null
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkMessageId.value = extractMessageId(intent)
    }

    private fun extractMessageId(intent: Intent?): Long? =
        intent?.getLongExtra(NotificationHelper.EXTRA_MESSAGE_ID, -1L)?.takeIf { it > 0L }

    override fun onResume() {
        super.onResume()
        // Start/refresh IMAP IDLE push from a guaranteed-foreground state so the
        // dataSync foreground service is never started from a restricted context.
        // Guarded so a rejected start can never take the app down with it.
        runCatching { pushController.refresh() }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionLauncher.launch(permission)
    }
}
