package com.lightmeter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lightmeter.app.activation.ActivationManager
import com.lightmeter.app.activation.ActivationScreen
import com.lightmeter.app.settings.SharedPreferencesAppSettingsStore
import com.lightmeter.app.ui.AppRoute
import com.lightmeter.app.ui.theme.LightMeterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsStore = remember {
                SharedPreferencesAppSettingsStore(applicationContext)
            }
            var themeStyle by remember {
                mutableStateOf(settingsStore.load().themeStyle)
            }
            LightMeterTheme(themeStyle = themeStyle) {
                if (BuildConfig.DEBUG) {
                    // Debug build: no activation required
                    AppRoute(
                        themeStyle = themeStyle,
                        onThemeStyleChanged = { selected ->
                            if (settingsStore.save(
                                    settingsStore.load().copy(themeStyle = selected),
                                )
                            ) {
                                themeStyle = selected
                            }
                        },
                    )
                } else {
                    // Release build: activation required
                    var isActivated by remember {
                        mutableStateOf(ActivationManager.isActivated(this@MainActivity))
                    }

                    if (isActivated) {
                        AppRoute(
                            themeStyle = themeStyle,
                            onThemeStyleChanged = { selected ->
                                if (settingsStore.save(
                                        settingsStore.load().copy(themeStyle = selected),
                                    )
                                ) {
                                    themeStyle = selected
                                }
                            },
                        )
                    } else {
                        ActivationScreen(
                            onActivated = { isActivated = true },
                        )
                    }
                }
            }
        }
    }
}
