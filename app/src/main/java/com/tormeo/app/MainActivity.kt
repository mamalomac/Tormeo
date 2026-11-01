package com.tormeo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.tormeo.app.ui.AppRoot
import com.tormeo.app.ui.TormeoTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { runCatching { TormeoApp.instance.addons.bootstrap() } }
        setContent { TormeoTheme { AppRoot() } }
    }
}
