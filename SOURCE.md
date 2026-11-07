# PureImage Offline source

## .github/workflows/android-apk.yml

```
name: Build PureImage APK
on:
  push:
    branches: [main]
  workflow_dispatch:
permissions:
  contents: read
concurrency:
  group: android-${{ github.ref }}
  cancel-in-progress: true
jobs:
  build:
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v5
      - name: Set up Java 17
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
      - name: Install SDK packages
        run: '"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0"'
      - name: Build and lint
        run: |
          chmod +x gradlew
          ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
      - name: Upload installable debug APK
        uses: actions/upload-artifact@v4
        with:
          name: PureImage-offline-${{ github.run_number }}
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 14

```

## app/build.gradle.kts

```
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.pureimage.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pureimage.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1-offline"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
}

```

## app/src/main/AndroidManifest.xml

```
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Photo Picker grants URI access. App-owned MediaStore writes need no
         storage permission on minSdk 29+. -->
    <application android:name=".PureImageApplication" android:label="PureImage"
        android:theme="@style/Theme.PureImage" android:allowBackup="false"
        android:supportsRtl="true" android:usesCleartextTraffic="false">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>

```

## app/src/main/java/com/pureimage/app/MainActivity.kt

```
package com.pureimage.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.pureimage.app.ui.PureImageScreen
import com.pureimage.app.ui.PureImageViewModel

class MainActivity : ComponentActivity() {
    private val model: PureImageViewModel by viewModels {
        PureImageViewModel.Factory((application as PureImageApplication).repository)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { PureImageScreen(model) } }
    }
}

```

## app/src/main/java/com/pureimage/app/PureImageApplication.kt

```
package com.pureimage.app

import android.app.Application
import com.pureimage.app.data.ImageRepository

class PureImageApplication : Application() {
    val repository by lazy { ImageRepository(this) }
}

```

## app/src/main/java/com/pureimage/app/data/ImageRepository.kt

```
package com.pureimage.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class ImageRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.cacheDir, "working_images").apply { mkdirs() }

    // All selected content is copied immediately: no persistent gallery grant needed.
    suspend fun importImage(uri: Uri): File = withContext(Dispatchers.IO) {
        val file = File.createTempFile("input_", ".img", directory)
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IOException("Unable to open the selected image.")
            input.use { source -> file.outputStream().use { copyLimited(source, it) } }
            validate(file)
            currentCoroutineContext().ensureActive()
            file
        } catch (e: Exception) { file.delete(); throw e }
    }

    suspend fun reconstruct(source: File): File = withContext(Dispatchers.Default) {
        validate(source)
        val output = File.createTempFile("output_", ".png", directory)
        var decoded: Bitmap? = null
        var result: Bitmap? = null
        try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(
                    maxOf(1, (info.size.width * scale).toInt()),
                    maxOf(1, (info.size.height * scale).toInt())
                )
            }
            decoded = bitmap
            currentCoroutineContext().ensureActive()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val coroutineContext = currentCoroutineContext()
            val cleaned = LocalCleanup.apply(pixels, bitmap.width, bitmap.height) {
                coroutineContext.ensureActive()
            }
            val cleanedBitmap = Bitmap.createBitmap(cleaned, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            result = cleanedBitmap
            output.outputStream().use {
                check(cleanedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Unable to encode image." }
            }
            require(output.length() <= MAX_BYTES) { "Processed image exceeds 25 MiB." }
            currentCoroutineContext().ensureActive()
            output
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            decoded?.recycle()
            result?.recycle()
        }
    }

    suspend fun saveToGallery(file: File): Uri = withContext(Dispatchers.IO) {
        val mime = validate(file)
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PureImage_${UUID.randomUUID()}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PureImage")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create a gallery entry.")
        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("Unable to write to the gallery.")
            stream.use { output -> file.inputStream().use { copyLimited(it, output) } }
            currentCoroutineContext().ensureActive()
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            check(published == 1) { "Unable to publish the gallery image." }
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    suspend fun delete(file: File?) = withContext(Dispatchers.IO) { file?.delete(); Unit }

    private fun validate(file: File): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Invalid or unsupported image." }
        require(options.outWidth.toLong() * options.outHeight <= 40_000_000L) {
            "Please select an image no larger than 40 megapixels."
        }
        val mime = options.outMimeType
        require(mime in setOf("image/png", "image/jpeg", "image/webp")) {
            "Please use a PNG, JPEG, or WebP image."
        }
        return requireNotNull(mime)
    }

    private suspend fun copyLimited(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            require(total <= MAX_BYTES) { "Image exceeds the 25 MiB limit." }
            output.write(buffer, 0, count)
        }
        require(total > 0) { "Image is empty." }
    }

    companion object { private const val MAX_BYTES = 25L * 1024 * 1024 }
}

```

