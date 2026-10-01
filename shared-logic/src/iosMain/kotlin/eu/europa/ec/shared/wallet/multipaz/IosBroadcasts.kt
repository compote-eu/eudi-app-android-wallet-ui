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

package eu.europa.ec.shared.wallet.multipaz

import eu.europa.ec.corelogic.model.RevokedDocumentDataDomain
import eu.europa.ec.corelogic.util.CoreActions
import eu.europa.ec.shared.platform.IosBroadcasts
import eu.europa.ec.shared.platform.PlatformIntent
import org.multipaz.util.Logger

/**
 * What Android's `RevocationWorkManager` broadcasts after a sweep, in the order it sends them: when
 * documents became revoked, the dashboard's message naming them and the details refresh; when anything
 * was flagged *or* cleared, the list refresh.
 *
 * 🪤 The details refresh reaches no one on either platform: `DocumentDetailsScreen` filters on its
 * action but its `when` branch compares against `REVOCATION_IDS_DETAILS_EXTRA`, an extra's name. It is
 * sent anyway, so the screen starts working on iOS the moment that branch is corrected.
 */
internal fun revocationBroadcasts(refresh: RevocationRefresh): List<PlatformIntent> = buildList {
    val revoked = refresh.newlyRevoked
    if (revoked.isNotEmpty()) {
        add(
            PlatformIntent(
                action = CoreActions.REVOCATION_WORK_MESSAGE_ACTION,
                stringListExtras = mapOf(
                    CoreActions.REVOCATION_IDS_EXTRA to revoked.map { it.id },
                    REVOKED_NAMES_EXTRA to revoked.map { it.name },
                ),
            )
        )
        add(
            PlatformIntent(
                action = CoreActions.REVOCATION_WORK_REFRESH_DETAILS_ACTION,
                stringListExtras = mapOf(CoreActions.REVOCATION_IDS_DETAILS_EXTRA to revoked.map { it.id }),
            )
        )
    }
    if (revoked.isNotEmpty() || refresh.cleared.isNotEmpty()) {
        add(PlatformIntent(action = CoreActions.REVOCATION_WORK_REFRESH_ACTION))
    }
}

/**
 * What Android's `ReIssuanceWorkManager` broadcasts after a sweep that re-issued something: the list
 * refresh, and **not** its details broadcast.
 *
 * Android re-issues by replacing the document, so that broadcast names ids that no longer exist, and a
 * details screen showing one of them closes (`DocumentDetailsViewModel.checkIfRemoved`). iOS tops the
 * credentials up in place and the id stays the same, so the screen is still showing a real document.
 * Sending the broadcast would close it for nothing.
 */
internal fun reIssuanceBroadcasts(summary: BackgroundReIssuanceSummary): List<PlatformIntent> =
    if (summary.didWork) listOf(PlatformIntent(action = CoreActions.RE_ISSUANCE_WORK_REFRESH_ACTION)) else emptyList()

/**
 * The documents a revocation message names, or null when [intent] carries none. This is iOS's
 * counterpart of the parcel reader in `AndroidNavPlatformActions`.
 *
 * Null rather than a guess when the two lists disagree in length. Only [revocationBroadcasts] writes
 * them, so a mismatch means the intent is not one of its messages.
 */
fun revokedDocumentsInBroadcast(intent: PlatformIntent): List<RevokedDocumentDataDomain>? {
    val ids = intent.stringListExtras[CoreActions.REVOCATION_IDS_EXTRA] ?: return null
    val names = intent.stringListExtras[REVOKED_NAMES_EXTRA] ?: return null
    if (ids.size != names.size) return null
    return ids.zip(names) { id, name -> RevokedDocumentDataDomain(name = name, id = id) }
}

/**
 * Sends [intents] on the in-process bus, which delivers them only to the screens composed at that moment
 * (see [IosBroadcasts]), the same as a broadcast.
 */
internal fun announce(intents: List<PlatformIntent>, tag: String) {
    if (intents.isEmpty()) return
    intents.forEach { IosBroadcasts.send(it) }
    Logger.i(tag, "broadcast ${intents.map { it.action }}")
}

/**
 * Android ships each revoked document's name and id together, as one list of parcels. An iOS intent
 * carries only strings, so the names go in a list of their own, in the same order as the ids.
 */
private const val REVOKED_NAMES_EXTRA = "revocation.names.extra"
