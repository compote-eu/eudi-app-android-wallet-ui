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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.commonfeature.ui.qr_scan.component.qrBorderCanvas
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.platform.PlatformContext
import eu.europa.ec.uilogic.component.PlatformScreenActions
import eu.europa.ec.uilogic.component.rememberPlatformContextOrNull
import eu.europa.ec.uilogic.component.rememberPlatformScreenActions
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.qr_scan_informative_text_presentation_flow
import eu.europa.ec.shared.resources.qr_scan_permission_not_granted
import eu.europa.ec.shared.resources.resolve
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ErrorInfo
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ContentTitle
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.SIZE_100
import eu.europa.ec.uilogic.component.utils.SIZE_EXTRA_SMALL
import eu.europa.ec.uilogic.component.utils.SIZE_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.utils.screenWidthInDp
import eu.europa.ec.uilogic.component.wrap.WrapCard
import eu.europa.ec.uilogic.component.wrap.WrapIcon
import eu.europa.ec.uilogic.extension.paddingFrom
import eu.europa.ec.uilogic.extension.throttledClickable
import eu.europa.ec.uilogic.navigation.helper.navigateReplacingCurrent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import org.jetbrains.compose.resources.stringResource

@Composable
fun QrScanScreen(
    navigator: AppNavigator,
    viewModel: QrScanViewModel
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    // Null on iOS, where the type is uninhabited. Only the RQES hand-off needs it, and only Android
    // offers that flow — so a null here can never reach a caller that would have used it.
    val context = rememberPlatformContextOrNull()
    val screenActions = rememberPlatformScreenActions()

    ContentScreen(
        isLoading = false,
        navigatableAction = ScreenNavigateAction.BACKABLE,
        onBack = { viewModel.setEvent(Event.GoBack) },
    ) { paddingValues ->
        Content(
            context = context,
            state = state,
            effectFlow = viewModel.effect,
            onEventSend = { viewModel.setEvent(it) },
            onNavigationRequested = { navigationEffect ->
                handleNavigationEffect(screenActions, navigationEffect, navigator)
            },
            paddingValues = paddingValues,
        )
    }
}

private fun handleNavigationEffect(
    screenActions: PlatformScreenActions,
    navigationEffect: Effect.Navigation,
    navigator: AppNavigator
) {
    when (navigationEffect) {
        is Effect.Navigation.SwitchScreen -> {
            navigator.navigateReplacingCurrent(navigationEffect.route)
        }

        is Effect.Navigation.Pop -> {
            navigator.pop()
        }

        is Effect.Navigation.GoToAppSettings -> screenActions.openAppSettings()
    }
}

@Composable
private fun Content(
    context: PlatformContext?,
    state: State,
    effectFlow: Flow<Effect>,
    onEventSend: (Event) -> Unit,
    onNavigationRequested: (navigationEffect: Effect.Navigation) -> Unit,
    paddingValues: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .paddingFrom(paddingValues, bottom = false, start = false, end = false),
        verticalArrangement = Arrangement.spacedBy(SPACING_LARGE.dp)
    ) {
        ContentTitle(
            modifier = Modifier
                .fillMaxWidth()
                .paddingFrom(paddingValues, bottom = false, top = false),
            title = state.title.resolve(),
            subtitle = state.subtitle.resolve(),
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            OpenCamera(
                hasCameraPermission = state.hasCameraPermission,
                shouldShowPermissionRational = state.shouldShowPermissionRational,
                onEventSend = onEventSend,
                onQrScanned = { qrCode ->
                    // The context is only read on the signature flow, which needs the RQES SDK and so
                    // exists on Android alone; dropping the scan when there is none is better than a
                    // scanner that appears to work and then does nothing.
                    context?.let { onEventSend(Event.OnQrScanned(context = it, resultQr = qrCode)) }
                }
            )

            AnimatedInformativeText(state = state, paddingValues = paddingValues)
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)
            }
        }.collect()
    }
}

@Composable
private fun AnimatedInformativeText(state: State, paddingValues: PaddingValues) {
    AnimatedVisibility(visible = state.showInformativeText) {
        Box(
            modifier = Modifier.padding(
                paddingValues.calculateBottomPadding()
            )
        ) {
            InformativeText(text = state.informativeText.resolve())
        }
    }
}

@Composable
private fun OpenCamera(
    hasCameraPermission: Boolean,
    shouldShowPermissionRational: Boolean,
    onEventSend: (Event) -> Unit,
    onQrScanned: (String) -> Unit,
) {
    val scannerAreaSize = screenWidthInDp(true) - SIZE_100.dp

    // The space the camera is going to occupy. Black underneath, so the framing brackets read the same
    // before the preview arrives as after.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color.Black),
        contentAlignment = Alignment.Center
    ) {
        QrCameraSurface(
            modifier = Modifier.fillMaxSize(),
            onAccess = { access ->
                onEventSend(
                    when (access) {
                        QrCameraAccess.Granted -> Event.CameraAccessGranted
                        QrCameraAccess.NeedsExplanation -> Event.ShowPermissionRational
                        // Nothing useful to say and nothing to offer: the brackets stay, the
                        // informative text does not appear, and the user can go back.
                        QrCameraAccess.Denied -> Event.ShowPermissionRational
                    }
                )
            },
            onQrScanned = onQrScanned,
        )

        if (!hasCameraPermission && shouldShowPermissionRational) {
            ErrorInfo(
                modifier = Modifier.throttledClickable { onEventSend(Event.GoToAppSettings) },
                informativeText = stringResource(Res.string.qr_scan_permission_not_granted),
                contentColor = Color.White,
                isIconEnabled = true,
            )
        }

        // Draw indicators.
        Canvas(
            modifier = Modifier.size(scannerAreaSize)
        ) {
            qrBorderCanvas(
                borderColor = Color.White,
                curve = 0.dp,
                strokeWidth = SIZE_EXTRA_SMALL.dp,
                capSize = SIZE_LARGE.dp,
                gapAngle = SIZE_EXTRA_SMALL,
                cap = StrokeCap.Square
            )
        }
    }
}

@Composable
private fun InformativeText(text: String) {
    WrapCard {
        Row(
            modifier = Modifier.padding(all = SPACING_SMALL.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            WrapIcon(AppIcons.Error)
            Text(
                modifier = Modifier.padding(all = SPACING_SMALL.dp),
                text = text,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@ThemeModePreviews
@Composable
private fun InformativeTextPreview() {
    PreviewTheme {
        InformativeText(
            text = stringResource(Res.string.qr_scan_informative_text_presentation_flow)
        )
    }
}