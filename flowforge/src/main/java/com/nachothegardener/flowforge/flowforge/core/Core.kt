package com.nachothegardener.flowforge.flowforge.core


import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Core state wrapper that tracks loading, success, and error states
 */
sealed class LoadState<out T> {
    data object Loading : LoadState<Nothing>()
    data class Success<T>(val data: T) : LoadState<T>()
    data class Error(val exception: Throwable) : LoadState<Nothing>()

    companion object {
        fun <T> loading(): LoadState<T> = Loading
        fun <T> success(data: T): LoadState<T> = Success(data)
        fun <T> error(exception: Throwable): LoadState<T> = Error(exception)
    }

    inline fun onSuccess(action: (T) -> Unit): LoadState<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (Throwable) -> Unit): LoadState<T> {
        if (this is Error) action(exception)
        return this
    }

    inline fun onLoading(action: () -> Unit): LoadState<T> {
        if (this is Loading) action()
        return this
    }

    fun <R> map(transform: (T) -> R): LoadState<R> {
        return when (this) {
            is Success -> Success(transform(data))
            is Error -> Error(exception)
            is Loading -> Loading
        }
    }

    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw exception
        is Loading -> throw IllegalStateException("Cannot get value while loading")
    }
}

/**
 * Retry policies for automatic flow retry behavior
 */
sealed class RetryPolicy {
    data object Immediate : RetryPolicy()
    data class Fixed(val delay: Duration) : RetryPolicy()
    data class Exponential(
        val initialDelay: Duration = 100.milliseconds,
        val factor: Double = 2.0,
        val maxDelay: Duration = 10000.milliseconds
    ) : RetryPolicy()
    data class ExponentialWithJitter(
        val initialDelay: Duration = 100.milliseconds,
        val factor: Double = 2.0,
        val maxDelay: Duration = 10000.milliseconds,
        val jitterFactor: Double = 0.5
    ) : RetryPolicy()
}

/**
 * Extension to collect a flow with lifecycle awareness
 */
fun <T> Flow<T>.collectWithLifecycle(
    lifecycleOwner: LifecycleOwner,
    lifecycleState: Lifecycle.State = Lifecycle.State.STARTED,
    collector: suspend (T) -> Unit
) {
    lifecycleOwner.lifecycleScope.launch {
        lifecycleOwner.repeatOnLifecycle(lifecycleState) {
            collect { collector(it) }
        }
    }
}

/**
 * Extension to collect a LoadState flow with lifecycle awareness and automatic state handling
 */
fun <T> Flow<LoadState<T>>.collectStateWithLifecycle(
    lifecycleOwner: LifecycleOwner,
    lifecycleState: Lifecycle.State = Lifecycle.State.STARTED,
    onLoading: () -> Unit = {},
    onError: (Throwable) -> Unit = {},
    onSuccess: (T) -> Unit
) {
    collectWithLifecycle(lifecycleOwner, lifecycleState) { state ->
        when (state) {
            is LoadState.Loading -> onLoading()
            is LoadState.Error -> onError(state.exception)
            is LoadState.Success -> onSuccess(state.data)
        }
    }
}

/**
 * Transforms a regular Flow into a LoadState Flow that tracks the emission state
 */
fun <T> Flow<T>.asLoadStateFlow(): Flow<LoadState<T>> = flow {
    emit(LoadState.loading())
    try {
        collect { value ->
            emit(LoadState.success(value))
        }
    } catch (e: Throwable) {
        emit(LoadState.error(e))
    }
}

/**
 * Debounces a flow while emitting Loading state during the debounce period
 */
fun <T> Flow<T>.debounceWithStatus(
    timeoutMillis: Long
): Flow<LoadState<T>> = flow {
    emit(LoadState.loading())
    debounce(timeoutMillis)
        .collect { value -> emit(LoadState.success(value)) }
}.catch { error ->
    emit(LoadState.error(error))
}

/**
 * Advanced retry with configurable policy and predicate
 */
