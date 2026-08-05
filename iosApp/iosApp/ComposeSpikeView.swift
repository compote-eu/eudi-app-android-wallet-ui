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

import SwiftUI

// Scoped import, as in ContentView: a whole-module `import SharedKit` would pull every public Kotlin
// declaration into this file's namespace, where the view-models' top-level `State` collides with
// SwiftUI.State.
import class SharedKit.SplashSpikeKt

/// Hosts the shared Compose Multiplatform UI.
///
/// Note how small the Swift surface is: one call returning a `UIViewController`. Under this
/// architecture Swift never names a view-model, a state or an effect, which is what keeps the
/// Obj-C flat-namespace export problem (`State`, `State_`, `State__`, …) harmless.
struct ComposeSpikeView: UIViewControllerRepresentable {

    func makeUIViewController(context: Context) -> UIViewController {
        SplashSpikeKt.SplashSpikeViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // Compose owns its own recomposition; nothing to push from SwiftUI.
    }
}
