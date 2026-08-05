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

// Phase 3b: the FIRST shared screen — the same composable now renders on Android and iOS.
//
// Nothing about the screen's logic changed; what moved is where it lives. Its whole surface was
// already multiplatform-capable — Compose animation, foundation, material3, `collectAsStateWithLifecycle`
// (multiplatform since lifecycle 2.11) — and its view-model has been in commonMain since `be2b5a61`.
// Two things had to come with it: the `navigateReplacingCurrent` translator helper (pure `AppNavigator`
// logic, so it simply moved), and the logo asset.
//
// The package is unchanged, so `featureStartupEntries` in :startup-feature — which contributes this
// destination to the Android host — needed no edit at all. The iOS host contributes the same
// destination through the identical `entry<SplashRoute>` shape.
package eu.europa.ec.startupfeature.ui.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.europa.ec.shared.navigation.AppNavigator
import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.content_description_logo_icon
import eu.europa.ec.shared.resources.ic_logo_icon
import eu.europa.ec.uilogic.navigation.helper.navigateReplacingCurrent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SplashScreen(
    navigator: AppNavigator,
    viewModel: SplashViewModel
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    Content(
        state = state,
        effectFlow = viewModel.effect,
        onNavigationRequested = {
            when (it) {
                is Effect.Navigation.SwitchScreen -> {
                    navigator.navigateReplacingCurrent(it.route)
                }
            }
        }
    )

}

@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onNavigationRequested: (navigationEffect: Effect.Navigation) -> Unit
) {
    val visibilityState = remember {
        MutableTransitionState(false).apply {
            targetState = true
        }
    }
    Scaffold { paddingValues ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visibleState = visibilityState,
                enter = fadeIn(animationSpec = tween(state.logoAnimationDuration)),
                exit = fadeOut(animationSpec = tween(state.logoAnimationDuration)),
            ) {
                // Straight from compose-resources rather than through `AppIcons`/`WrapImage`: the
                // icon system still resolves keys to Android `R.drawable` ids in :ui-logic, so it
                // cannot serve a shared screen yet. Migrating that corpus (53 vector XMLs, which
                // compose-resources can consume as-is) is its own piece of work; this screen was the
                // only consumer of `AppIcons.LogoIcon`.
                Image(
                    painter = painterResource(Res.drawable.ic_logo_icon),
                    contentDescription = stringResource(Res.string.content_description_logo_icon),
                )
            }
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