package nl.wdhertog.library.covers

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlin.math.roundToInt

/** Camerafoto rechtzetten en uitsnijden tot het kader dat op het scherm stond (zie CoverCrop). */
object CoverImages {

    fun cropToFrame(image: ImageProxy, onScreen: Box, viewWidth: Float, viewHeight: Float): Bitmap = image.use {
        val upright = it.toBitmap().rotate(it.imageInfo.rotationDegrees)
        val frame = CoverCrop.toImage(onScreen, viewWidth, viewHeight, upright.width, upright.height)
        val left = frame.left.roundToInt()
        val top = frame.top.roundToInt()
        Bitmap.createBitmap(
            upright,
            left,
            top,
            frame.width.roundToInt().coerceAtMost(upright.width - left).coerceAtLeast(1),
            frame.height.roundToInt().coerceAtMost(upright.height - top).coerceAtLeast(1),
        )
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap =
        if (degrees == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}
