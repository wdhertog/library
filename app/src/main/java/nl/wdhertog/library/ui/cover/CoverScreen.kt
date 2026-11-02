package nl.wdhertog.library.ui.cover

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.wdhertog.library.covers.Box
import nl.wdhertog.library.covers.CoverCrop
import nl.wdhertog.library.ui.navigation.appViewModel

/** Cover fotograferen binnen een kader, of een afbeelding kiezen; daarna bekijken en bewaren. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverScreen(
    onClose: () -> Unit,
    viewModel: CoverViewModel = appViewModel { container, handle ->
        CoverViewModel(handle, container.bookRepository, container.coverStore)
    },
) {
    val context = LocalContext.current
    val title by viewModel.title.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val torchOn by viewModel.torchOn.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.onPicked(context.contentResolver, it) }
    }
    val pickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cover")
                        if (title.isNotEmpty()) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Sluiten") }
                },
                actions = {
                    if (state == CoverUiState.Camera && hasPermission) {
                        IconButton(onClick = { viewModel.torchOn.value = !torchOn }) {
                            Icon(
                                if (torchOn) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn,
                                contentDescription = if (torchOn) "Lamp uit" else "Lamp aan",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            when (val s = state) {
                CoverUiState.Camera ->
                    if (hasPermission) {
                        CoverCamera(torchOn = torchOn, onCaptured = viewModel::onCaptured, onFailed = viewModel::onCaptureFailed, onPick = pickImage)
                    } else {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Voor een foto is toegang tot de camera nodig.", color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Camera toestaan") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = pickImage) { Text("Afbeelding kiezen") }
                        }
                    }
                CoverUiState.Processing -> CircularProgressIndicator()
                is CoverUiState.Review -> Review(s, onRetake = viewModel::retake, onSave = { viewModel.save(onSaved = onClose) })
            }
        }
    }
}

/** Camerabeeld met boekkader en sluiterknop; ook gebruikt voor cover-herkenning bij het scannen. */
@Composable
fun CoverCamera(
    torchOn: Boolean,
    onCaptured: (image: ImageProxy, frame: Box, viewWidth: Float, viewHeight: Float) -> Unit,
    onFailed: () -> Unit,
    onPick: (() -> Unit)?,
    hint: String = "Zet de voorkant binnen het kader",
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    LaunchedEffect(torchOn) { controller.enableTorch(torchOn) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    // Ruimte voor de uitleg bovenaan en de sluiterknop onderaan, zodat die het kader niet bedekken.
    val topInset = with(density) { 44.dp.toPx() }
    val bottomInset = with(density) { 128.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize().clipToBounds(),
            )
            CoverFrame(topInset, bottomInset)
        }
        Text(
            hint,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onPick != null) {
                IconButton(onClick = onPick) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Afbeelding kiezen", tint = Color.White)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            LargeFloatingActionButton(onClick = {
                val width = viewSize.width.toFloat()
                val height = viewSize.height.toFloat()
                val frame = CoverCrop.frame(width, height, topInset, bottomInset)
                controller.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) = onCaptured(image, frame, width, height)
                        override fun onError(exception: ImageCaptureException) = onFailed()
                    },
                )
            }) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Foto maken", Modifier.size(36.dp))
            }
            // Houdt de sluiterknop in het midden.
            Spacer(Modifier.size(48.dp))
        }
    }
}

/** Alles buiten het kader wat donkerder, zodat duidelijk is wat er bewaard wordt. */
@Composable
private fun CoverFrame(topInset: Float, bottomInset: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val frame = CoverCrop.frame(size.width, size.height, topInset, bottomInset)
        val corner = CornerRadius(8.dp.toPx())
        val hole = Path().apply {
            addRoundRect(RoundRect(frame.left, frame.top, frame.right, frame.bottom, corner))
        }
        clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.5f)) }
        drawRoundRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = Offset(frame.left, frame.top),
            size = Size(frame.width, frame.height),
            cornerRadius = corner,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun Review(state: CoverUiState.Review, onRetake: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = remember(state.bitmap) { state.bitmap.asImageBitmap() },
            contentDescription = "Gemaakte foto",
            modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
        )
        Row(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onRetake) { Text("Opnieuw") }
            Button(onClick = onSave) { Text("Gebruiken") }
        }
    }
}
