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

package eu.europa.ec.uilogic.component

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

/**
 * Data class wrapping an [AppIconKey].
 *
 * Holding *only* an [AppIconKey] means the type system, not a runtime check, enforces
 * that every icon passed through the UI layer is registered in the central enum.
 * Callers cannot construct an `IconDataUi` from a raw `R.drawable.example` or a Material
 * `Icons.Filled.X`; they must add an entry to [AppIconKey] first.
 *
 * Serialization writes just the key (e.g. `{"iconKey":"WalletSecured"}`); the
 * destination reconstructs the full data via the enum entry.
 *
 * KMP-clean: the actual resource / [androidx.compose.ui.graphics.vector.ImageVector] mapping lives
 * in the androidMain resolvers in :ui-logic (`AppIconKey.resourceId`, `AppIconKey.imageVector`,
 * `AppIconKey.contentDescriptionId` and the matching [IconDataUi] extensions) — the same pattern as
 * [eu.europa.ec.uilogic.component.wrap.ColorKey].
 */
@Stable
@Serializable
data class IconDataUi(val iconKey: AppIconKey)

/**
 * Stable identity for every icon in the app.
 *
 * The enum is the single source of truth for icon assets: callers reference icons by their key,
 * never by raw resource id or `ImageVector`. Each key resolves to either a drawable `resourceId`,
 * an `imageVector`, or both — and a `contentDescriptionId` — through the androidMain resolvers in
 * :ui-logic. That resolver enforces the invariant that every key has a non-null `resourceId` or
 * `imageVector`; adding a key without either fails its exhaustive `when` at compile time.
 *
 * Serialization writes the enum's *name* (e.g. `"WalletSecured"`) on the wire and resolves back to
 * the same entry on the other side. `ImageVector` and `resourceId` therefore survive a navigation
 * round-trip without ever being serialized themselves.
 *
 * **Adding a new icon:** add one entry here, one branch in each resolver in :ui-logic, and a
 * one-line alias in [AppIcons].
 */
@Serializable
enum class AppIconKey {
    ArrowBack,
    Close,
    VerticalMore,
    Warning,
    Error,
    ErrorFilled,
    Delete,
    TouchId,
    QR,
    NFC,
    User,
    Id,
    IdStroke,
    LogoIcon,
    LogoIconAndText,
    KeyboardArrowDown,
    KeyboardArrowUp,
    Visibility,
    VisibilityOff,
    Add,
    Edit,
    Sign,
    QrScanner,
    Verified,
    Message,
    ClockTimer,
    OpenNew,
    KeyboardArrowRight,
    HandleBar,
    Search,
    PresentDocumentInPerson,
    PresentDocumentOnline,
    AddDocumentFromList,
    AddDocumentFromQr,
    Bookmark,
    BookmarkFilled,
    Certified,
    Success,
    Documents,
    Download,
    Filters,
    Home,
    Menu,
    Contract,
    InProgress,
    Notifications,
    Transactions,
    WalletActivated,
    WalletSecured,
    Info,
    IdCards,
    SignDocumentFromDevice,
    SignDocumentFromQr,
    ChangePin,
    Check,
    OpenInBrowser,
    DateRange,
    Settings,
    BatchIssuanceCounter,
}

/**
 * Convenience constants — `AppIcons.Example` is exactly `IconDataUi(AppIconKey.Example)`.
 */
