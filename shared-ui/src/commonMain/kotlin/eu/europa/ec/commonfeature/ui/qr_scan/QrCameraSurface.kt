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
import androidx.compose.ui.Modifier

/** What the platform's camera-permission story has decided. */
enum class QrCameraAccess {
    Granted,

    /** Refused, and the platform thinks an explanation would help — the screen offers Settings. */
    NeedsExplanation,

    /** Refused, with nothing useful to say about it, or no camera at all. */
    Denied,
}

/**
 * A live camera view that reports the QR codes it reads.
 *
 * **The whole of the QR scanner's platform half, and deliberately so.** Everything around it — the
 * title, the informative text after too many bad scans, the framing brackets, the offer to open
 * Settings — is the same on both platforms and lives in `QrScanScreen`. What genuinely differs is
 * asking for camera permission and turning frames into strings: CameraX and zxing on Android,
 * AVFoundation on iOS, which decodes in the capture session itself.
 *
 * Permission belongs on this side rather than beside it, because the two are one conversation: the
 * preview cannot start before the answer, and each platform's answer arrives its own way. [onAccess]
 * is how that answer reaches the shared screen, which decides what to *show* about it.
 *
 * [onQrScanned] may fire repeatedly for the same code while it stays in frame; the view-model already
 * expects that and ignores scans once one has been accepted.
 */
@Composable
expect fun QrCameraSurface(
    modifier: Modifier,
    onAccess: (QrCameraAccess) -> Unit,
    onQrScanned: (String) -> Unit,
)
