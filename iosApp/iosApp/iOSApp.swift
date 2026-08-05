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

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            // The Compose Multiplatform spike is the root while we establish that shared view-models
            // can drive real iOS UI. ContentView (the SwiftUI-calling-Kotlin spike from Phase 0) is
            // still in the target and reachable from the tab below, so both paths stay exercised.
            TabView {
                ComposeSpikeView()
                    .ignoresSafeArea()
                    .tabItem { Label("Compose MP", systemImage: "square.stack.3d.up") }

                ContentView()
                    .tabItem { Label("SwiftUI", systemImage: "swift") }
            }
        }
    }
}
