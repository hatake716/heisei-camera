package io.github.hatake716.heiseicamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.Surface
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner

enum class CameraPreviewState { STARTING, STREAMING, FAILED }

/** Back-camera viewfinder only. No ImageCapture, ImageAnalysis, VideoCapture, or pixel copying. */
@SuppressLint("MissingPermission")
@Composable
fun LiveCameraPreview(
    onState: (CameraPreviewState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCallback = rememberUpdatedState(onState)
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        var disposed = false
        var failed = false
        var streaming = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var cameraStates: LiveData<CameraState>? = null
        fun publish() {
            if (!disposed) currentCallback.value(
                when {
                    failed -> CameraPreviewState.FAILED
                    streaming -> CameraPreviewState.STREAMING
                    else -> CameraPreviewState.STARTING
                },
            )
        }
        val streamObserver = Observer<PreviewView.StreamState> { state ->
            streaming = state == PreviewView.StreamState.STREAMING
            publish()
        }
        val cameraObserver = Observer<CameraState> { state ->
            failed = state.error != null
            publish()
        }
        val layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.display?.rotation?.let { preview?.targetRotation = it }
        }
        previewView.addOnLayoutChangeListener(layoutListener)
        previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
        publish()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            failed = true
            publish()
        } else {
            try {
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    if (!disposed) {
                        try {
                            val cameraProvider = future.get()
                            provider = cameraProvider
                            if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                                failed = true
                                publish()
                            } else {
                                val useCase = Preview.Builder()
                                    .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                                    .build()
                                preview = useCase
                                useCase.setSurfaceProvider(previewView.surfaceProvider)
                                val camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCase,
                                )
                                cameraStates = camera.cameraInfo.cameraState.also {
                                    it.observe(lifecycleOwner, cameraObserver)
                                }
                            }
                        } catch (_: Exception) {
                            failed = true
                            publish()
                        }
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (_: Exception) {
                failed = true
                publish()
            }
        }

        onDispose {
            // Releasing the viewfinder when showing the past scene also releases the camera.
            disposed = true
            previewView.removeOnLayoutChangeListener(layoutListener)
            previewView.previewStreamState.removeObserver(streamObserver)
            cameraStates?.removeObserver(cameraObserver)
            preview?.let { useCase ->
                provider?.unbind(useCase)
                useCase.setSurfaceProvider(null)
            }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
