package com.threemail.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.threemail.android.data.settings.AppSettings
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.notifications.NotificationHelper
import com.threemail.android.push.PushController
import com.threemail.android.ui.navigation.Screen
import com.threemail.android.ui.navigation.ThreeMailNavHost
import com.threemail.android.ui.theme.ThreeMailTheme
import com.threemail.android.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var pushController: PushController
    @Inject lateinit var settingsRepository: SettingsRepository

    private val deepLinkMessageId = MutableStateFlow<Long?>(null)
    private val replyMessageId = MutableStateFlow<Long?>(null)
    private val biometricUnlocked = MutableStateFlow(false)
    private val biometricUnavailable = MutableStateFlow(false)
    private var biometricLockEnabled = false
    private var biometricPromptShowing = false
    private var needsReauthentication = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        deepLinkMessageId.value = extractMessageId(intent)
        replyMessageId.value = extractReplyMessageId(intent)

        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val themeSettings by themeViewModel.settings.collectAsState()
            val appSettings by settingsRepository.settings.collectAsState(initial = AppSettings())
            val unlocked by biometricUnlocked.collectAsState()
            val authenticationUnavailable by biometricUnavailable.collectAsState()

            LaunchedEffect(appSettings.biometricLockEnabled) {
                biometricLockEnabled = appSettings.biometricLockEnabled
                if (!biometricLockEnabled) {
                    biometricUnavailable.value = false
                    biometricUnlocked.value = true
                } else if (!unlocked && !biometricPromptShowing) {
                    authenticateBiometrically()
                }
            }

            ThreeMailTheme(
                themeMode = themeSettings.themeMode,
                dynamicColor = themeSettings.useDynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!biometricLockEnabled || unlocked) {
                        val navController = rememberNavController()
                        ThreeMailNavHost(navController = navController)
                        val pendingMessageId by deepLinkMessageId.collectAsState()
                        val pendingReplyId by replyMessageId.collectAsState()
                        LaunchedEffect(pendingMessageId) {
                            val id = pendingMessageId ?: return@LaunchedEffect
                            navController.navigate(Screen.MessageDetail.createRoute(id))
                            deepLinkMessageId.value = null
                        }
                        LaunchedEffect(pendingReplyId) {
                            val id = pendingReplyId ?: return@LaunchedEffect
                            navController.navigate(Screen.Compose.createRoute("reply", id))
                            replyMessageId.value = null
                        }
                    } else {
                        BiometricLockScreen(
                            unavailable = authenticationUnavailable,
                            onRetry = ::authenticateBiometrically,
                            onOpenSecuritySettings = {
                                runCatching {
                                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkMessageId.value = extractMessageId(intent)
        replyMessageId.value = extractReplyMessageId(intent)
    }

    override fun onResume() {
        super.onResume()
        if (biometricLockEnabled && needsReauthentication && !biometricPromptShowing) {
            needsReauthentication = false
            biometricUnlocked.value = false
            authenticateBiometrically()
        } else if (!biometricLockEnabled) {
            biometricUnlocked.value = true
        }
        runCatching { pushController.refresh() }
    }

    override fun onPause() {
        // BiometricPrompt briefly pauses the activity itself. Do not treat that
        // prompt lifecycle as leaving the app, or a successful authentication
        // would immediately trigger a second prompt on resume.
        if (biometricLockEnabled && !biometricPromptShowing) {
            needsReauthentication = true
        }
        super.onPause()
    }

    private fun authenticateBiometrically() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            // Keep mail locked when the device has no secure authenticator.
            // The lock screen offers a direct path to Android security settings
            // so the user can enroll a credential and retry.
            biometricUnavailable.value = true
            biometricUnlocked.value = false
            return
        }

        biometricUnavailable.value = false
        biometricPromptShowing = true
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_unlock_title))
            .setSubtitle(getString(R.string.biometric_unlock_subtitle))
            .setAllowedAuthenticators(authenticators)
            .build()
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    biometricPromptShowing = false
                    needsReauthentication = false
                    biometricUnlocked.value = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    biometricPromptShowing = false
                    biometricUnlocked.value = false
                }

                override fun onAuthenticationFailed() {
                    biometricPromptShowing = false
                }
            }
        ).authenticate(promptInfo)
    }

    private fun extractMessageId(intent: Intent?): Long? =
        intent?.getLongExtra(NotificationHelper.EXTRA_MESSAGE_ID, -1L)?.takeIf { it > 0L }

    private fun extractReplyMessageId(intent: Intent?): Long? =
        intent?.getLongExtra(NotificationHelper.EXTRA_REPLY_MESSAGE_ID, -1L)?.takeIf { it > 0L }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun BiometricLockScreen(
    unavailable: Boolean,
    onRetry: () -> Unit,
    onOpenSecuritySettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Fingerprint,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResourceCompat(com.threemail.android.R.string.biometric_unlock_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = if (unavailable) {
                stringResourceCompat(com.threemail.android.R.string.biometric_unavailable)
            } else {
                stringResourceCompat(com.threemail.android.R.string.biometric_unlock_subtitle)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
        )
        Button(onClick = onRetry) { Text(stringResourceCompat(com.threemail.android.R.string.biometric_retry)) }
        if (unavailable) {
            androidx.compose.material3.TextButton(onClick = onOpenSecuritySettings) {
                Text(stringResourceCompat(com.threemail.android.R.string.open_security_settings))
            }
        }
    }
}

@Composable
private fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