fun <T> Flow<T>.retryWithPolicy(
    maxAttempts: Int = 3,
    retryPredicate: (Throwable) -> Boolean = { true },
    backoffStrategy: RetryPolicy = RetryPolicy.Exponential()
): Flow<T> = retryWhen { cause, attempt ->
    if (attempt >= maxAttempts || !retryPredicate(cause)) return@retryWhen false

    when (backoffStrategy) {
        is RetryPolicy.Immediate -> kotlinx.coroutines.delay(0)
        is RetryPolicy.Fixed -> kotlinx.coroutines.delay(backoffStrategy.delay)
        is RetryPolicy.Exponential -> {
            val delayMillis = backoffStrategy.initialDelay.inWholeMilliseconds *
                    backoffStrategy.factor.pow(attempt.toDouble()).toLong()
            val cappedDelay = delayMillis.coerceAtMost(backoffStrategy.maxDelay.inWholeMilliseconds)
            kotlinx.coroutines.delay(cappedDelay)
        }
        is RetryPolicy.ExponentialWithJitter -> {
            val baseDelay = backoffStrategy.initialDelay.inWholeMilliseconds *
                    backoffStrategy.factor.pow(attempt.toDouble()).toLong()
            val cappedDelay = baseDelay.coerceAtMost(backoffStrategy.maxDelay.inWholeMilliseconds)
            val jitter = (Math.random() * backoffStrategy.jitterFactor * cappedDelay).toLong()
            kotlinx.coroutines.delay(cappedDelay - jitter)
        }
    }

    true
}

/**
 * Flow error recovery with fallback value
 */
fun <T> Flow<T>.withFallback(fallback: suspend (Throwable) -> T): Flow<T> = catch { error ->
    emit(fallback(error))
}

/**
 * Flow transformation with cached values during loading new data
 * (keeps last successful value visible while loading new data)
 */
fun <T, R> Flow<T>.flatMapLatestWithCache(transform: suspend (T) -> Flow<R>): Flow<LoadState<R>> = map { value ->
    transform(value).asLoadStateFlow()
}.flatMapLatest { it }
    .scan<LoadState<R>, LoadState<R>>(LoadState.loading()) { accumulated, current ->
        when {
            current is LoadState.Loading && accumulated is LoadState.Success -> accumulated
            else -> current
        }
    }

/**
 * Creates a flow with automatic ViewModel scope cancellation
 */
fun <T> flowForgeScope(scope: CoroutineScope, block: suspend () -> T): Flow<LoadState<T>> = flow {
    emit(LoadState.loading())
    val result = block()
    emit(LoadState.success(result))
}.catch { error ->
    emit(LoadState.error(error))
}.flowOn(scope.coroutineContext)




/**
 * Configuration for FlowForge performance tuning
 */
object FlowForgeConfig {
    /**
     * Default dispatcher for compute-intensive transformations
     */
    var defaultComputeDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * Default dispatcher for IO operations
     */
    var defaultIODispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Default buffer capacity for flow operations
     */
    var defaultBufferCapacity: Int = 64

    /**
     * Default sharing policy timeout
     */
    var defaultSharingTimeout: Duration = 5000.milliseconds

    /**
     * Default conflation behavior
     */
    var enableConflationByDefault: Boolean = true

    /**
     * Enables performance tracking
     */
    var enablePerformanceTracking: Boolean = false

    /**
     * Reset configuration to defaults
     */
    fun resetToDefaults() {
        defaultComputeDispatcher = Dispatchers.Default
        defaultIODispatcher = Dispatchers.IO
        defaultBufferCapacity = 64
        defaultSharingTimeout = 5000.milliseconds
        enableConflationByDefault = true
        enablePerformanceTracking = false
    }
}

/**
 * Performance-optimized version of asLoadStateFlow
 */
fun <T> Flow<T>.asLoadStateFlow(
    context: CoroutineContext = FlowForgeConfig.defaultComputeDispatcher,
    bufferCapacity: Int = FlowForgeConfig.defaultBufferCapacity
): Flow<LoadState<T>> = flow {
    emit(LoadState.loading())
    try {
        collect { value ->
            emit(LoadState.success(value))
        }
    } catch (e: Throwable) {
        emit(LoadState.error(e))
    }
}
    .buffer(bufferCapacity)
    .flowOn(context)

/**
 * Performance-optimized debounce with status
 */
fun <T> Flow<T>.debounceWithStatus(
    timeoutMillis: Long,
    context: CoroutineContext = FlowForgeConfig.defaultComputeDispatcher
): Flow<LoadState<T>> = flow {
    emit(LoadState.loading())
    debounce(timeoutMillis)
        .collect { value -> emit(LoadState.success(value)) }
}.catch { error ->
    emit(LoadState.error(error))
}
    .flowOn(context)

/**
 * Memory-efficient shared flow creation
 */
