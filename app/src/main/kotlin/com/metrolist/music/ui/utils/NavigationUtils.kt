/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import androidx.navigation.NavController
import com.metrolist.music.ui.screens.Screens

fun NavController.backToMain() {
    navigate(Screens.Home.route) {
        popUpTo(graph.startDestinationId) {
            inclusive = false
        }
        launchSingleTop = true
    }
}
