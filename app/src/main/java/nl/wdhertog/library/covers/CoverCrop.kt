package nl.wdhertog.library.covers

import kotlin.math.max
import kotlin.math.min

/** Rechthoek in pixels; los van android.graphics zodat het in gewone unit-tests werkt. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Rekenwerk voor het kader waarin je de cover fotografeert. */
object CoverCrop {
    /** Hoogte / breedte van het kader; de meeste boeken zitten rond 2:3. */
    const val ASPECT = 1.5f
    private const val FILL = 0.82f

    /** Het kader op het scherm: zo groot mogelijk, gecentreerd, met wat ruimte eromheen. */
    fun frame(viewWidth: Float, viewHeight: Float): Box {
        val width = min(viewWidth * FILL, viewHeight * FILL / ASPECT)
        val height = width * ASPECT
        val left = (viewWidth - width) / 2
        val top = (viewHeight - height) / 2
        return Box(left, top, left + width, top + height)
    }

    /**
     * Het kader omgerekend naar pixels van de (rechtgezette) foto. Het camerabeeld vult het
     * scherm en valt aan twee kanten weg (FILL_CENTER); de foto heeft dezelfde verhouding.
     */
    fun toImage(frame: Box, viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): Box {
        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
        val offsetX = (viewWidth - imageWidth * scale) / 2
        val offsetY = (viewHeight - imageHeight * scale) / 2
        return Box(
            left = ((frame.left - offsetX) / scale).coerceIn(0f, imageWidth.toFloat()),
            top = ((frame.top - offsetY) / scale).coerceIn(0f, imageHeight.toFloat()),
            right = ((frame.right - offsetX) / scale).coerceIn(0f, imageWidth.toFloat()),
            bottom = ((frame.bottom - offsetY) / scale).coerceIn(0f, imageHeight.toFloat()),
        )
    }
}
