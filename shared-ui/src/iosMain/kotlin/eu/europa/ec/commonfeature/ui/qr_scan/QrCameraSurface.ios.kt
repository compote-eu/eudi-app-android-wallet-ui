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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * AVFoundation behind a `UIKitView`.
 *
 * iOS decodes QR codes inside the capture session — `AVCaptureMetadataOutput` emits the string
 * directly — so there is no analyser and no decoding library here, which is why the Android half needs
 * zxing and this needs nothing.
 *
 * **Written but unproven, and it says so where it matters.** The iOS Simulator has no camera:
 * `AVCaptureDevice.defaultDeviceWithMediaType` returns null there, so this reports
 * [QrCameraAccess.Denied] and the screen shows its brackets over black. That is the same position
 * proximity's BLE half is in — the code is here, the hardware is not — and a first device run should
 * expect to debug the session and the preview layer's geometry rather than the decoding.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrCameraSurface(
    modifier: Modifier,
    onAccess: (QrCameraAccess) -> Unit,
    onQrScanned: (String) -> Unit,
) {
    var access by remember { mutableStateOf(currentCameraAccess()) }

    // Asking is a one-shot: iOS prompts on the first request and answers from its record afterwards,
    // so this runs once and the answer drives everything below.
    LaunchedEffect(Unit) {
        if (AVCaptureDevice.Companion.authorizationStatusForMediaType(AVMediaTypeVideo) ==
            AVAuthorizationStatusNotDetermined
        ) {
            AVCaptureDevice.Companion.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                // The completion handler arrives on an arbitrary queue; the state it sets is read by
                // composition, so it is hopped onto the main one.
                dispatch_async(dispatch_get_main_queue()) {
                    access = if (granted) QrCameraAccess.Granted else QrCameraAccess.NeedsExplanation
                }
            }
        }
    }

    LaunchedEffect(access) { onAccess(access) }

    if (access != QrCameraAccess.Granted) return

    // Held across recompositions: a session rebuilt on every frame of state would restart the camera.
    val scanner = remember { QrScannerSession(onQrScanned) }
    DisposableEffect(scanner) {
        scanner.start()
        onDispose { scanner.stop() }
    }

    UIKitView(
        modifier = modifier,
        factory = { scanner.view },
    )
}

/** What iOS currently says about camera access, before anything has been asked. */
private fun currentCameraAccess(): QrCameraAccess =
    when (AVCaptureDevice.Companion.authorizationStatusForMediaType(AVMediaTypeVideo)) {
        AVAuthorizationStatusAuthorized -> QrCameraAccess.Granted
        // Denied is recoverable through Settings, so the screen offers that; restricted is not — a
        // managed device forbids it, and there is nothing the user can do.
        AVAuthorizationStatusDenied -> QrCameraAccess.NeedsExplanation
        AVAuthorizationStatusRestricted -> QrCameraAccess.Denied
        // Not asked yet. Nothing to show until the answer arrives.
        else -> QrCameraAccess.Denied
    }

/**
 * One capture session and the view it draws into.
 *
 * A class rather than a pile of `remember`s because the delegate has to be an `NSObject` held strongly
 * for as long as the session runs — `AVCaptureMetadataOutput` keeps only a weak reference to it, so a
 * delegate that is merely a lambda is deallocated and the scanner silently reads nothing.
 */
@OptIn(ExperimentalForeignApi::class)
private class QrScannerSession(private val onQrScanned: (String) -> Unit) {

    /**
     * A view that keeps the preview layer at its own bounds.
     *
     * The layer is not a view and takes no part in layout, so something has to resize it; overriding
     * `layoutSubviews` is the UIKit way and means the Compose side needs no resize callback — which
     * matters, because the interop `UIKitView` it is hosted in no longer offers one.
     */
    val view: UIView = object : UIView(frame = CGRectZero.readValue()) {
        override fun layoutSubviews() {
            super.layoutSubviews()
            previewLayer.setFrame(bounds)
        }
    }

    private val session = AVCaptureSession().apply { sessionPreset = AVCaptureSessionPresetHigh }

    val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    private val delegate = object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
        override fun captureOutput(
            output: AVCaptureOutput,
            didOutputMetadataObjects: List<*>,
            fromConnection: platform.AVFoundation.AVCaptureConnection,
        ) {
            didOutputMetadataObjects
                .filterIsInstance<AVMetadataMachineReadableCodeObject>()
                .firstNotNullOfOrNull { it.stringValue }
                ?.let(onQrScanned)
        }
    }

    init {
        view.layer.addSublayer(previewLayer)
        configure()
    }

    private fun configure() {
        val camera = AVCaptureDevice.Companion.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return
        val input = AVCaptureDeviceInput.Companion.deviceInputWithDevice(camera, null) ?: return
        if (!session.canAddInput(input)) return
        session.addInput(input)

        val output = AVCaptureMetadataOutput()
        if (!session.canAddOutput(output)) return
        session.addOutput(output)
        // Set *after* the output is attached: the available metadata types are empty until then, so
        // asking for QR codes first throws.
        output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
    }

    /** Off the main thread: `startRunning` blocks while the camera warms up. */
    fun start() {
        if (session.isRunning()) return
        platform.darwin.dispatch_async(
            platform.darwin.dispatch_get_global_queue(0, 0uL)
        ) { session.startRunning() }
    }

    fun stop() {
        if (session.isRunning()) session.stopRunning()
    }
}
