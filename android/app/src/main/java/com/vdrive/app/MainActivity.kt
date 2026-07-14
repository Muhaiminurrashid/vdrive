package com.vdrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vdrive.app.ui.auth.LoginScreen
import com.vdrive.app.ui.files.DashboardScreen
import com.vdrive.app.ui.navigation.Route
import com.vdrive.app.ui.theme.VDriveTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkTheme by remember { mutableStateOf(false) }
            VDriveTheme(darkTheme = isDarkTheme) {
                VDriveNavHost(isDarkTheme = isDarkTheme, onToggleTheme = { isDarkTheme = !isDarkTheme })
            }
        }
    }
}

@Composable
fun VDriveNavHost(isDarkTheme: Boolean, onToggleTheme: () -> Unit) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Route.Login.route) {
        composable(Route.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Route.Dashboard.route) {
                        popUpTo(Route.Login.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Route.Dashboard.route) {
            DashboardScreen(
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onSignOut = {
                    navController.navigate(Route.Login.route) {
                        popUpTo(Route.Dashboard.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