fun <T> Flow<T>.toSharedFlow(
    scope: CoroutineScope,
    replay: Int = 1,
    extraBufferCapacity: Int = FlowForgeConfig.defaultBufferCapacity,
    onBufferOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST
): SharedFlow<T> = this.shareIn(
    scope = scope,
    started = SharingStarted.WhileSubscribed(FlowForgeConfig.defaultSharingTimeout.inWholeMilliseconds),
    replay = replay
)

/**
 * Memory-efficient state flow creation
 */
fun <T> Flow<T>.toEfficientStateFlow(
scope: CoroutineScope,
initialValue: T,
started: SharingStarted = SharingStarted.WhileSubscribed(FlowForgeConfig.defaultSharingTimeout.inWholeMilliseconds)
): StateFlow<T> = this.stateIn(
scope = scope,
started = started,
initialValue = initialValue
)

/**
 * Performance tracking extension for flow operations
 */
fun <T> Flow<T>.trackPerformance(
    operationName: String = "Flow Operation",
    tracingEnabled: Boolean = FlowForgeConfig.enablePerformanceTracking
): Flow<T> = if (!tracingEnabled) {
    this
} else {
    flow {
        val startTime = System.nanoTime()
        var itemCount = 0

        try {
            collect { value ->
                itemCount++
                emit(value)
            }
        } finally {
            val endTime = System.nanoTime()
            val durationMs = (endTime - startTime) / 1_000_000.0
            FlowForgeLogger.logPerformance(
                operation = operationName,
                durationMs = durationMs,
                itemsProcessed = itemCount
            )
        }
    }
}

/**
 * Memory-optimized flow transformations for large data sets
 */
fun <T, R> Flow<T>.mapChunked(
    chunkSize: Int = 100,
    context: CoroutineContext = FlowForgeConfig.defaultComputeDispatcher,
    transform: suspend (List<T>) -> List<R>
): Flow<R> = flow {
    val buffer = mutableListOf<T>()

    collect { item ->
        buffer.add(item)

        if (buffer.size >= chunkSize) {
            val chunk = buffer.toList()
            buffer.clear()

            transform(chunk).forEach { result ->
                emit(result)
            }
        }
    }

    // Process any remaining items
    if (buffer.isNotEmpty()) {
        transform(buffer).forEach { result ->
            emit(result)
        }
    }
}.flowOn(context)

/**
 * Performance logger for FlowForge operations
 */
object FlowForgeLogger {
    private val performanceMetrics = mutableMapOf<String, MutableList<PerformanceMetric>>()

    data class PerformanceMetric(
        val operation: String,
        val durationMs: Double,
        val itemsProcessed: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun logPerformance(operation: String, durationMs: Double, itemsProcessed: Int) {
        val metric = PerformanceMetric(operation, durationMs, itemsProcessed)

        synchronized(performanceMetrics) {
            performanceMetrics.getOrPut(operation) { mutableListOf() }.add(metric)
        }

        // Log to console or analytics system
        println("FlowForge Performance: $operation took ${durationMs}ms for $itemsProcessed items")
    }

    fun getMetricsForOperation(operation: String): List<PerformanceMetric> {
        return synchronized(performanceMetrics) {
            performanceMetrics[operation]?.toList() ?: emptyList()
        }
    }

    fun getAverageMetricsForOperation(operation: String): PerformanceMetric? {
        val metrics = getMetricsForOperation(operation)
        if (metrics.isEmpty()) return null

        val avgDuration = metrics.map { it.durationMs }.average()
        val totalItems = metrics.sumOf { it.itemsProcessed }

        return PerformanceMetric(
            operation = operation,
            durationMs = avgDuration,
            itemsProcessed = totalItems,
            timestamp = System.currentTimeMillis()
        )
    }

    fun clearMetrics() {
        synchronized(performanceMetrics) {
            performanceMetrics.clear()
        }
    }
}

/**
 * Example of memory optimization configurations
 */
fun configureForLowEndDevices() {
    FlowForgeConfig.apply {
        defaultBufferCapacity = 16
        enableConflationByDefault = true
        defaultSharingTimeout = 2000.milliseconds
    }
}

/**
 * Example of performance optimization for high-end devices
 */
fun configureForHighEndDevices() {
    FlowForgeConfig.apply {
        defaultBufferCapacity = 128
        enableConflationByDefault = false
        defaultSharingTimeout = 10000.milliseconds
        enablePerformanceTracking = true
    }
}

/**
 * Example of using performance-optimized FlowForge
 */
