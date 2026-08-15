/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.commonfeature.ui.qr_scan

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import eu.europa.ec.commonfeature.ui.qr_scan.component.QrCodeAnalyzer
import java.util.concurrent.Executors

/**
 * CameraX behind a `PreviewView`, with zxing reading the frames.
 *
 * Unchanged from the version that lived inside `QrScanScreen`, minus the framing brackets and the
 * permission-rationale message, which are the same on every platform and stayed with the screen.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
actual fun QrCameraSurface(
    modifier: Modifier,
    onAccess: (QrCameraAccess) -> Unit,
    onQrScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
    }
    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor()
    }
    val mainExecutor = remember(context) {
        ContextCompat.getMainExecutor(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    val permissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)
    when {
        permissionState.status.isGranted -> onAccess(QrCameraAccess.Granted)
        permissionState.status.shouldShowRationale -> onAccess(QrCameraAccess.NeedsExplanation)

        else -> {
            LaunchedEffect(Unit) {
                permissionState.launchPermissionRequest()
            }
        }
    }

    if (!permissionState.status.isGranted) return

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->

            val previewView = PreviewView(viewContext)
            val preview = Preview.Builder().build()

            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            preview.surfaceProvider = previewView.surfaceProvider

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(
                analysisExecutor,
                QrCodeAnalyzer { result ->
                    mainExecutor.execute {
                        onQrScanned(result)
                    }
                }
            )
            try {
                cameraProviderFuture.get().bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            previewView
        }
    )
}
