package com.nachothegardener.flowforge.flowforge

import com.nachothegardener.flowforge.flowforge.core.LoadState
import com.nachothegardener.flowforge.flowforge.core.debounceWithStatus
import com.nachothegardener.flowforge.flowforge.core.flatMapLatestWithCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */


/**
 * Test harness for testing flows with time control
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTestHarness(private val testScope: TestScope) {

    /**
     * Advances virtual time by the specified duration
     */
    fun advanceTimeBy(duration: Duration) {
        testScope.advanceTimeBy(duration.inWholeMilliseconds)
    }

    /**
     * Asserts that a flow emits the expected value after optional time advancement
     */
    suspend fun <T> assertValueEquals(flow: Flow<T>, expectedValue: T, advanceBy: Duration = 0.milliseconds) {
        val values = mutableListOf<T>()
        val collectJob = testScope.launch {
            flow.toList(values)
        }

        advanceTimeBy(advanceBy)
        assertTrue(values.contains(expectedValue), "Expected $expectedValue but received ${values.joinToString()}")
        collectJob.cancel()
    }

    /**
     * Asserts that a LoadState flow emits an error of the specified type
     */
    suspend fun <T> assertErrorState(flow: Flow<LoadState<T>>, errorType: Class<out Throwable>) {
        val states = mutableListOf<LoadState<T>>()
        val collectJob = testScope.launch {
            flow.toList(states)
        }

        val hasErrorOfType = states.any { state ->
            state is LoadState.Error && errorType.isInstance(state.exception)
        }

        assertTrue(hasErrorOfType, "No error of type ${errorType.simpleName} was emitted. Received: ${states.joinToString()}")
        collectJob.cancel()
    }

    /**
     * Simulates a network error by throwing an exception in the downstream flow
     */
    fun simulateNetworkError(errorType: Throwable = NetworkError("Simulated network error")) {
        throw errorType
    }

    /**
     * Creates a mutable flow that can be used to simulate user input
     */
    fun <T> createTestFlow(initialValue: T): MutableStateFlow<T> {
        return MutableStateFlow(initialValue)
    }

    companion object {
        /**
         * Creates a new test harness with controlled time
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun runTest(block: suspend FlowTestHarness.() -> Unit) = runTest(UnconfinedTestDispatcher()) {
            FlowTestHarness(this).block()
        }
    }
}

/**
 * Mock network error for testing
 */
class NetworkError(message: String) : Exception(message)

/**
 * Extension function to verify LoadState transitions
 */
fun <T> List<LoadState<T>>.assertContainsSequence(vararg states: LoadState<*>) {
    val sequence = states.toList()

    // Find the starting index of our sequence
    val startIndex = this.indices.find { startIdx ->
        if (startIdx + sequence.size > this.size) return@find false

        sequence.indices.all { seqIdx ->
            val actualState = this[startIdx + seqIdx]
            val expectedState = sequence[seqIdx]

            when {
                actualState is LoadState.Loading && expectedState is LoadState.Loading -> true
                actualState is LoadState.Error && expectedState is LoadState.Error -> true
                actualState is LoadState.Success<*> && expectedState is LoadState.Success<*> -> true
                else -> false
            }
        }
    }

    assertTrue(startIndex != null, "Expected state sequence not found in emissions")
}

/**
 * Example usage of the test harness
 */
fun exampleTest() = FlowTestHarness.runTest {
    // Create a test flow
    val searchQuery = createTestFlow("test")

    // Create the flow under test
    val resultsFlow = searchQuery
        .debounceWithStatus(300)
        .flatMapLatestWithCache { query ->
            flow {
                // Simulate API call
                emit(listOf("Result for $query"))
            }
        }

    // Test the flow behavior
    searchQuery.value = "android"
    advanceTimeBy(100.milliseconds)
    // Should still be in loading state

    advanceTimeBy(300.milliseconds)
    // Should now have results

    assertValueEquals(
        resultsFlow.map { it.getOrNull() },
        listOf("Result for android"),
        400.milliseconds
    )
}