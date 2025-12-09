package app.rive.runtime.example

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.Alignment as RiveAlignment
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.errors.RiveException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Activity to replicate bug #424: Crash when using RiveAnimationView in LazyColumn with fast scrolling
 * 
 * Issue: app.rive.runtime.kotlin.core.errors.RiveException: Accessing disposed C++ object RiveArtboardRenderer
 * 
 * Steps to reproduce:
 * 1. Scroll quickly up and down through the LazyColumn
 * 2. Or use the "Trigger Fast Scroll" button to automatically scroll
 * 3. The crash occurs due to race condition when resizing artboard while C++ object is being disposed
 */
class LazyColumnBugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                LazyColumnBugScreen()
            }
        }
    }
}

@Composable
fun LazyColumnBugScreen() {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var isAutoScrolling by remember { mutableStateOf(false) }
    var clickCount by remember { mutableStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "LazyColumn Bug Reproduction (Issue #424)",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Fast scroll to reproduce crash:\nRiveException: Accessing disposed C++ object",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = {
                            isAutoScrolling = !isAutoScrolling
                            if (isAutoScrolling) {
                                coroutineScope.launch {
                                    // Fast scroll up and down repeatedly
                                    while (isAutoScrolling) {
                                        listState.animateScrollToItem(50)
                                        delay(300)
                                        listState.animateScrollToItem(0)
                                        delay(300)
                                        listState.animateScrollToItem(80)
                                        delay(300)
                                        listState.animateScrollToItem(20)
                                        delay(300)
                                    }
                                }
                            }
                        }
                    ) {
                        Text(if (isAutoScrolling) "Stop Auto Scroll" else "Trigger Fast Scroll")
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(100) { index ->
                RiveListItem(
                    index = index,
                    onClick = { clickCount++ }
                )
            }
        }
    }
}

@Composable
fun RiveListItem(
    index: Int,
    onClick: () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val lifecycleOwner = LocalLifecycleOwner.current
    var buttonClickCount by remember { mutableStateOf(0) }
    
    // Recreation key forces AndroidView to create a fresh view after pause
    var recreationKey by remember { mutableIntStateOf(0) }
    var riveView by remember { mutableStateOf<RiveAnimationView?>(null) }
    var isViewDisposed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    riveView?.stop()
                    isViewDisposed = true
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Increment key to force AndroidView recreation
                    if (isViewDisposed) {
                        recreationKey++
                    }
                }
                else -> {}
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            riveView?.stop()
            riveView = null
            isViewDisposed = true
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(200.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // When recreationKey changes, the entire AndroidView is recreated
            key(recreationKey) {
                AndroidView(
                    factory = { context ->
                        // Factory creates a fresh view - reset disposal flag
                        isViewDisposed = false
                        RiveAnimationView(context).apply {
                            // Use a Rive animation - this reproduces the bug
                            setRiveResource(
                                resId = R.raw.basketball,
                                fit = Fit.CONTAIN,
                                alignment = RiveAlignment.CENTER,
                                autoplay = true
                            )
                        }.also { riveAnimationView ->
                            riveView = riveAnimationView
                            riveAnimationView.doOnAttach {
                                try {
                                    // Simulate production code pattern
                                    riveAnimationView.play()
                                } catch (e: RiveException) {
                                    // In production code, this would log to crash reporter
                                    android.util.Log.e("RiveBug424", 
                                        "Rive renderer disposed during initialization", e)
                                }
                            }
                        }
                    },
                    update = { view ->
                        if (!isViewDisposed && buttonClickCount > 0) {
                            try {
                                // Simulate state updates that might trigger the race condition
                                view.play()
                            } catch (e: RiveException) {
                                android.util.Log.e("RiveBug424", 
                                    "Rive renderer disposed during state update", e)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(150.dp)
                        .weight(1f)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Item #$index",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        buttonClickCount++
                        onClick()
                    }
                ) {
                    Text("Click: $buttonClickCount")
                }
            }
        }
    }
}
