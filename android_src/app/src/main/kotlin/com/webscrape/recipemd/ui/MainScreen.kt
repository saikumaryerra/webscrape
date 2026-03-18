package com.webscrape.recipemd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ScraperViewModel) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RecipeMD") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // URL input
            OutlinedTextField(
                value = state.urlInput,
                onValueChange = { viewModel.updateUrlInput(it) },
                label = { Text("Recipe URL") },
                placeholder = { Text("https://www.indianhealthyrecipes.com/...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.isRunning,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.scrapeSingleUrl(state.urlInput) },
                    enabled = !state.isRunning && state.urlInput.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Scrape URL")
                }

                OutlinedButton(
                    onClick = { viewModel.scrapeFromSitemap(limit = 0) },
                    enabled = !state.isRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Scrape All")
                }

                OutlinedButton(
                    onClick = { viewModel.scrapeFromSitemap(limit = 10) },
                    enabled = !state.isRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Try 10")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress
            if (state.isRunning && state.total > 0) {
                val progress = (state.succeeded + state.failed).toFloat() / state.total
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Stats
            if (state.total > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Succeeded: ${state.succeeded}",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text("Failed: ${state.failed}")
                    Text("Total: ${state.total}")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Output: ${viewModel.getOutputDir().absolutePath}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Log output
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                val listState = rememberLazyListState()

                // Auto-scroll to bottom
                LaunchedEffect(state.statusLog.size) {
                    if (state.statusLog.isNotEmpty()) {
                        listState.animateScrollToItem(state.statusLog.size - 1)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                ) {
                    if (state.statusLog.isEmpty()) {
                        item {
                            Text(
                                "Enter a recipe URL or tap \"Scrape All\" to discover recipes from the sitemap.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.statusLog) { line ->
                        Text(
                            text = line,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
