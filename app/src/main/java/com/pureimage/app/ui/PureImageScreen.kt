package com.pureimage.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File

@Composable
fun PureImageScreen(model: PureImageViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(model::select)
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.dismissMessage() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("PureImage", style = MaterialTheme.typography.headlineLarge)
            Text("Offline image cleanup. No account or internet needed.")
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Choose image") }
            BeforeAfterPreview(state.original, state.processed)
            Text(
                "Processes only on your device using mild noise reduction. " +
                    "This is not AI reconstruction or verified SynthID removal. " +
                    "Output is PNG, limited to 2048 pixels on the longest side; fine details may soften.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = model::purify, enabled = state.original != null && !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Clean up offline") }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(state.operation.orEmpty())
                }
                if (state.operation == "Cleaning image offline…") {
                    TextButton(onClick = model::cancel) { Text("Cancel") }
                }
            }
            Button(onClick = model::save,
                enabled = state.processed != null && !state.busy && !state.saved,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (state.saved) "Saved to gallery" else "Save image")
            }
        }
    }
}

@Composable
fun BeforeAfterPreview(before: File?, after: File?) {
    // Stacked panels stay legible on small phones and with large text settings.
    ImagePanel("Before", before)
    ImagePanel("After", after)
}

@Composable
private fun ImagePanel(label: String, image: File?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                if (image == null) Text(if (label == "Before") "Select a photo" else "Result appears here")
                else AsyncImage(model = image, contentDescription = "$label image",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
