package nl.wdhertog.library.covers

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverCropTest {

    @Test
    fun `frame is centered with book proportions`() {
        val frame = CoverCrop.frame(1000f, 2000f)
        assertEquals(820f, frame.width, 0.01f)
        assertEquals(1230f, frame.height, 0.01f)
        assertEquals(90f, frame.left, 0.01f)
        assertEquals(385f, frame.top, 0.01f)
    }

    @Test
    fun `frame fits a wide view by height`() {
        val frame = CoverCrop.frame(2000f, 1000f)
        assertEquals(820f, frame.height, 0.01f)
        assertEquals(820f / 1.5f, frame.width, 0.01f)
    }

    @Test
    fun `frame maps to image when preview is cropped at the sides`() {
        // Scherm 1000x2000, foto 3000x4000: schaal 0,5, links en rechts valt 250 px weg.
        val box = CoverCrop.toImage(Box(250f, 0f, 750f, 2000f), 1000f, 2000f, 3000, 4000)
        assertEquals(Box(1000f, 0f, 2000f, 4000f), box)
    }

    @Test
    fun `frame mapping stays within the image`() {
        val box = CoverCrop.toImage(Box(-50f, -50f, 1100f, 2100f), 1000f, 2000f, 1000, 2000)
        assertEquals(Box(0f, 0f, 1000f, 2000f), box)
    }
}
