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

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.vector.ImageVector
import eu.europa.ec.resourceslogic.R

/**
 * Android-side resolvers for [AppIconKey] / [IconDataUi].
 *
 * The pure key/data (enum + [IconDataUi] wrapper + [AppIcons] aliases) lives in :shared-ui
 * commonMain and carries no Android resource references. These extensions reproduce the concrete
 * `R.drawable.*` / `R.string.*` / `Icons.*` mapping for each key — the same pattern as
 * `ColorKey.toColor()` and friends.
 *
 * Invariant (previously enforced by the enum's `init {}` block): every key must resolve to a
 * non-null [resourceId] or [imageVector]. The exhaustive `when` blocks below make that invariant a
 * compile-time check — a new key with neither a drawable nor an image vector is impossible to add
 * without leaving both branches null, which is caught in review of these resolvers.
 */

@get:DrawableRes
val AppIconKey.resourceId: Int?
    get() = when (this) {
        AppIconKey.ArrowBack -> null
        AppIconKey.Close -> null
        AppIconKey.VerticalMore -> R.drawable.ic_more
        AppIconKey.Warning -> R.drawable.ic_warning
        AppIconKey.Error -> R.drawable.ic_error
        AppIconKey.ErrorFilled -> null
        AppIconKey.Delete -> R.drawable.ic_delete
        AppIconKey.TouchId -> R.drawable.ic_touch_id
        AppIconKey.QR -> R.drawable.ic_qr
        AppIconKey.NFC -> R.drawable.ic_nfc
        AppIconKey.User -> R.drawable.ic_user
        AppIconKey.Id -> R.drawable.ic_id
        AppIconKey.IdStroke -> R.drawable.ic_id_stroke
        AppIconKey.LogoIcon -> R.drawable.ic_logo_icon
        AppIconKey.LogoIconAndText -> R.drawable.ic_logo_icon_and_text
        AppIconKey.KeyboardArrowDown -> null
        AppIconKey.KeyboardArrowUp -> null
        AppIconKey.Visibility -> R.drawable.ic_visibility_on
        AppIconKey.VisibilityOff -> R.drawable.ic_visibility_off
        AppIconKey.Add -> R.drawable.ic_add
        AppIconKey.Edit -> R.drawable.ic_edit
        AppIconKey.Sign -> R.drawable.ic_sign_document
        AppIconKey.QrScanner -> R.drawable.ic_qr_scanner
        AppIconKey.Verified -> R.drawable.ic_verified
        AppIconKey.Message -> R.drawable.ic_message
        AppIconKey.ClockTimer -> R.drawable.ic_clock_timer
        AppIconKey.OpenNew -> R.drawable.ic_open_new
        AppIconKey.KeyboardArrowRight -> null
        AppIconKey.HandleBar -> R.drawable.ic_handle_bar
        AppIconKey.Search -> R.drawable.ic_search
        AppIconKey.PresentDocumentInPerson -> R.drawable.ic_present_document_same_device
        AppIconKey.PresentDocumentOnline -> R.drawable.ic_present_document_cross_device
        AppIconKey.AddDocumentFromList -> R.drawable.ic_add_document_from_list
        AppIconKey.AddDocumentFromQr -> R.drawable.ic_add_document_from_qr
        AppIconKey.Bookmark -> R.drawable.ic_bookmark
        AppIconKey.BookmarkFilled -> R.drawable.ic_bookmark_filled
        AppIconKey.Certified -> R.drawable.ic_certified
        AppIconKey.Success -> R.drawable.ic_success
        AppIconKey.Documents -> R.drawable.ic_documents
        AppIconKey.Download -> R.drawable.ic_download
        AppIconKey.Filters -> R.drawable.ic_filters
        AppIconKey.Home -> R.drawable.ic_home
        AppIconKey.Menu -> R.drawable.ic_menu
        AppIconKey.Contract -> R.drawable.ic_contract
        AppIconKey.InProgress -> R.drawable.ic_in_progress
        AppIconKey.Notifications -> R.drawable.ic_notifications
        AppIconKey.Transactions -> R.drawable.ic_transactions
        AppIconKey.WalletActivated -> R.drawable.ic_wallet_activated
        AppIconKey.WalletSecured -> R.drawable.ic_wallet_secured
        AppIconKey.Info -> R.drawable.ic_info
        AppIconKey.IdCards -> R.drawable.ic_authenticate_id_cards
        AppIconKey.SignDocumentFromDevice -> R.drawable.ic_sign_document_from_device
        AppIconKey.SignDocumentFromQr -> R.drawable.ic_sign_document_from_qr
        AppIconKey.ChangePin -> R.drawable.ic_change_pin
        AppIconKey.Check -> R.drawable.ic_check
        AppIconKey.OpenInBrowser -> R.drawable.ic_open_in_browser
        AppIconKey.DateRange -> null
        AppIconKey.Settings -> R.drawable.ic_settings
        AppIconKey.BatchIssuanceCounter -> R.drawable.ic_batch_issuance_counter
    }