object AppIcons {
    val ArrowBack: IconDataUi = IconDataUi(AppIconKey.ArrowBack)
    val Close: IconDataUi = IconDataUi(AppIconKey.Close)
    val VerticalMore: IconDataUi = IconDataUi(AppIconKey.VerticalMore)
    val Warning: IconDataUi = IconDataUi(AppIconKey.Warning)
    val Error: IconDataUi = IconDataUi(AppIconKey.Error)
    val ErrorFilled: IconDataUi = IconDataUi(AppIconKey.ErrorFilled)
    val Delete: IconDataUi = IconDataUi(AppIconKey.Delete)
    val TouchId: IconDataUi = IconDataUi(AppIconKey.TouchId)
    val QR: IconDataUi = IconDataUi(AppIconKey.QR)
    val NFC: IconDataUi = IconDataUi(AppIconKey.NFC)
    val User: IconDataUi = IconDataUi(AppIconKey.User)
    val Id: IconDataUi = IconDataUi(AppIconKey.Id)
    val IdStroke: IconDataUi = IconDataUi(AppIconKey.IdStroke)
    val LogoIcon: IconDataUi = IconDataUi(AppIconKey.LogoIcon)
    val LogoIconAndText: IconDataUi = IconDataUi(AppIconKey.LogoIconAndText)
    val KeyboardArrowDown: IconDataUi = IconDataUi(AppIconKey.KeyboardArrowDown)
    val KeyboardArrowUp: IconDataUi = IconDataUi(AppIconKey.KeyboardArrowUp)
    val Visibility: IconDataUi = IconDataUi(AppIconKey.Visibility)
    val VisibilityOff: IconDataUi = IconDataUi(AppIconKey.VisibilityOff)
    val Add: IconDataUi = IconDataUi(AppIconKey.Add)
    val Edit: IconDataUi = IconDataUi(AppIconKey.Edit)
    val Sign: IconDataUi = IconDataUi(AppIconKey.Sign)
    val QrScanner: IconDataUi = IconDataUi(AppIconKey.QrScanner)
    val Verified: IconDataUi = IconDataUi(AppIconKey.Verified)
    val Message: IconDataUi = IconDataUi(AppIconKey.Message)
    val ClockTimer: IconDataUi = IconDataUi(AppIconKey.ClockTimer)
    val OpenNew: IconDataUi = IconDataUi(AppIconKey.OpenNew)
    val KeyboardArrowRight: IconDataUi = IconDataUi(AppIconKey.KeyboardArrowRight)
    val HandleBar: IconDataUi = IconDataUi(AppIconKey.HandleBar)
    val Search: IconDataUi = IconDataUi(AppIconKey.Search)
    val PresentDocumentInPerson: IconDataUi = IconDataUi(AppIconKey.PresentDocumentInPerson)
    val PresentDocumentOnline: IconDataUi = IconDataUi(AppIconKey.PresentDocumentOnline)
    val AddDocumentFromList: IconDataUi = IconDataUi(AppIconKey.AddDocumentFromList)
    val AddDocumentFromQr: IconDataUi = IconDataUi(AppIconKey.AddDocumentFromQr)
    val Bookmark: IconDataUi = IconDataUi(AppIconKey.Bookmark)
    val BookmarkFilled: IconDataUi = IconDataUi(AppIconKey.BookmarkFilled)
    val Certified: IconDataUi = IconDataUi(AppIconKey.Certified)
    val Success: IconDataUi = IconDataUi(AppIconKey.Success)
    val Documents: IconDataUi = IconDataUi(AppIconKey.Documents)
    val Download: IconDataUi = IconDataUi(AppIconKey.Download)
    val Filters: IconDataUi = IconDataUi(AppIconKey.Filters)
    val Home: IconDataUi = IconDataUi(AppIconKey.Home)
    val Menu: IconDataUi = IconDataUi(AppIconKey.Menu)
    val Contract: IconDataUi = IconDataUi(AppIconKey.Contract)
    val InProgress: IconDataUi = IconDataUi(AppIconKey.InProgress)
    val Notifications: IconDataUi = IconDataUi(AppIconKey.Notifications)
    val Transactions: IconDataUi = IconDataUi(AppIconKey.Transactions)
    val WalletActivated: IconDataUi = IconDataUi(AppIconKey.WalletActivated)
    val WalletSecured: IconDataUi = IconDataUi(AppIconKey.WalletSecured)
    val Info: IconDataUi = IconDataUi(AppIconKey.Info)
    val IdCards: IconDataUi = IconDataUi(AppIconKey.IdCards)
    val SignDocumentFromDevice: IconDataUi = IconDataUi(AppIconKey.SignDocumentFromDevice)
    val SignDocumentFromQr: IconDataUi = IconDataUi(AppIconKey.SignDocumentFromQr)
    val ChangePin: IconDataUi = IconDataUi(AppIconKey.ChangePin)
    val Check: IconDataUi = IconDataUi(AppIconKey.Check)
    val OpenInBrowser: IconDataUi = IconDataUi(AppIconKey.OpenInBrowser)
    val DateRange: IconDataUi = IconDataUi(AppIconKey.DateRange)
    val Settings: IconDataUi = IconDataUi(AppIconKey.Settings)
    val BatchIssuanceCounter: IconDataUi = IconDataUi(AppIconKey.BatchIssuanceCounter)
}
