/*
 * ui/MainActivity.kt: the app: a preview of the wallpapers and their
 * settings (the system's wallpaper picker opens it as their settings too).
 *
 * Layer: ui (Android activity). It owns the preview's render loop: drawing
 * while the app is in front (onStart → onStop), stopped otherwise, freed
 * when the app closes.
 */
package com.thedungeons2077.fluidmotion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thedungeons2077.fluidmotion.lib.PowerState
import com.thedungeons2077.fluidmotion.render.PowerWatch
import com.thedungeons2077.fluidmotion.render.RenderLoop
import com.thedungeons2077.fluidmotion.settings.Settings

/** The app's activity. */
class MainActivity : ComponentActivity() {
    private lateinit var loop: RenderLoop
    private lateinit var state: HomeState
    private val onPower: (PowerState) -> Unit = { state.onPower(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        loop = RenderLoop(applicationContext, settings.selected, "preview")
        state = HomeState(this, settings, loop)
        // Stats come from the loop's thread: hand them to the UI's.
        loop.onStats = { s -> runOnUiThread { state.stats = s } }
        setContent { HomeScreen(state, loop) }
    }

    override fun onStart() {
        super.onStart()
        state.started = true
        state.refreshActive()
        PowerWatch.add(this, onPower)
        loop.setVisible(true)
    }

    override fun onStop() {
        // MEASURE IMPACT and AUTO CALIBRATE count only with the app in front.
        state.cancelRun()
        loop.setVisible(false)
        PowerWatch.remove(onPower)
        state.started = false
        super.onStop()
    }

    override fun onDestroy() {
        loop.onStats = null
        loop.release()
        super.onDestroy()
    }
}