## app/src/main/java/com/pureimage/app/data/LocalCleanup.kt

```
package com.pureimage.app.data

import kotlin.math.abs

/** Mild 3x3 median blend. Classical denoising, not AI or a watermark detector. */
object LocalCleanup {
    fun apply(input: IntArray, width: Int, height: Int, checkActive: () -> Unit = {}): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == input.size.toLong())
        val output = input.copyOf()
        val red = IntArray(9)
        val green = IntArray(9)
        val blue = IntArray(9)
        for (y in 0 until height) {
            checkActive()
            for (x in 0 until width) {
                val center = input[y * width + x]
                // Preserve partially transparent pixels and their hidden RGB exactly.
                if ((center ushr 24) != 255) continue
                var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val c = input[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
                    if ((c ushr 24) != 255) continue
                    red[count] = (c ushr 16) and 255
                    green[count] = (c ushr 8) and 255
                    blue[count] = c and 255
                    count++
                }
                red.sort(0, count); green.sort(0, count); blue.sort(0, count)
                fun blend(channel: Int, median: Int): Int =
                    if (abs(channel - median) <= 40) (channel * 3 + median + 2) / 4 else channel
                val r = blend((center ushr 16) and 255, red[count / 2])
                val g = blend((center ushr 8) and 255, green[count / 2])
                val b = blend(center and 255, blue[count / 2])
                output[y * width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return output
    }
}

```

## app/src/main/java/com/pureimage/app/ui/PureImageScreen.kt

```
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

```

## app/src/main/java/com/pureimage/app/ui/PureImageViewModel.kt

```
package com.pureimage.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pureimage.app.data.ImageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ImageUiState(
    val original: File? = null,
    val processed: File? = null,
    val operation: String? = null,
    val message: String? = null,
    val saved: Boolean = false
) { val busy: Boolean get() = operation != null }

class PureImageViewModel(private val repository: ImageRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ImageUiState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null

    fun select(uri: Uri) = runOperation("Opening image…") {
        val file = repository.importImage(uri)
        val old = mutableState.value
        mutableState.update { it.copy(original = file, processed = null, saved = false) }
        repository.delete(old.original)
        repository.delete(old.processed)
    }

    fun purify() {
        val source = mutableState.value.original ?: return
        runOperation("Cleaning image offline…") {
            val result = repository.reconstruct(source)
            val previous = mutableState.value.processed
            mutableState.update { it.copy(processed = result, saved = false) }
            repository.delete(previous)
        }
    }

    fun save() {
        val result = mutableState.value.processed ?: return
        runOperation("Saving image…") {
            // Complete the MediaStore transaction even if the screen is closed.
            withContext(NonCancellable) { repository.saveToGallery(result) }
            mutableState.update { it.copy(saved = true, message = "Saved to Pictures/PureImage.") }
        }
    }

    fun cancel() { if (mutableState.value.operation == "Cleaning image offline…") task?.cancel() }
    fun dismissMessage() { mutableState.update { it.copy(message = null) } }

    private fun runOperation(label: String, block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(operation = label, message = null) }
        task = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableState.update { it.copy(message = e.message ?: "Something went wrong. Please retry.") }
            } finally { mutableState.update { it.copy(operation = null) } }
        }
    }

    class Factory(private val repository: ImageRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PureImageViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return PureImageViewModel(repository) as T
        }
    }
}

```

## app/src/main/res/values/styles.xml

```
<resources>
    <style name="Theme.PureImage" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>
</resources>

```

## build.gradle.kts

```
plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

```

## gradle/wrapper/gradle-wrapper.properties

```
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists

```

## gradle.properties

```
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official

```

## settings.gradle.kts

```
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PureImage"
include(":app")

```
