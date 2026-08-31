package com.boat.dashcamcompanion

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.boat.dashcamcompanion.ui.ConnectScreen
import com.boat.dashcamcompanion.ui.HomeScreen
import com.boat.dashcamcompanion.ui.InstallScreen
import com.boat.dashcamcompanion.ui.LiveViewScreen
import com.boat.dashcamcompanion.ui.theme.DashcamCompanionTheme
import com.boat.dashcamcompanion.wifi.DashcamWifiManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wifiManager = DashcamWifiManager(this)

        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { /* no-op: SSID/gateway reads just return null until granted */ }

            LaunchedEffect(Unit) {
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.NEARBY_WIFI_DEVICES
                } else {
                    Manifest.permission.ACCESS_FINE_LOCATION
                }
                permissionLauncher.launch(arrayOf(permission))
            }

            DashcamCompanionTheme {
                val nav = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    NavHost(
                        navController = nav,
                        startDestination = "home",
                        modifier = Modifier.fillMaxSize(),
                        // A live camera feed should snap in immediately, not crossfade —
                        // the default crossfade briefly blends both screens' content together.
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None }
                    ) {
                        composable("home") {
                            HomeScreen(onAddDashCam = { nav.navigate("install") })
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
                        }
                    }
                }
            }
        }
    }
}
