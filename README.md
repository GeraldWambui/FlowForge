<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
  <title>FlowForge Documentation</title>
</head>
<body>

  <h1>🚀 FlowForge Documentation</h1>

  <p><strong>FlowForge</strong> is a sophisticated Kotlin library for building efficient, lifecycle-aware reactive flows with elegant state management. This documentation provides a comprehensive guide on how to use FlowForge effectively in your Android applications.</p>

  <hr/>

  <h2>📚 Table of Contents</h2>
  <ol>
    <li><a href="#getting-started">Getting Started</a></li>
    <li><a href="#core-concepts">Core Concepts</a></li>
    <li><a href="#api-reference">API Reference</a></li>
    <li><a href="#best-practices">Best Practices</a></li>
    <li><a href="#migration-guide">Migration Guide</a></li>
    <li><a href="#performance-considerations">Performance Considerations</a></li>
    <li><a href="#testing">Testing</a></li>
    <li><a href="#advanced-usage">Advanced Usage</a></li>
  </ol>

  <hr/>

  <h2 id="getting-started">🛠️ Getting Started</h2>

  <h3>🔧 Installation</h3>
  <pre><code>
dependencies {
    implementation("com.flowforge:flowforge-core:1.0.0")
    implementation("com.flowforge:flowforge-android:1.0.0")
    testImplementation("com.flowforge:flowforge-testing:1.0.0")
}
  </code></pre>

  <h3>✨ Basic Usage</h3>
  <pre><code>
// In your ViewModel
val searchResults = FlowForge.from(searchQuery)
    .debounce(300.milliseconds)
    .flatTransform { query -> api.searchUsers(query).asFlow() }
    .toStateFlow(viewModelScope, LoadState.loading())

// In your UI
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    searchResults.collectWith(
        lifecycleOwner = viewLifecycleOwner,
        onLoading = { showLoading() },
        onError = { error -> showError(error) },
        onSuccess = { users -> showUsers(users) }
    )
}
  </code></pre>

  <hr/>

  <h2 id="core-concepts">🧠 Core Concepts</h2>

  <h3>LoadState</h3>
  <p>Represents the three core states of any async operation:</p>
  <ul>
    <li>🔄 <strong>Loading</strong> – Operation in progress</li>
    <li>✅ <strong>Success</strong> – Completed with data</li>
    <li>❌ <strong>Error</strong> – Failed with an exception</li>
  </ul>

  <h3>FlowForge Builders</h3>
  <ul>
    <li><code>FlowForge.from(flow)</code></li>
    <li><code>FlowForge.fromSuspend(scope) { ... }</code></li>
  </ul>

  <h3>🔁 Lifecycle Integration</h3>
  <pre><code>
userFlow.collectWith(
    lifecycleOwner = viewLifecycleOwner,
    onSuccess = { users -> /* UI update */ }
)
  </code></pre>

  <hr/>

  <h2 id="api-reference">🧩 API Reference</h2>

  <h3>🔤 Core Types</h3>

  <h4>LoadState&lt;T&gt;</h4>
  <pre><code>
sealed class LoadState<out T> {
    data object Loading : LoadState<Nothing>()
    data class Success<T>(val data: T) : LoadState<T>()
    data class Error(val exception: Throwable) : LoadState<Nothing>()
}
  </code></pre>

  <h4>FlowForge&lt;T&gt;</h4>
  <pre><code>
class FlowForge<T>(private val source: Flow<T>) {
    fun asLoadState(): FlowForgeState<T>
    fun debounce(timeout: Duration): FlowForgeState<T>
    fun withRetry(...): FlowForge<T>
    fun &lt;R&gt; transform(transform: suspend (T) -> R): FlowForge<R>
    fun &lt;R&gt; flatTransform(transform: suspend (T) -> Flow<R>): FlowForgeState<R>
}
  </code></pre>

  <h4>FlowForgeState&lt;T&gt;</h4>
  <pre><code>
class FlowForgeState<T>(private val stateFlow: Flow<LoadState<T>>) {
    fun &lt;R&gt; mapData(transform: (T) -> R): FlowForgeState<R>
    fun withFallback(fallback: suspend (Throwable) -> T): FlowForgeState<T>
    fun withErrorRecovery(recovery: suspend (Throwable) -> Flow<T>): FlowForgeState<T>
}
  </code></pre>

  <h3>🧮 Key Extensions</h3>

  <h4>🔁 State Transformation</h4>
  <ul>
    <li><code>Flow&lt;T&gt;.asLoadStateFlow()</code></li>
    <li><code>Flow&lt;T&gt;.debounceWithStatus(timeoutMillis: Long)</code></li>
    <li><code>Flow&lt;T&gt;.flatMapLatestWithCache(transform)</code></li>
  </ul>

  <h4>⚠️ Error Handling</h4>
  <ul>
    <li><code>retryWithPolicy</code></li>
    <li><code>withFallback</code></li>
  </ul>

  <h4>🔄 Lifecycle Integration</h4>
  <ul>
    <li><code>collectWithLifecycle</code></li>
    <li><code>collectStateWithLifecycle</code></li>
  </ul>

  <hr/>

  <h2 id="best-practices">💡 Best Practices</h2>

  <h3>🧱 ViewModel Structure</h3>
  <pre><code>
