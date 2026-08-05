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

// The Kotlin Multiplatform module, exposed as an Objective-C/Swift framework.
//
// Imported symbol-by-symbol rather than as a whole module on purpose. Obj-C has a flat namespace, so
// every public Kotlin declaration lands in one, and each shared view-model contributes top-level
// `State`, `Event` and `Effect` classes. A plain `import SharedKit` therefore makes `State` ambiguous
// against `SwiftUI.State` and breaks `@State`. A scoped import keeps those names out of this file
// entirely; see wiki/KMP_FEASIBILITY.md on why this stays a non-issue while iOS hosts Compose UI.
import class SharedKit.Platform
import class SharedKit.PinValidator

struct ContentView: View {

    // Shared Kotlin `object Platform` → Swift `Platform.shared`.
    private let platform = Platform.shared.name

    @State private var pin = ""

    var body: some View {
        VStack(spacing: 16) {
            Text("EUDI KMP spike")
                .font(.headline)
            Text("Running on \(platform)")
                .foregroundStyle(.secondary)

            TextField("Enter 6-digit PIN", text: $pin)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numberPad)

            // Shared business logic (Kotlin `object PinValidator`) called from Swift.
            Text(PinValidator.shared.isValid(pin: pin, length: 6)
                 ? "✅ valid PIN"
                 : "❌ invalid PIN")
                .font(.callout.bold())
        }
        .padding()
    }
}