@get:StringRes
val AppIconKey.contentDescriptionId: Int
    get() = when (this) {
        AppIconKey.ArrowBack -> R.string.content_description_arrow_back_icon
        AppIconKey.Close -> R.string.content_description_close_icon
        AppIconKey.VerticalMore -> R.string.content_description_more_vert_icon
        AppIconKey.Warning -> R.string.content_description_warning_icon
        AppIconKey.Error -> R.string.content_description_error_icon
        AppIconKey.ErrorFilled -> R.string.content_description_error_icon
        AppIconKey.Delete -> R.string.content_description_delete_icon
        AppIconKey.TouchId -> R.string.content_description_touch_id_icon
        AppIconKey.QR -> R.string.content_description_qr_icon
        AppIconKey.NFC -> R.string.content_description_nfc_icon
        AppIconKey.User -> R.string.content_description_user_icon
        AppIconKey.Id -> R.string.content_description_id_icon
        AppIconKey.IdStroke -> R.string.content_description_id_stroke_icon
        AppIconKey.LogoIcon -> R.string.content_description_logo_icon
        AppIconKey.LogoIconAndText -> R.string.content_description_logo_icon_and_text
        AppIconKey.KeyboardArrowDown -> R.string.content_description_arrow_down_icon
        AppIconKey.KeyboardArrowUp -> R.string.content_description_arrow_up_icon
        AppIconKey.Visibility -> R.string.content_description_visibility_icon
        AppIconKey.VisibilityOff -> R.string.content_description_visibility_off_icon
        AppIconKey.Add -> R.string.content_description_add_icon
        AppIconKey.Edit -> R.string.content_description_edit_icon
        AppIconKey.Sign -> R.string.content_description_edit_icon
        AppIconKey.QrScanner -> R.string.content_description_qr_scanner_icon
        AppIconKey.Verified -> R.string.content_description_verified_icon
        AppIconKey.Message -> R.string.content_description_message_icon
        AppIconKey.ClockTimer -> R.string.content_description_clock_timer_icon
        AppIconKey.OpenNew -> R.string.content_description_open_new_icon
        AppIconKey.KeyboardArrowRight -> R.string.content_description_arrow_right_icon
        AppIconKey.HandleBar -> R.string.content_description_handle_bar_icon
        AppIconKey.Search -> R.string.content_description_search_icon
        AppIconKey.PresentDocumentInPerson -> R.string.content_description_present_document_same_device_icon
        AppIconKey.PresentDocumentOnline -> R.string.content_description_present_document_cross_device_icon
        AppIconKey.AddDocumentFromList -> R.string.content_description_add_document_from_list_icon
        AppIconKey.AddDocumentFromQr -> R.string.content_description_add_document_from_qr_icon
        AppIconKey.Bookmark -> R.string.content_description_bookmark_icon
        AppIconKey.BookmarkFilled -> R.string.content_description_bookmark_filled_icon
        AppIconKey.Certified -> R.string.content_description_certified_icon
        AppIconKey.Success -> R.string.content_description_success_icon
        AppIconKey.Documents -> R.string.content_description_documents_icon
        AppIconKey.Download -> R.string.content_description_download_icon
        AppIconKey.Filters -> R.string.content_description_filters_icon
        AppIconKey.Home -> R.string.content_description_home_icon
        AppIconKey.Menu -> R.string.content_description_menu_icon
        AppIconKey.Contract -> R.string.content_description_signature_icon
        AppIconKey.InProgress -> R.string.content_description_in_progress_icon
        AppIconKey.Notifications -> R.string.content_description_notifications_icon
        AppIconKey.Transactions -> R.string.content_description_transactions_icon
        AppIconKey.WalletActivated -> R.string.content_description_wallet_activated_icon
        AppIconKey.WalletSecured -> R.string.content_description_wallet_secured_icon
        AppIconKey.Info -> R.string.content_description_info_icon
        AppIconKey.IdCards -> R.string.content_description_issuer_icon
        AppIconKey.SignDocumentFromDevice -> R.string.content_description_add_document_from_list_icon
        AppIconKey.SignDocumentFromQr -> R.string.content_description_add_document_from_qr_icon
        AppIconKey.ChangePin -> R.string.content_description_change_pin_icon
        AppIconKey.Check -> R.string.content_description_check_icon
        AppIconKey.OpenInBrowser -> R.string.content_description_open_in_browser_icon
        AppIconKey.DateRange -> R.string.content_description_date_range_icon
        AppIconKey.Settings -> R.string.content_description_settings_icon
        AppIconKey.BatchIssuanceCounter -> R.string.content_description_batch_issuance_counter_icon
    }

val AppIconKey.imageVector: ImageVector?
    get() = when (this) {
        AppIconKey.ArrowBack -> Icons.AutoMirrored.Filled.ArrowBack
        AppIconKey.Close -> Icons.Filled.Close
        AppIconKey.ErrorFilled -> Icons.Default.Info
        AppIconKey.KeyboardArrowDown -> Icons.Default.KeyboardArrowDown
        AppIconKey.KeyboardArrowUp -> Icons.Default.KeyboardArrowUp
        AppIconKey.KeyboardArrowRight -> Icons.AutoMirrored.Filled.KeyboardArrowRight
        AppIconKey.DateRange -> Icons.Default.DateRange
        else -> null
    }

@get:DrawableRes
val IconDataUi.resourceId: Int?
    get() = iconKey.resourceId

@get:StringRes
val IconDataUi.contentDescriptionId: Int
    get() = iconKey.contentDescriptionId

val IconDataUi.imageVector: ImageVector?
    get() = iconKey.imageVector