class UserViewModel(private val userRepository: UserRepository) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val searchResults = FlowForge.from(searchQuery)
        .debounce()
        .flatTransform { query ->
            flowForgeScope(viewModelScope) {
                userRepository.searchUsers(query)
            }
        }
        .toStateFlow(viewModelScope, LoadState.loading())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
  </code></pre>

  <h3>🛡️ Error Handling</h3>
  <pre><code>
userFlow
    .withRetry(
        maxAttempts = 3,
        condition = { it is NetworkError && it.isTransient },
        strategy = RetryPolicy.ExponentialWithJitter()
    )
    .withFallback { error -> 
        analyticsTracker.trackError("user_data_failure", error)
        cachedUsers ?: emptyList()
    }
  </code></pre>

  <h3>⚙️ Performance Optimization</h3>
  <pre><code>
FlowForgeConfig.apply {
    defaultBufferCapacity = 64
    defaultIODispatcher = Dispatchers.IO
    enablePerformanceTracking = BuildConfig.DEBUG
}
  </code></pre>

  <hr/>

  <h2 id="migration-guide">🔁 Migration Guide</h2>

  <h3>🔄 From Standard Flows</h3>
  <pre><code>
// Before
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        userFlow
            .onStart { showLoading() }
            .catch { error -> showError(error) }
            .collect { users -> showUsers(users) }
    }
}

// After
userFlow.asLoadState().collectWith(
    lifecycleOwner = viewLifecycleOwner,
    onLoading = { showLoading() },
    onError = { error -> showError(error) },
    onSuccess = { users -> showUsers(users) }
)
  </code></pre>

  <h3>🔄 From MutableStateFlow</h3>
  <pre><code>
val userState = FlowForge.fromSuspend(viewModelScope) {
    api.getUsers()
}.toStateFlow(viewModelScope)
  </code></pre>

  <hr/>

  <h2 id="performance-considerations">⚡ Performance Considerations</h2>

  <h3>🧠 Memory Management</h3>
  <ul>
    <li>Configurable buffer sizes</li>
    <li>Chunked processing</li>
    <li>Shared flow optimizations</li>
  </ul>

  <h3>⚙️ CPU Efficiency</h3>
  <ul>
    <li>Dispatcher tuning</li>
    <li>Conflation for high-frequency data</li>
    <li>Built-in performance metrics</li>
  </ul>

  <h3>📱 Device-Specific Tuning</h3>
  <pre><code>
configureForLowEndDevices()
configureForHighEndDevices()
  </code></pre>

  <hr/>

  <h2 id="testing">🧪 Testing</h2>
  <pre><code>
@Test
fun `search results emit success state after debounce`() = FlowTestHarness.runTest {
    val searchQuery = createTestFlow("test")

    val resultsFlow = searchQuery
        .debounceWithStatus(300)
        .flatMapLatestWithCache { query ->
            flow {
                emit(listOf("Result for $query"))
            }
        }

    searchQuery.value = "android"
    advanceTimeBy(100.milliseconds)
    advanceTimeBy(300.milliseconds)

    assertValueEquals(
        resultsFlow.map { it.getOrNull() },
        listOf("Result for android")
    )
}
  </code></pre>

  <hr/>

  <h2 id="advanced-usage">🧙 Advanced Usage</h2>

  <h3>🧩 Custom Operators</h3>
  <pre><code>
fun &lt;T&gt; Flow&lt;T&gt;.throttleFirst(windowDuration: Long): Flow&lt;T&gt; = flow {
    var lastEmissionTime = 0L
    collect { value ->
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmissionTime > windowDuration) {
            lastEmissionTime = currentTime
            emit(value)
        }
    }
}

FlowForge&lt;T&gt;.throttleFirst(windowDuration: Long): FlowForge&lt;T&gt; =
    FlowForge(source.throttleFirst(windowDuration))
  </code></pre>

  <h3>🔌 Integration with Other Libraries</h3>
  <pre><code>
// RxJava
fun &lt;T&gt; Observable&lt;T&gt;.toFlowForge(): FlowForge&lt;T&gt; = FlowForge.from(toFlow())

// LiveData
fun &lt;T&gt; LiveData&lt;T&gt;.toFlowForge(): FlowForge&lt;T&gt; = FlowForge.from(asFlow())
  </code></pre>

  <h3>🛠️ Advanced Configuration</h3>
  <pre><code>
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        FlowForgeConfig.apply {
            enablePerformanceTracking = BuildConfig.DEBUG
            defaultIODispatcher = Dispatchers.IO.limitedParallelism(8)
            defaultSharingTimeout = 5000.milliseconds
        }
    }
}
  </code></pre>

  <hr/>

  <h2>🌟 Contribute</h2>
  <p>Found a bug or want to contribute? Feel free to <a href="https://github.com/your-org/flowforge/issues">open an issue</a> or submit a PR!</p>

  <hr/>

  <h2>📄 License</h2>
  <p>MIT License © <em>Gerald Wambui</em></p>

</body>
</html>
