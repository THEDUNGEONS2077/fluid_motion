/*
 * ui/PreviewSurface.kt: the selected wallpaper running behind the app's
 * controls, drawn by the same RenderLoop as on the home screen (so what the
 * app shows, rates and all, is what the wallpaper does).
 *
 * Layer: ui (a SurfaceView inside Compose). A SurfaceView, not a
 * TextureView: its frames go straight to the compositor, with no extra copy
 * through the app's own drawing each frame.
 */
package com.thedungeons2077.fluidmotion.ui

import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.thedungeons2077.fluidmotion.render.RenderLoop

/** The preview: [loop] draws into it while it exists. */
@Composable
fun PreviewSurface(loop: RenderLoop, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                // Opaque: the compositor needn't blend what's behind it.
                holder.setFormat(PixelFormat.RGBX_8888)
                holder.addCallback(object : SurfaceHolder.Callback2 {
                    override fun surfaceCreated(holder: SurfaceHolder) = loop.surfaceCreated(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = loop.surfaceChanged(width, height)
                    override fun surfaceDestroyed(holder: SurfaceHolder) = loop.surfaceDestroyed()
                    override fun surfaceRedrawNeeded(holder: SurfaceHolder) = loop.redraw()
                })
            }
        },
    )
}
