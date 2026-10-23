/*
 * render/UsageStore.kt: where INSIGHT's log is kept: SharedPreferences
 * ("fluid_motion_usage"), a line an hour, a week back (lib/Usage.kt).
 *
 * Layer: render (Android). One store for the process: the home screen's
 * and the lock screen's wallpapers may both be adding to it, each from its
 * own render thread, so a merge is one locked read, add and write. Writes
 * are rare (a wallpaper going off screen, or every few minutes while one
 * is on) and asynchronous (apply).
 */
package com.thedungeons2077.fluidmotion.render

import android.content.Context
import android.content.SharedPreferences
import com.thedungeons2077.fluidmotion.lib.Usage
import com.thedungeons2077.fluidmotion.lib.UsageHour

/** INSIGHT's log. */
object UsageStore {
    private const val FILE = "fluid_motion_usage"
    private const val KEY = "hours"

    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Adds [deltas] to the log, dropping what's older than a week (any thread). */
    fun merge(context: Context, deltas: Collection<UsageHour>, nowMs: Long) = synchronized(this) {
        if (deltas.all { it.empty }) return@synchronized
        val p = prefs(context)
        val log = Usage.decode(p.getString(KEY, "") ?: "")
        val merged = Usage.merge(log, deltas, keepFrom = Usage.hourOf(nowMs) - Usage.KEEP_HOURS)
        val editor = p.edit()
        editor.putString(KEY, Usage.encode(merged))
        editor.apply()
    }

    /** The log as it stands. */
    fun read(context: Context): List<UsageHour> = synchronized(this) { Usage.decode(prefs(context).getString(KEY, "") ?: "") }

    /** Calls [onChange] (main thread) when the log changes, until the handle is closed (keep it: listeners are held weakly). */
    fun observe(context: Context, onChange: () -> Unit): AutoCloseable {
        val p = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        p.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
