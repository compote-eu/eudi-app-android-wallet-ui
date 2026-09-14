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

package eu.europa.ec.proximityfeature.ui.qr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.uilogic.component.wrap.rememberQrPainter
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.rememberPlatformActivityOrNull
import eu.europa.ec.uilogic.component.content.ContentTitle
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.LifecycleEffect
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.utils.screenWidthInDp
import eu.europa.ec.uilogic.component.wrap.SwitchDataUi
import eu.europa.ec.uilogic.component.wrap.WrapImage
import eu.europa.ec.uilogic.component.wrap.WrapSwitch
import eu.europa.ec.uilogic.extension.paddingFrom
import eu.europa.ec.uilogic.navigation.helper.navigateReplacingCurrent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.content_description_qr_code_icon
import eu.europa.ec.shared.resources.proximity_qr_enable_nfc_data_retrieval
import eu.europa.ec.shared.resources.proximity_qr_hold_near_reader
import eu.europa.ec.shared.resources.proximity_qr_subtitle
import eu.europa.ec.shared.resources.proximity_qr_title
import eu.europa.ec.shared.resources.proximity_qr_use_nfc

@Composable
fun ProximityQRScreen(
    navigator: AppNavigator,
    viewModel: ProximityQRViewModel
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    // Null on iOS, where an app cannot be an NFC card emulator at all: the QR engagement below is the
    // whole story there, and the NFC half is simply not offered.
    val platformActivity = rememberPlatformActivityOrNull()

    val snackbarHostState = remember { SnackbarHostState() }

    ContentScreen(
        isLoading = state.isLoading,
        navigatableAction = ScreenNavigateAction.BACKABLE,
        onBack = { viewModel.setEvent(Event.GoBack) },
        contentErrorConfig = state.error,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                snackbar = { snackbarData -> Snackbar(snackbarData = snackbarData) }
            )
        },
    ) { paddingValues ->
        Content(
            state = state,
            effectFlow = viewModel.effect,
            onNavigationRequested = { navigationEffect ->
                when (navigationEffect) {
                    is Effect.Navigation.SwitchScreen -> {
                        navigator.navigateReplacingCurrent(navigationEffect.route)
                    }

                    is Effect.Navigation.Pop -> {
                        navigator.pop()
                    }
                }
            },
            onNfcDataRetrievalToggled = {
                viewModel.setEvent(Event.NfcDataRetrievalToggled(enabled = it))
            },
            snackbarHostState = snackbarHostState,
            paddingValues = paddingValues
        )
    }


    LifecycleEffect(
        lifecycleOwner = LocalLifecycleOwner.current,
        lifecycleEvent = Lifecycle.Event.ON_RESUME
    ) {
        platformActivity?.let {
            viewModel.setEvent(Event.NfcEngagement(componentActivity = it, enable = true))
        }
    }

    LifecycleEffect(
        lifecycleOwner = LocalLifecycleOwner.current,
        lifecycleEvent = Lifecycle.Event.ON_PAUSE
    ) {
        platformActivity?.let {
            viewModel.setEvent(Event.NfcEngagement(componentActivity = it, enable = false))
        }
    }
}

@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onNavigationRequested: (navigationEffect: Effect.Navigation) -> Unit,
    onNfcDataRetrievalToggled: (Boolean) -> Unit,
    snackbarHostState: SnackbarHostState,
    paddingValues: PaddingValues,
) {

    val qrSize = screenWidthInDp(true) / 1.4f

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .paddingFrom(paddingValues, bottom = false)
        ) {
            ContentTitle(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(Res.string.proximity_qr_title),
                subtitle = stringResource(Res.string.proximity_qr_subtitle)
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                QRCode(
                    qrCode = state.qrCode,
                    qrSize = qrSize
                )
            }
        }

        Column {
            HorizontalDivider()
            NFCSection(paddingValues)
            // Only where the platform actually has the transport — see
            // ProximityQRInteractor.isNfcDataRetrievalAvailable. Android's existing NFCSection above
            // is unaffected: it describes Android's own, separate, always-on NFC engagement, not this.
            if (state.nfcDataRetrievalAvailable) {
                NfcDataRetrievalSection(
                    checked = state.nfcDataRetrievalEnabled,
                    // Findings A/B/D/E, part 4: toggling restarts the current engagement (see
                    // ProximityQRViewModel.restartEngagementForNfcToggle) rather than only taking
                    // effect on the next screen visit — disabled mid-restart so a second tap can't
                    // race the first one's restart.
                    switchEnabled = !state.isLoading,
                    onToggle = onNfcDataRetrievalToggled,
                    paddingValues = paddingValues,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)

                is Effect.ShowSnackbar -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch {
                        snackbarHostState.showSnackbar(
                            message = effect.message,
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            }
        }.collect()
    }
}

@Composable
private fun NFCSection(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(
                start = SPACING_MEDIUM.dp,
                end = SPACING_MEDIUM.dp,
                top = SPACING_MEDIUM.dp,
                bottom = paddingValues.calculateBottomPadding()
            ),
        verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(Res.string.proximity_qr_use_nfc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        WrapImage(iconData = AppIcons.NFC)
        Text(
            text = stringResource(Res.string.proximity_qr_hold_near_reader),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * The one real, user-facing control this screen has: ISO 18013-5 Annex 8 NFC data retrieval,
 * offered as a sibling transport to BLE (see `wiki/IOS_NFC_PLAN.md`). Only rendered by the caller
 * when [State.nfcDataRetrievalAvailable] — today that means iOS only; Android keeps its own,
 * separate, always-on NFC engagement in [NFCSection] above, unaffected by this switch.
 */
@Composable
private fun NfcDataRetrievalSection(
    checked: Boolean,
    switchEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    paddingValues: PaddingValues,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SPACING_MEDIUM.dp,
                end = SPACING_MEDIUM.dp,
                top = SPACING_SMALL.dp,
                bottom = paddingValues.calculateBottomPadding()
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.proximity_qr_enable_nfc_data_retrieval),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        WrapSwitch(
            switchData = SwitchDataUi(isChecked = checked, enabled = switchEnabled),
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun QRCode(
    modifier: Modifier = Modifier,
    qrCode: String,
    qrSize: Dp
) {
    if (qrCode.isNotEmpty()) {
        WrapImage(
            modifier = modifier,
            painter = rememberQrPainter(
                content = qrCode,
                size = qrSize
            ),
            contentDescription = stringResource(Res.string.content_description_qr_code_icon)
        )
    }
}

@ThemeModePreviews
@Composable
private fun ContentPreview() {
    PreviewTheme {
        Content(
            state = State(
                isLoading = false,
                error = null,
                qrCode = "some qr code",
                nfcDataRetrievalAvailable = true,
            ),
            effectFlow = Channel<Effect>().receiveAsFlow(),
            onNavigationRequested = {},
            onNfcDataRetrievalToggled = {},
            snackbarHostState = SnackbarHostState(),
            paddingValues = PaddingValues(SPACING_MEDIUM.dp)
        )
    }
}