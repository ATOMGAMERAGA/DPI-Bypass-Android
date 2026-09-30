package net.atom.dpibypass.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.atom.dpibypass.ui.apps.AppsScreen
import net.atom.dpibypass.ui.design.AmbientBackground
import net.atom.dpibypass.ui.design.ChromeState
import net.atom.dpibypass.ui.design.LocalChrome
import net.atom.dpibypass.ui.design.LocalHapticsEnabled
import net.atom.dpibypass.ui.design.LocalTabletLayout
import net.atom.dpibypass.ui.design.connectionColor
import net.atom.dpibypass.ui.theme.Motion
import net.atom.dpibypass.ui.home.HomeScreen
import net.atom.dpibypass.ui.mode.ModeScreen
import net.atom.dpibypass.ui.nav.BottomDock
import net.atom.dpibypass.ui.nav.TabletRail
import net.atom.dpibypass.ui.nav.Dest
import net.atom.dpibypass.ui.onboarding.OnboardingOverlay
import net.atom.dpibypass.ui.settings.SettingsScreen
import net.atom.dpibypass.ui.theme.DpiBypassTheme
import net.atom.dpibypass.util.DeviceInfo
import net.atom.dpibypass.util.QuickTile
import net.atom.dpibypass.vpn.ServiceController

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    // VPN izni sonucu → onaylanırsa servisi başlat.
    private val vpnPermissionLauncher =
        registerForActivityResultInternal()

    private fun registerForActivityResultInternal() =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                ServiceController.start(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            DpiBypassTheme(themePref = settings.theme) {
                // Haptik tercihi tüm arayüze buradan akar; her bileşen kendi
                // ayarını okumak zorunda kalmaz.
                CompositionLocalProvider(LocalHapticsEnabled provides settings.haptics) {
                    AppRoot(
                        viewModel = viewModel,
                        onConnect = ::connect,
                        onDisconnect = ::disconnect,
                        onRequestTile = ::requestQuickTile,
                        discordInstalled = isDiscordInstalled(),
                    )
                }
            }
        }
    }

    /** Bağlan: VPN izni gerekiyorsa iste, yoksa doğrudan başlat. */
    private fun connect() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            ServiceController.start(this)
        }
    }

    private fun disconnect() {
        ServiceController.stop(this)
    }

    /** Hızlı Panel kısayolunu (Android 13+ sistem onayıyla) ekle. */
    private fun requestQuickTile() {
        QuickTile.request(this)
    }

    /** Discord kurulu mu? Kurulum sihirbazının Discord adımını tetikler. */
    private fun isDiscordInstalled(): Boolean = try {
        packageManager.getPackageInfo(AppViewModel.DISCORD_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                    .launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

}

@Composable
private fun AppRoot(
    viewModel: AppViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRequestTile: () -> Unit,
    discordInstalled: Boolean,
) {
    val navController = rememberNavController()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    // Ekranların kaydırma durumu kabuk öğeleriyle (başlık şeridi, dock) burada
    // buluşur; dock hangi ekranda olduğumuzu bilmeden ekrana tepki verebilir.
    val chrome = remember { ChromeState() }

    // Ekran geçişi: derinlik ekseni. Giren ekran hafifçe büyüyerek ve aşağıdan
    // gelerek yerleşir, çıkan ekran öne doğru büyüyüp solar. Yatay kaydırma
    // seçilmedi, çünkü dock sekmelerinin sırası hiyerarşik bir yol değildir.
    val enter = fadeIn(tween(Motion.SCREEN_ENTER_MS, easing = Motion.EmphasizedDecelerate)) +
        scaleIn(
            initialScale = 0.965f,
            animationSpec = tween(Motion.SCREEN_ENTER_MS, easing = Motion.EmphasizedDecelerate),
        ) +
        slideInVertically(
            animationSpec = tween(Motion.SCREEN_ENTER_MS, easing = Motion.EmphasizedDecelerate),
            initialOffsetY = { it / 22 },
        )
    val exit = fadeOut(tween(Motion.SCREEN_EXIT_MS, easing = Motion.EmphasizedAccelerate)) +
        scaleOut(
            targetScale = 1.03f,
            animationSpec = tween(Motion.SCREEN_EXIT_MS, easing = Motion.EmphasizedAccelerate),
        )

    // Zemin, bağlantı durumunun rengini alır: uygulama bir bütün olarak durum
    // değiştirir, sadece bir düğme değil.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tablet = maxWidth >= 600.dp
        CompositionLocalProvider(
            LocalChrome provides chrome,
            LocalTabletLayout provides tablet,
        ) {
        AmbientBackground(
            accent = connectionColor(connectionState),
            modifier = Modifier.fillMaxSize(),
            overlay = {
                // Dock ve sihirbaz, arka plan KAYDININ DIŞINDA durur: kendini
                // içeren bir katmanı bulanıklaştıran cam olamaz (bkz. Glass.kt).
                if (tablet) {
                    TabletRail(
                        navController = navController,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                } else {
                    BottomDock(
                        navController = navController,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 14.dp),
                    )
                }

                // İlk açılış kurulum sihirbazı — her şeyin üstüne biner.
                if (!settings.onboardingDone) {
                    OnboardingOverlay(
                        discordInstalled = discordInstalled,
                        brandName = DeviceInfo.brandName(),
                        quickPanelName = DeviceInfo.quickPanelName(),
                        onEnableDiscordMode = viewModel::enableDiscordOnlyMode,
                        onRequestTile = onRequestTile,
                        onFinish = { viewModel.setOnboardingDone(true) },
                    )
                }
            },
        ) {
            NavHost(
                navController = navController,
                startDestination = Dest.Home.route,
                modifier = if (tablet) Modifier.padding(start = 88.dp) else Modifier,
                enterTransition = { enter },
                exitTransition = { exit },
                popEnterTransition = { enter },
                popExitTransition = { exit },
            ) {
                composable(Dest.Home.route) {
                    HomeScreen(
                        viewModel = viewModel,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onNavigate = { route ->
                            navController.navigate(route) {
                                popUpTo(Dest.Home.route)
                                launchSingleTop = true
                            }
                        },
                        onRequestTile = onRequestTile,
                    )
                }
                composable(Dest.Mode.route) { ModeScreen(viewModel) }
                composable(Dest.Apps.route) { AppsScreen(viewModel) }
                composable(Dest.Settings.route) { SettingsScreen(viewModel, onRequestTile = onRequestTile) }
            }
        }
        }
    }
}
