package nl.wdhertog.library.ui.cover

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.camera.core.ImageProxy
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.wdhertog.library.covers.Box
import nl.wdhertog.library.covers.CoverImages
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.ui.navigation.CoverRoute

sealed interface CoverUiState {
    data object Camera : CoverUiState
    data object Processing : CoverUiState
    data class Review(val bitmap: Bitmap) : CoverUiState
}

class CoverViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: BookRepository,
    private val store: CoverStore,
) : ViewModel() {

    private val bookId = savedStateHandle.toRoute<CoverRoute>().bookId

    val title = MutableStateFlow("")
    val state = MutableStateFlow<CoverUiState>(CoverUiState.Camera)
    val torchOn = MutableStateFlow(false)

    init {
        viewModelScope.launch { title.value = repository.getBook(bookId)?.displayTitle.orEmpty() }
    }

    /** Foto van de camera: rechtzetten en uitsnijden tot het kader dat op het scherm stond. */
    fun onCaptured(image: ImageProxy, frame: Box, viewWidth: Float, viewHeight: Float) {
        state.value = CoverUiState.Processing
        viewModelScope.launch {
            val cropped = withContext(Dispatchers.Default) { CoverImages.cropToFrame(image, frame, viewWidth, viewHeight) }
            state.value = CoverUiState.Review(cropped)
        }
    }

    fun onCaptureFailed() {
        state.value = CoverUiState.Camera
    }

    /** Afbeelding uit de galerij of een andere app; die wordt niet bijgesneden. */
    fun onPicked(resolver: ContentResolver, uri: Uri) {
        state.value = CoverUiState.Processing
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { decode(resolver, uri) }.getOrNull() }
            state.value = bitmap?.let(CoverUiState::Review) ?: CoverUiState.Camera
        }
    }

    fun retake() {
        state.value = CoverUiState.Camera
    }

    fun save(onSaved: () -> Unit) {
        val review = state.value as? CoverUiState.Review ?: return
        state.value = CoverUiState.Processing
        viewModelScope.launch {
            repository.setCoverFile(bookId, store.save(review.bitmap))
            onSaved()
        }
    }

    private fun decode(resolver: ContentResolver, uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Houdt rekening met de draaiing uit de EXIF-gegevens.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_DECODE) decoder.setTargetSampleSize(longest / MAX_DECODE)
            }
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: error("Onleesbaar beeld")
        }

    private companion object {
        const val MAX_DECODE = 2400
    }
}
