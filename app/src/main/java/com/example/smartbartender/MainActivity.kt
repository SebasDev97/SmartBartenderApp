package com.example.smartbartender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.smartbartender.ui.navigation.SmartBartenderApp
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.SmartBartenderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartBartenderTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Obsidian),
                ) {
                    SmartBartenderApp()
                }
            }
        }
    }
}
