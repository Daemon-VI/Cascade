package com.rishi.cascade

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rishi.cascade.ui.CascadeTheme
import com.rishi.cascade.ui.EditorScreen
import com.rishi.cascade.ui.HomeScreen
import com.rishi.cascade.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CascadeTheme {
                CascadeRoot()
            }
        }
    }
}

@Composable
private fun CascadeRoot() {
    var screen by rememberSaveable { mutableStateOf("home") }
    var editing by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = screen != "home") { screen = "home" }

    when (screen) {
        "editor" -> EditorScreen(editing) { screen = "home" }
        "settings" -> SettingsScreen { screen = "home" }
        else -> HomeScreen(
            onOpen = { id -> editing = id; screen = "editor" },
            onSettings = { screen = "settings" }
        )
    }
}
