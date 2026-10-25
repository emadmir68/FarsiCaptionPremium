package com.farsicaption.premium

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/** Render the actual production composables, not a separate HTML mockup. */
class StudioRenderTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig=DeviceConfig.PIXEL_5, theme="android:Theme.Material.Light.NoActionBar")
    @Test fun homeReady() { paparazzi.snapshot { StudioTheme { HomeScreen(true,"Whisper Turbo Q5","~548MB",{},true,{}) } } }
    @Test fun homeFirstRunLargeText() { paparazzi.snapshot {
        val d=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density,1.3f)) {
            StudioTheme { HomeScreen(false,"Whisper Turbo Q5","~548MB",{},false,{}) }
        }
    } }
    @Test fun landscapeStyleControls() { paparazzi.snapshot { StudioTheme {
        Surface(color=Ink, modifier=Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical=24.dp)) {
                StyleEditor("luxury",90f,true,120f,"cream","none",{},{},{},{},{})
            }
        }
    } } }
    @Test fun captionBeyondSixtyRemainsEditable() { paparazzi.snapshot { StudioTheme {
        Surface(color=Ink, modifier=Modifier.fillMaxSize()) {
            Column(Modifier.padding(vertical=24.dp)) {
                TextEditor(List(75){ CaptionItem("هر صدا، یک داستان تازه دارد",it.toDouble(),it+1.0) },74,{},{_,_->},{_,_->})
            }
        }
    } } }
    @Test fun transcriptionControls() { paparazzi.snapshot { StudioTheme {
        Surface(color=Ink,modifier=Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical=24.dp)) {
                AutoCaptionPanel(5f,{},{_,_->})
            }
        }
    } } }
    @Test fun onlineKeySetup() { paparazzi.snapshot { StudioTheme {
        Surface(color=Ink,modifier=Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                GroqKeyFields(false,"","",true,{},{},{},{})
            }
        }
    } } }
}
