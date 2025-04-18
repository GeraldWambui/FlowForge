package com.nachothegardener.flowforge.flowforge.core


import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * FlowForge - Builder pattern API for creating sophisticated flow pipelines
 */
class FlowForge<T>(private val source: Flow<T>) {

    /**
     * Converts the flow to a LoadState-wrapped flow
     */
    fun asLoadState(): FlowForgeState<T> = FlowForgeState(source.asLoadStateFlow())

    /**
     * Adds debounce with automatic loading state
     */
    fun debounce(timeout: Duration = 300.milliseconds): FlowForgeState<T> =
        FlowForgeState(source.debounceWithStatus(timeout.inWholeMilliseconds))

    /**
     * Adds retry behavior with sophisticated backoff
     */
    fun withRetry(
        maxAttempts: Int = 3,
        condition: (Throwable) -> Boolean = { true },
        strategy: RetryPolicy = RetryPolicy.Exponential()
    ): FlowForge<T> = FlowForge(source.retryWithPolicy(maxAttempts, condition, strategy))

    /**
     * Transform to a new type with LoadState preservation
     */
    fun <R> transform(transform: suspend (T) -> R): FlowForge<R> =
        FlowForge(source.map { transform(it) })

    /**
     * Transform to a new flow with LoadState and caching
     */
    fun <R> flatTransform(transform: suspend (T) -> Flow<R>): FlowForgeState<R> =
        FlowForgeState(source.flatMapLatestWithCache { transform(it) })

    /**
     * Collects the flow with lifecycle awareness
     */
    fun collectWith(
        lifecycleOwner: LifecycleOwner,
        lifecycleState: Lifecycle.State = Lifecycle.State.STARTED,
        collector: suspend (T) -> Unit
    ) {
        source.collectWithLifecycle(lifecycleOwner, lifecycleState, collector)
    }

    /**
     * Converts to StateFlow
     */
    fun toStateFlow(
        scope: CoroutineScope,
        initialValue: T
    ): StateFlow<T> = source.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = initialValue
    )

    companion object {
        /**
         * Creates a new FlowForge instance from any flow
         */
        fun <T> from(flow: Flow<T>): FlowForge<T> = FlowForge(flow)

        /**
         * Creates a new FlowForge instance from a StateFlow
         */
        fun <T> from(stateFlow: StateFlow<T>): FlowForge<T> = FlowForge(stateFlow)

        /**
         * Creates a new FlowForge instance from a suspending function
         */
        fun <T> fromSuspend(scope: CoroutineScope, block: suspend () -> T): FlowForgeState<T> =
            FlowForgeState(flowForgeScope(scope, block))
    }
}

/**
 * State-aware FlowForge variant that handles LoadState
 */
class FlowForgeState<T>(private val stateFlow: Flow<LoadState<T>>) {

    /**
     * Maps successful values while preserving loading/error states
     */
    fun <R> mapData(transform: (T) -> R): FlowForgeState<R> =
        FlowForgeState(stateFlow.map { state -> state.map(transform) })

    /**
     * Provides a fallback value for error states
     */
    fun withFallback(fallback: suspend (Throwable) -> T): FlowForgeState<T> =
        FlowForgeState(stateFlow.map { state ->
            when (state) {
                is LoadState.Error -> LoadState.Success(fallback(state.exception))
                else -> state
            }
        })

    /**
     * Adds recovery behavior for error states
     */
    fun withErrorRecovery(recovery: suspend (Throwable) -> Flow<T>): FlowForgeState<T> =
        FlowForgeState(flow {
            stateFlow.collect { state ->
                when (state) {
                    is LoadState.Error -> {
                        emit(LoadState.Loading)
                        try {
                            recovery(state.exception).collect { value ->
                                emit(LoadState.Success(value))
                            }
                        } catch (e: Throwable) {
                            emit(LoadState.Error(e))
                        }
                    }
                    else -> emit(state)
                }
            }
        })

    /**
     * Converts to a standard StateFlow with automatic state handling
     */
    fun toStateFlow(
        scope: CoroutineScope,
        initialState: LoadState<T> = LoadState.Loading
    ): StateFlow<LoadState<T>> = stateFlow.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = initialState
    )

    /**
     * Collects with lifecycle awareness and state handling
     */
    fun collectWith(
        lifecycleOwner: LifecycleOwner,
        lifecycleState: Lifecycle.State = Lifecycle.State.STARTED,
        onLoading: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
        onSuccess: (T) -> Unit
    ) {
        stateFlow.collectStateWithLifecycle(
            lifecycleOwner,
            lifecycleState,
            onLoading,
            onError,
            onSuccess
        )
    }

    /**
     * Unwraps LoadState to return raw values with error handling
     */
    fun asFlow(): Flow<T> = stateFlow.mapNotNull { state ->
        when (state) {
            is LoadState.Success -> state.data
            is LoadState.Error -> throw state.exception
            is LoadState.Loading -> null
        }
    }
}
