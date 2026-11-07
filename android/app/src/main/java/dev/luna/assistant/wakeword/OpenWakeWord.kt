package dev.luna.assistant.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * openWakeWord's streaming pipeline (https://github.com/dscripka/openWakeWord, `openwakeword/utils.py` and `model.py`), for the same
 * `.onnx` models the NUC's listener uses, so `ron.onnx` and `rulon.onnx` work here unchanged.
 *
 * Audio is 16 kHz mono, fed in chunks of exactly [CHUNK] samples (80 ms). Per chunk:
 *  1. the newest chunk plus 480 samples of context go through the melspectrogram model, scaled `x / 10 + 2`;
 *  2. the newest 76 spectrogram frames go through the embedding model: one 96-number embedding;
 *  3. each wake word model scores the newest [EMBEDDINGS] embeddings (0..1).
 * The mel and embedding stages are shared, so each extra wake word costs only its own small model.
 *
 * Differences from the Python reference, on purpose: the feature history starts as zeros rather than the embedding of random noise
 * (it is flushed after [EMBEDDINGS] chunks either way), and the model names are the caller's ids. As in Python the first
 * [SILENT_FRAMES] scores are 0. Scores are only trustworthy once [ready] (the history holds real audio only).
 *
 * Not thread safe: call [process] from one thread. The models are created from bytes, so they can come from assets or from files.
 */
class OpenWakeWord(
    melspectrogram: ByteArray,
    embedding: ByteArray,
    wakeWords: Map<String, ByteArray>,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : AutoCloseable {
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)     // one thread each: this runs all day on a phone
        setInterOpNumThreads(1)
    }
    private val mel = env.createSession(melspectrogram, options)
    private val embed = env.createSession(embedding, options)
    private val words: Map<String, OrtSession> = wakeWords.mapValues { env.createSession(it.value, options) }
    private val melInput = mel.inputNames.first()
    private val embedInput = embed.inputNames.first()
    private val wordInputs = words.mapValues { it.value.inputNames.first() }   // the names differ between models

    private var tail = FloatArray(0)                                  // the newest raw samples (at most CHUNK + CONTEXT)
    private val melFrames = ArrayDeque<FloatArray>()                  // spectrogram frames, 32 numbers each
    private val embeddings = ArrayDeque<FloatArray>()                 // 96 numbers each
    private var frames = 0

    val ids: Set<String> get() = words.keys

    /** True once the feature history holds nothing but real audio (about 1.9 s after the start or a [reset]). */
    val ready: Boolean get() = frames >= READY_FRAMES

    init {
        reset()
    }

    /** Forget all audio (after a conversation, so the end of Luna's own voice is not heard as a wake word). */
    fun reset() {
        tail = FloatArray(0)
        melFrames.clear()
        repeat(MEL_WINDOW) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        embeddings.clear()
        repeat(EMBEDDINGS) { embeddings.addLast(FloatArray(EMBEDDING_SIZE)) }
        frames = 0
    }

    /** Feed one chunk of exactly [CHUNK] 16-bit samples; returns the score of every wake word for it. */
    fun process(chunk: ShortArray): Map<String, Float> {
        require(chunk.size == CHUNK) { "expected $CHUNK samples, got ${chunk.size}" }

        val fresh = FloatArray(CHUNK) { chunk[it].toFloat() }
        tail = (tail + fresh).let { if (it.size > CHUNK + CONTEXT) it.copyOfRange(it.size - CHUNK - CONTEXT, it.size) else it }

        // 1. melspectrogram of the newest samples
        OnnxTensor.createTensor(env, FloatBuffer.wrap(tail), longArrayOf(1, tail.size.toLong())).use { input ->
            mel.run(mapOf(melInput to input)).use { out ->
                val buffer = (out[0] as OnnxTensor).floatBuffer
                val flat = FloatArray(buffer.remaining())
                buffer.get(flat)
                for (row in 0 until flat.size / MEL_BINS) {
                    melFrames.addLast(FloatArray(MEL_BINS) { flat[row * MEL_BINS + it] / 10f + 2f })
                }
            }
        }
        while (melFrames.size > MEL_WINDOW) melFrames.removeFirst()

        // 2. embedding of the newest 76 spectrogram frames
        val window = FloatArray(MEL_WINDOW * MEL_BINS)
        melFrames.forEachIndexed { i, row -> row.copyInto(window, i * MEL_BINS) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)).use { input ->
            embed.run(mapOf(embedInput to input)).use { out ->
                val vector = FloatArray(EMBEDDING_SIZE)
                (out[0] as OnnxTensor).floatBuffer.get(vector)
                embeddings.addLast(vector)
            }
        }
        while (embeddings.size > EMBEDDINGS) embeddings.removeFirst()

        // 3. every wake word scores the newest 16 embeddings
        val features = FloatArray(EMBEDDINGS * EMBEDDING_SIZE)
        embeddings.forEachIndexed { i, row -> row.copyInto(features, i * EMBEDDING_SIZE) }
        val scores = HashMap<String, Float>(words.size)
        for ((id, session) in words) {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, EMBEDDINGS.toLong(), EMBEDDING_SIZE.toLong())).use { input ->
                session.run(mapOf(wordInputs.getValue(id) to input)).use { out ->
                    val score = (out[0] as OnnxTensor).floatBuffer.get(0)
                    scores[id] = if (frames < SILENT_FRAMES) 0f else score
                }
            }
        }
        frames++
        return scores
    }

    /** The newest embedding (96 numbers), for tests against the Python reference. */
    fun lastEmbedding(): FloatArray = embeddings.last().copyOf()

    override fun close() {
        words.values.forEach { it.close() }
        embed.close()
        mel.close()
        options.close()
    }

    companion object {
        const val CHUNK = 1280              // 80 ms at 16 kHz: what the models expect per call
        const val RATE = 16_000
        private const val CONTEXT = 160 * 3  // samples before the chunk that the melspectrogram needs
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76    // spectrogram frames per embedding
        private const val EMBEDDINGS = 16    // embeddings per wake word score
        private const val EMBEDDING_SIZE = 96
        private const val SILENT_FRAMES = 5  // openWakeWord reports 0 for its first five chunks
        private const val READY_FRAMES = EMBEDDINGS + 8
    }
}
