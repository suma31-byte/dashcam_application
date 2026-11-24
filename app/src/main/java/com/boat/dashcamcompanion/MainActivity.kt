package com.boat.dashcamcompanion

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.boat.dashcamcompanion.drive.PotholeAlerts
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.boat.dashcamcompanion.ui.ConnectScreen
import com.boat.dashcamcompanion.ui.HomeScreen
import com.boat.dashcamcompanion.ui.InstallScreen
import com.boat.dashcamcompanion.ui.LiveViewScreen
import com.boat.dashcamcompanion.ui.MapScreen
import com.boat.dashcamcompanion.ui.PotholeDetailScreen
import com.boat.dashcamcompanion.ui.PotholeListScreen
import com.boat.dashcamcompanion.ui.VideoTestScreen
import com.boat.dashcamcompanion.ui.theme.DashcamCompanionTheme
import com.boat.dashcamcompanion.wifi.DashcamWifiManager

class MainActivity : ComponentActivity() {
    /** Report to open when the app is started from a "Pothole detected" notification. */
    private var openReportId by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openReportId = intent.getStringExtra(PotholeAlerts.EXTRA_REPORT_ID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openReportId = intent?.getStringExtra(PotholeAlerts.EXTRA_REPORT_ID)

        val wifiManager = DashcamWifiManager(this)

        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { /* no-op: SSID/gateway reads just return null until granted */ }

            LaunchedEffect(Unit) {
                // Android hides the connected Wi-Fi name unless Location is granted, even on
                // Android 13+, so ask for it everywhere and add Nearby devices where it exists.
                val permissions = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        add(Manifest.permission.POST_NOTIFICATIONS) // drive-mode notification
                    }
                }
                permissionLauncher.launch(permissions.toTypedArray())
            }

            DashcamCompanionTheme {
                val nav = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = nav,
                        startDestination = "home",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        // A live camera feed should snap in immediately, not crossfade —
                        // the default crossfade briefly blends both screens' content together.
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None }
                    ) {
                        composable("home") {
                            HomeScreen(
                                onAddDashCam = { nav.navigate("install") },
                                onTestVideo = { nav.navigate("videotest") },
                                onPotholes = { nav.navigate("potholes") },
                                onMap = { nav.navigate("map") },
                                onPotholeSafety = { nav.navigate("pothole_safety") }
                            )
                        }
                        composable("pothole_safety") {
                            com.boat.dashcamcompanion.ui.PotholeSafetyScreen(onBack = { nav.popBackStack() })
                        }
                        composable("map") {
                            MapScreen(
                                onBack = { nav.popBackStack() },
                                onOpenReport = { reportId -> nav.navigate("pothole/$reportId") }
                            )
                        }
                        composable("potholes") {
                            PotholeListScreen(
                                onBack = { nav.popBackStack() },
                                onOpen = { reportId -> nav.navigate("pothole/$reportId") }
                            )
                        }
                        composable("pothole/{reportId}") { entry ->
                            PotholeDetailScreen(
                                reportId = entry.arguments?.getString("reportId") ?: "",
                                onClose = { nav.popBackStack() }
                            )
                        }
                        composable("videotest") {
                            VideoTestScreen(onBack = { nav.popBackStack() })
                        }
                        composable("install") {
                            InstallScreen(
                                onBack = { nav.popBackStack() },
                                onNext = { nav.navigate("connect") }
                            )
                        }
                        composable("connect") {
                            ConnectScreen(
                                wifiManager = wifiManager,
                                onBack = { nav.popBackStack() },
                                onContinueToLiveView = { gatewayIp ->
                                    nav.navigate("live/$gatewayIp")
                                }
                            )
                        }
                        composable("live/{gatewayIp}") { backStackEntry ->
                            val gatewayIp = backStackEntry.arguments?.getString("gatewayIp") ?: "192.168.1.254"
                            LiveViewScreen(gatewayIp = gatewayIp, onBack = { nav.popBackStack() })
                            // Leaving the live view: release the app's binding to the camera Wi-Fi
                            // (no internet), so the map, addresses etc. use the phone's internet again.
                            DisposableEffect(Unit) { onDispose { wifiManager.unbind() } }
                        }
                    }
                    // Opened from a "Pothole detected" notification: show that report
                    // (after the NavHost above has set up its routes).
                    val reportToOpen = openReportId
                    LaunchedEffect(reportToOpen) {
                        if (reportToOpen != null) {
                            // During a drive, just bring the app back: leaving the live view
                            // would end the drive. The report is in "Potholes found" afterwards.
                            if (!com.boat.dashcamcompanion.drive.TripRecorder.isActive) {
                                nav.navigate("pothole/$reportToOpen")
                            }
                            openReportId = null
                        }
                    }
                }
            }
        }
    }
}