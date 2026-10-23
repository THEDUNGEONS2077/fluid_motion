/*
 * render/PowerWatch.kt: the phone's power state as the frame rate cares
 * about it (lib/Power.kt PowerState): battery saver, how hot it is, and
 * which refresh rates the display can run at (they change with the
 * phone's motion smoothness setting).
 *
 * Layer: render (Android: PowerManager). One watcher for the whole process
 * (the home screen's wallpaper, the lock screen's, the picker's preview and
 * the app may all be drawing): it listens only while someone is, and tells
 * them all on the main thread.
 */
package com.thedungeons2077.fluidmotion.render

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import com.thedungeons2077.fluidmotion.lib.PowerState

/** Tells listeners about battery saver and thermal status changes. */
object PowerWatch {
    private val listeners = LinkedHashSet<(PowerState) -> Unit>()
    private var app: Context? = null
    private var power: PowerManager? = null

    /** The state now (as of the last change heard). */
    var state = PowerState()
        private set

    private val saverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update()
    }

    // Made only on Android 10+ (the type doesn't exist before: making it in a field would fail on 8 and 9).
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private var displays: DisplayManager? = null

    // The display's modes can change (motion smoothness, a resolution change): read them again.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) update()
        }
    }

    /** Starts telling [listener] (main thread), with the state now. Call on the main thread. */
    fun add(context: Context, listener: (PowerState) -> Unit) {
        if (listeners.isEmpty()) start(context.applicationContext)
        listeners.add(listener)
        listener(state)
    }

    /** Stops telling [listener]; the last one out stops listening to the system. */
    fun remove(listener: (PowerState) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) stop()
    }

    private fun start(context: Context) {
        app = context
        val pm = context.getSystemService(PowerManager::class.java)
        power = pm
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        // A system broadcast: only the system can send it, so the receiver needn't be open to other apps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(saverReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(saverReceiver, filter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val l = PowerManager.OnThermalStatusChangedListener { update() }
            thermalListener = l
            pm.addThermalStatusListener(context.mainExecutor, l)
        }
        val dm = context.getSystemService(DisplayManager::class.java)
        displays = dm
        dm.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        state = read()
    }

    private fun stop() {
        val context = app ?: return
        context.unregisterReceiver(saverReceiver)
        displays?.unregisterDisplayListener(displayListener)
        displays = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalListener?.let { power?.removeThermalStatusListener(it) }
        thermalListener = null
        app = null
        power = null
    }

    /** Reads the state again and tells everyone if it changed. */
    private fun update() {
        val next = read()
        if (next == state) return
        state = next
        for (l in listeners.toList()) l(next)
    }

    private fun read(): PowerState {
        val pm = power ?: return PowerState()
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
        val modes = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted() ?: emptyList()
        return PowerState(powerSave = pm.isPowerSaveMode, thermal = thermal, refreshRates = modes)
    }
}
