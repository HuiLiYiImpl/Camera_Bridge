package com.yaoyihan.nikonconnect.lut

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yaoyihan.nikonconnect.CubeLut

/** Fits each incoming frame without zoom/pan gestures, preserving tap-to-focus coordinates. */
@Composable
internal fun RemoteLiveLutSurface(bitmap: Bitmap, lut: CubeLut, intensity: Float, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { LutGlView(context) }
    DisposableEffect(view, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.setPreview(null, null, 1f)
            view.onPause()
        }
    }
    AndroidView(factory = { view }, modifier = modifier,
        update = { it.setPreview(bitmap, lut, intensity) })
}
