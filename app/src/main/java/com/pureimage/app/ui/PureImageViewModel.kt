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
