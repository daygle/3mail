package com.threemail.android.ui.screens.message

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.threemail.android.R
import com.threemail.android.ui.components.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    viewModel: MessageDetailViewModel,
    onNavigateBack: () -> Unit,
    onReply: (Long) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val message = state.message

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleStar() }) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = stringResource(R.string.mark_as_read),
                            tint = if (message?.isStarred == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            message?.let {
                FloatingActionButton(onClick = { onReply(it.id) }) {
                    Icon(Icons.Default.Reply, contentDescription = stringResource(R.string.reply))
                }
            }
        }
    ) { padding ->
        when {
            state.isLoading -> LoadingIndicator()
            message == null -> Text("Message not found", modifier = Modifier.padding(padding))
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(text = message.subject, style = MaterialTheme.typography.headlineLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "From: ${message.from.joinToString { it.toString() }}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "To: ${message.to.joinToString { it.toString() }}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    message.bodyHtml?.let { html ->
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { context ->
                                WebView(context).apply {
                                    webViewClient = WebViewClient()
                                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                                }
                            }
                        )
                    } ?: Text(text = message.bodyPlain ?: message.bodyPreview, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
