package nl.wdhertog.library.scan

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import nl.wdhertog.library.lookup.search.CoverLine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Tekst op een cover lezen, volledig op het toestel (ML Kit, Latijns schrift). */
object CoverRecognizer {

    suspend fun recognize(bitmap: Bitmap): List<CoverLine> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        cont.resume(
                            text.textBlocks.flatMap { it.lines }
                                .map { CoverLine(it.text, it.boundingBox?.height() ?: 0) },
                        )
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
    }
}
