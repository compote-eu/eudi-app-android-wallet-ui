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

// Phase 3b: the shared half of the icon system — `AppIconKey` -> compose-resources `DrawableResource`.
//
// The corpus moved into this module's `composeResources/drawable/` (53 vector XMLs, which
// compose-resources consumes as-is), so shared screens can render icons on both platforms. This is the
// drawable counterpart of the Phase-3a string corpus.
//
// The Android `AppIconKey.resourceId` resolver in :ui-logic is deliberately still in place and
// unchanged: flipping its call sites over to these resources is a separate step, and one that wants a
// visual pass on Android first — the AVD's screencap returns black for Compose windows, so a
// vector-parse regression there would be invisible to automation. Until then the assets exist in both
// resource systems and each platform reads its own.
//
// The seven `ImageVector`-backed keys return null here exactly as they do there; the `when` is
// exhaustive, so adding a key without an asset fails compilation.
//
// TRANSFORMS the copy needed (everything else was byte-identical):
//   * `android:tint` on the <vector> root overrides every fill, so it was baked into the fills and the
//     attribute dropped — lossless. (ic_open_in_browser, ic_open_new: #000000; ic_sign_document: #2a5ed9)
//   * `@android:color/white` -> `#FFFFFFFF` (ic_clock_timer), a framework reference compose-resources
//     cannot resolve.
//
// `LogoIconAndText` resolves to the lockup's **mark** layer only. The wordmark is a second asset
// (`ic_logo_lockup_wordmark`) that `AppIconAndText` overlays and tints from
// `MaterialTheme.colorScheme.onSurface`, because upstream it was painted with `?colorOnSurface` — a theme
// attribute compose-resources cannot resolve. Both layers keep the original 161x52 viewport, so stacking
// them reproduces the artwork exactly with no coordinate maths, and the wordmark follows light/dark again.
package eu.europa.ec.uilogic.component

import eu.europa.ec.shared.resources.Res
import eu.europa.ec.shared.resources.ic_add
import eu.europa.ec.shared.resources.ic_add_document_from_list
import eu.europa.ec.shared.resources.ic_add_document_from_qr
import eu.europa.ec.shared.resources.ic_authenticate_id_cards
import eu.europa.ec.shared.resources.ic_batch_issuance_counter
import eu.europa.ec.shared.resources.ic_bookmark
import eu.europa.ec.shared.resources.ic_bookmark_filled
import eu.europa.ec.shared.resources.ic_certified
import eu.europa.ec.shared.resources.ic_change_pin
import eu.europa.ec.shared.resources.ic_check
import eu.europa.ec.shared.resources.ic_clock_timer
import eu.europa.ec.shared.resources.ic_contract
import eu.europa.ec.shared.resources.ic_delete
import eu.europa.ec.shared.resources.ic_documents
import eu.europa.ec.shared.resources.ic_download
import eu.europa.ec.shared.resources.ic_edit
import eu.europa.ec.shared.resources.ic_error
import eu.europa.ec.shared.resources.ic_filters
import eu.europa.ec.shared.resources.ic_handle_bar
import eu.europa.ec.shared.resources.ic_home
import eu.europa.ec.shared.resources.ic_id
import eu.europa.ec.shared.resources.ic_id_stroke
import eu.europa.ec.shared.resources.ic_in_progress
import eu.europa.ec.shared.resources.ic_info
import eu.europa.ec.shared.resources.ic_logo_icon
import eu.europa.ec.shared.resources.ic_logo_lockup_mark
import eu.europa.ec.shared.resources.ic_menu
import eu.europa.ec.shared.resources.ic_message
import eu.europa.ec.shared.resources.ic_more
import eu.europa.ec.shared.resources.ic_nfc
import eu.europa.ec.shared.resources.ic_notifications
import eu.europa.ec.shared.resources.ic_open_in_browser
import eu.europa.ec.shared.resources.ic_open_new
import eu.europa.ec.shared.resources.ic_present_document_cross_device
import eu.europa.ec.shared.resources.ic_present_document_same_device
import eu.europa.ec.shared.resources.ic_qr
import eu.europa.ec.shared.resources.ic_qr_scanner
import eu.europa.ec.shared.resources.ic_search
import eu.europa.ec.shared.resources.ic_settings
import eu.europa.ec.shared.resources.ic_sign_document
import eu.europa.ec.shared.resources.ic_sign_document_from_device
import eu.europa.ec.shared.resources.ic_sign_document_from_qr
import eu.europa.ec.shared.resources.ic_success
import eu.europa.ec.shared.resources.ic_touch_id
import eu.europa.ec.shared.resources.ic_transactions
import eu.europa.ec.shared.resources.ic_user
import eu.europa.ec.shared.resources.ic_verified
import eu.europa.ec.shared.resources.ic_visibility_off
import eu.europa.ec.shared.resources.ic_visibility_on
import eu.europa.ec.shared.resources.ic_wallet_activated
import eu.europa.ec.shared.resources.ic_wallet_secured
import eu.europa.ec.shared.resources.ic_warning
import org.jetbrains.compose.resources.DrawableResource

