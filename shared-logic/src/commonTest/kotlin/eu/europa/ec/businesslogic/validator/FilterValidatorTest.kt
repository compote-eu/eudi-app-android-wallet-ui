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

package eu.europa.ec.businesslogic.validator

import eu.europa.ec.businesslogic.validator.model.FilterElement.FilterItem
import eu.europa.ec.businesslogic.validator.model.FilterGroup
import eu.europa.ec.businesslogic.validator.model.FilterMultipleAction
import eu.europa.ec.businesslogic.validator.model.FilterableAttributes
import eu.europa.ec.businesslogic.validator.model.FilterableItem
import eu.europa.ec.businesslogic.validator.model.FilterableItemPayload
import eu.europa.ec.businesslogic.validator.model.FilterableList
import eu.europa.ec.businesslogic.validator.model.Filters
import eu.europa.ec.businesslogic.validator.model.SortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs on both Android (JVM) and iOS (Kotlin/Native) from the same source.
 *
 * These pin the behaviour that makes a **list-derived filter group** dangerous, because that is what
 * cost a real transaction its place in History: the dashboard's relying-party and issuer groups are
 * built *from the list being filtered*, so a group built against one list cannot judge a later one.
 *
 * ⚠️ The first two tests assert what the validator does **today**, on purpose. They are not a wish:
 * they are the reason `TransactionsViewModel` and `DocumentsViewModel` must rebuild their derived
 * groups on every load rather than only after a pause. Change them only with a matching change in
 * `applyMultipleSelectionFilter`, and expect the two view models to need re-checking.
 */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher, advanceUntilIdle
class FilterValidatorTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private data class Attributes(
        override val searchTags: List<String> = emptyList(),
        val relyingParty: String?,
    ) : FilterableAttributes

    private data class Payload(val id: String) : FilterableItemPayload

    /**
     * ⚠️ `searchTags` must not be empty. `filterByQuery` runs on every apply and keeps an item only if
     * one of its tags `contains` the query — and `any {}` over an empty list is false, so a tagless
     * item is dropped even when the query is "". An earlier version of these tests left the tags empty
     * and every assertion passed for that reason instead of the one being tested. Production items
     * always carry tags (a transaction's name).
     */
    private fun transaction(id: String, relyingParty: String?) = FilterableItem(
        payload = Payload(id),
        attributes = Attributes(searchTags = listOf(id), relyingParty = relyingParty),
    )

    /**
     * `TransactionsInteractorImpl.addRelyingPartyFilter` in miniature: one item per distinct relying
     * party, plus the unconditional "transactions without a relying party" item that means the group
     * is never actually empty — which is why the failure looks like a filter that works rather than
     * one that is missing.
     */
    private fun derivedFilters(list: FilterableList) = Filters(
        sortOrder = SortOrder.Descending(isDefault = true),
        filterGroups = listOf(
            FilterGroup.MultipleSelectionFilterGroup(
                id = RELYING_PARTY_GROUP,
                name = RELYING_PARTY_GROUP,
                filters = listOf(
                    FilterItem(
                        id = WITHOUT_RELYING_PARTY,
                        name = WITHOUT_RELYING_PARTY,
                        selected = true,
                        isDefault = true,
                    )
                ) + list.items
                    .mapNotNull { (it.attributes as Attributes).relyingParty }
                    .distinct()
                    .sorted()
                    .map { FilterItem(id = it, name = it, selected = true, isDefault = true) },
                filterableAction = FilterMultipleAction<Attributes> { attributes, filter ->
                    if (filter.id == WITHOUT_RELYING_PARTY) {
                        attributes.relyingParty == null
                    } else {
                        attributes.relyingParty == filter.name
                    }
                },
            )
        ),
    )

    /**
     * A validator plus every state it emits.
     *
     * 🪤 The validator gets **its own** `CoroutineScope`, not `backgroundScope`. Handing it
     * `backgroundScope` makes `applyFilters` emit into nothing — verified by A/B with everything else
     * identical: own scope records the state, `backgroundScope` records none — so the assertions all
     * pass on an empty list and prove nothing. The collector may live in `backgroundScope`; only the
     * validator's own scope matters. The scope outlives the test, which is why nothing here relies on
     * it being cancelled.
     */
    private fun TestScope.validatorWithStates():
        Pair<FilterValidator, List<FilterValidatorPartialState>> {
        val validator = FilterValidatorImpl(
            scope = CoroutineScope(dispatcher),
            sharingStarted = SharingStarted.Eagerly,
        )
        val states = mutableListOf<FilterValidatorPartialState>()
        backgroundScope.launch { validator.onFilterStateChange().toList(states) }
        advanceUntilIdle()
        return validator to states
    }

    /** The ids the last emitted list result contains, or empty when it filtered everything out. */
    private fun List<FilterValidatorPartialState>.filteredIds(): List<String> {
        val state = lastOrNull { it is FilterValidatorPartialState.FilterListResult }
        assertTrue(state != null, "the validator emitted no list result at all")
        return when (state) {
            is FilterValidatorPartialState.FilterListResult.FilterApplyResult ->
                state.filteredList.items.map { (it.payload as Payload).id }

            else -> emptyList()
        }
    }

    @Test
    fun a_group_built_from_one_list_hides_the_items_of_a_later_one() = runTest(dispatcher) {
        val (validator, states) = validatorWithStates()

        // The screen was opened while the transaction log was empty, so the only relying-party filter
        // is "without a relying party".
        val empty = FilterableList(emptyList())
        validator.initializeValidator(derivedFilters(empty), empty)

        // A presentation happens. `updateLists` swaps the list and nothing else — the group still has
        // no item for this relying party, and its predicate answers false for every one it has.
        validator.updateLists(FilterableList(listOf(transaction("t1", "Verifier"))))
        validator.applyFilters()
        advanceUntilIdle()

        assertEquals(emptyList(), states.filteredIds())
    }

    @Test
    fun resetting_the_filters_does_not_recover_them() = runTest(dispatcher) {
        val (validator, states) = validatorWithStates()
        val empty = FilterableList(emptyList())
        validator.initializeValidator(derivedFilters(empty), empty)
        validator.updateLists(FilterableList(listOf(transaction("t1", "Verifier"))))

        // "Reset all" restores `initialFilters`, which is the group as it was *built* — so the reset
        // that looks like the obvious escape hatch reinstates exactly the stale group.
        validator.resetFilters()
        advanceUntilIdle()

        assertEquals(emptyList(), states.filteredIds())
    }

    @Test
    fun re_initializing_shows_them_and_keeps_what_the_user_had_deselected() = runTest(dispatcher) {
        val (validator, states) = validatorWithStates()
        val empty = FilterableList(emptyList())
        validator.initializeValidator(derivedFilters(empty), empty)

        // The user turns "without a relying party" off and applies it — a choice that must survive a
        // rebuild, or rebuilding on every load would fight the user. Applying is what moves it out of
        // the pending snapshot and into the applied filters; see the test below for the difference.
        validator.updateFilter(RELYING_PARTY_GROUP, WITHOUT_RELYING_PARTY)
        validator.applyFilters()
        advanceUntilIdle()

        val grown = FilterableList(
            listOf(transaction("t1", "Verifier"), transaction("t2", null))
        )
        validator.initializeValidator(derivedFilters(grown), grown)
        validator.applyFilters()
        advanceUntilIdle()

        // t1 is visible because the group gained an item for its relying party; t2 stays hidden
        // because the user's deselection was carried across the rebuild.
        assertEquals(listOf("t1"), states.filteredIds())
    }

    @Test
    fun a_pending_selection_outlives_a_rebuild_and_reimposes_the_old_group() = runTest(dispatcher) {
        val (validator, states) = validatorWithStates()
        val empty = FilterableList(emptyList())
        validator.initializeValidator(derivedFilters(empty), empty)

        // Toggled but NOT applied, so it lives in `snapshotFilters`.
        validator.updateFilter(RELYING_PARTY_GROUP, WITHOUT_RELYING_PARTY)
        advanceUntilIdle()

        val grown = FilterableList(listOf(transaction("t1", "Verifier")))
        validator.initializeValidator(derivedFilters(grown), grown)
        validator.applyFilters()
        advanceUntilIdle()

        // `initializeValidator` merges the rebuilt groups into `appliedFilters` and leaves
        // `snapshotFilters` alone — and `applyFilters` then promotes that snapshot over the merge. So a
        // pending selection silently reinstates the group as it was before the rebuild, new items and
        // all. Found while writing the test above; a second route into the same class of staleness.
        assertEquals(emptyList(), states.filteredIds())
    }

    private companion object {
        const val RELYING_PARTY_GROUP = "relying_party"
        const val WITHOUT_RELYING_PARTY = "without_relying_party"
    }
}
