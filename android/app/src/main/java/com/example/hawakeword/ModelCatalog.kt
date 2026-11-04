package com.example.hawakeword

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Finds wake word models from two places:
 *  - bundled in app assets (`app/src/main/assets/`), and
 *  - imported at runtime into `filesDir/models/` (imported files shadow bundled ones).
 *
 * The two helper models (mel-spectrogram and embedding) live in the same places.
 */
object ModelCatalog {
    const val MEL = "melspectrogram.onnx"
    const val EMBEDDING = "embedding_model.onnx"
    private val HELPERS = setOf(MEL, EMBEDDING)
    private const val MAX_IMPORT_BYTES = 50L * 1024 * 1024

    private fun importDir(context: Context) = File(context.filesDir, "models").also { it.mkdirs() }

    private fun bundled(context: Context): List<String> =
        context.assets.list("")?.filter { it.endsWith(".onnx") }.orEmpty()

    private fun imported(context: Context): List<String> =
        importDir(context).listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.map { it.name }.orEmpty()

    /** File names of wake word classifiers (helpers excluded), e.g. `hey_jarvis_v0.1.onnx`. */
    fun available(context: Context): List<String> =
        (bundled(context) + imported(context)).filter { it !in HELPERS }.distinct().sorted()

    fun isImported(context: Context, file: String): Boolean = File(importDir(context), file).isFile

    fun hasHelpers(context: Context): Boolean {
        val all = bundled(context) + imported(context)
        return HELPERS.all { it in all }
    }

    /** `hey_jarvis_v0.1.onnx` -> `hey_jarvis` (this is what HA sees as `wake_word`). */
    fun wakeWordName(file: String): String =
        file.removeSuffix(".onnx").replace(Regex("_v\\d+(\\.\\d+)*$"), "")

    /** Imported file wins over a bundled one with the same name. */
    fun readBytes(context: Context, file: String): ByteArray {
        val local = File(importDir(context), file)
        return if (local.isFile) local.readBytes() else context.assets.open(file).use { it.readBytes() }
    }

    fun delete(context: Context, file: String): Boolean = File(importDir(context), file).delete()

    /**
     * Number of feature frames a classifier consumes (input shape [1, frames, 96]).
     * Throws if the model isn't an openWakeWord classifier.
     */
    fun framesOf(session: OrtSession): Int {
        val shape = (session.inputInfo.values.first().info as TensorInfo).shape
        require(shape.size == 3 && shape[2] == 96L) {
            "Not an openWakeWord classifier (expected input [1, N, 96], got ${shape.toList()})"
        }
        return shape[1].toInt().takeIf { it > 0 } ?: 16
    }

    /** Copies a user-picked file into app storage after validating it. Returns the stored file name. */
    fun importFrom(context: Context, uri: Uri): Result<String> = runCatching {
        val raw = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment.orEmpty()

        val name = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(name.endsWith(".onnx") && name.length > 5) { "Pick an .onnx file" }

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not read file")
        require(bytes.size <= MAX_IMPORT_BYTES) { "File is larger than 50 MB" }

        if (name !in HELPERS) {
            // Validate before storing so a wrong file can't break the listener.
            OrtEnvironment.getEnvironment().createSession(bytes).use { framesOf(it) }
        }
        File(importDir(context), name).writeBytes(bytes)
        // A word you add yourself is one you want, so it starts switched on.
        if (name !in HELPERS) Prefs(context).setModelEnabled(name, true)
        name
    }
}
