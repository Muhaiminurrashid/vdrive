package com.vdrive.app.ui.navigation

sealed class Route(val route: String) {
    data object Login : Route("login")
    data object Dashboard : Route("dashboard")
}