/**
 * The compose-resources drawable for this key, or null when the key is backed by an `ImageVector`
 * instead (see `AppIconKey.imageVector` in :ui-logic).
 */
val AppIconKey.drawableResource: DrawableResource?
    get() = when (this) {
        AppIconKey.VerticalMore -> Res.drawable.ic_more
        AppIconKey.Warning -> Res.drawable.ic_warning
        AppIconKey.Error -> Res.drawable.ic_error
        AppIconKey.Delete -> Res.drawable.ic_delete
        AppIconKey.TouchId -> Res.drawable.ic_touch_id
        AppIconKey.QR -> Res.drawable.ic_qr
        AppIconKey.NFC -> Res.drawable.ic_nfc
        AppIconKey.User -> Res.drawable.ic_user
        AppIconKey.Id -> Res.drawable.ic_id
        AppIconKey.IdStroke -> Res.drawable.ic_id_stroke
        AppIconKey.LogoIcon -> Res.drawable.ic_logo_icon
        AppIconKey.LogoIconAndText -> Res.drawable.ic_logo_lockup_mark
        AppIconKey.Visibility -> Res.drawable.ic_visibility_on
        AppIconKey.VisibilityOff -> Res.drawable.ic_visibility_off
        AppIconKey.Add -> Res.drawable.ic_add
        AppIconKey.Edit -> Res.drawable.ic_edit
        AppIconKey.Sign -> Res.drawable.ic_sign_document
        AppIconKey.QrScanner -> Res.drawable.ic_qr_scanner
        AppIconKey.Verified -> Res.drawable.ic_verified
        AppIconKey.Message -> Res.drawable.ic_message
        AppIconKey.ClockTimer -> Res.drawable.ic_clock_timer
        AppIconKey.OpenNew -> Res.drawable.ic_open_new
        AppIconKey.HandleBar -> Res.drawable.ic_handle_bar
        AppIconKey.Search -> Res.drawable.ic_search
        AppIconKey.PresentDocumentInPerson -> Res.drawable.ic_present_document_same_device
        AppIconKey.PresentDocumentOnline -> Res.drawable.ic_present_document_cross_device
        AppIconKey.AddDocumentFromList -> Res.drawable.ic_add_document_from_list
        AppIconKey.AddDocumentFromQr -> Res.drawable.ic_add_document_from_qr
        AppIconKey.Bookmark -> Res.drawable.ic_bookmark
        AppIconKey.BookmarkFilled -> Res.drawable.ic_bookmark_filled
        AppIconKey.Certified -> Res.drawable.ic_certified
        AppIconKey.Success -> Res.drawable.ic_success
        AppIconKey.Documents -> Res.drawable.ic_documents
        AppIconKey.Download -> Res.drawable.ic_download
        AppIconKey.Filters -> Res.drawable.ic_filters
        AppIconKey.Home -> Res.drawable.ic_home
        AppIconKey.Menu -> Res.drawable.ic_menu
        AppIconKey.Contract -> Res.drawable.ic_contract
        AppIconKey.InProgress -> Res.drawable.ic_in_progress
        AppIconKey.Notifications -> Res.drawable.ic_notifications
        AppIconKey.Transactions -> Res.drawable.ic_transactions
        AppIconKey.WalletActivated -> Res.drawable.ic_wallet_activated
        AppIconKey.WalletSecured -> Res.drawable.ic_wallet_secured
        AppIconKey.Info -> Res.drawable.ic_info
        AppIconKey.IdCards -> Res.drawable.ic_authenticate_id_cards
        AppIconKey.SignDocumentFromDevice -> Res.drawable.ic_sign_document_from_device
        AppIconKey.SignDocumentFromQr -> Res.drawable.ic_sign_document_from_qr
        AppIconKey.ChangePin -> Res.drawable.ic_change_pin
        AppIconKey.Check -> Res.drawable.ic_check
        AppIconKey.OpenInBrowser -> Res.drawable.ic_open_in_browser
        AppIconKey.Settings -> Res.drawable.ic_settings
        AppIconKey.BatchIssuanceCounter -> Res.drawable.ic_batch_issuance_counter
        AppIconKey.ArrowBack -> null
        AppIconKey.Close -> null
        AppIconKey.ErrorFilled -> null
        AppIconKey.KeyboardArrowDown -> null
        AppIconKey.KeyboardArrowUp -> null
        AppIconKey.KeyboardArrowRight -> null
        AppIconKey.DateRange -> null
    }

/** Convenience for the wrapper models, mirroring `IconDataUi.resourceId` on the Android side. */
val IconDataUi.drawableResource: DrawableResource?
    get() = iconKey.drawableResource
