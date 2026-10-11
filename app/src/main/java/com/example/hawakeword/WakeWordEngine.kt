package com.example.hawakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * On-device openWakeWord pipeline (mic -> mel-spectrogram -> embedding -> classifiers).
 *
 * The mel and embedding models run once per 80 ms chunk and their features are shared by
 * every classifier, so listening for several words costs little more than one.
 * Models are loaded through [ModelCatalog], so both bundled and imported files work.
 *
 * Audio is fed to the mel model as raw 16-bit PCM values (not normalised to +/-1), matching
 * the Python openWakeWord implementation the models were trained against.
 */
class WakeWordEngine(
    private val context: Context,
    private val models: List<ModelSpec>,
    private val cooldownMs: Long,
    private val onDetection: (Detection) -> Unit,
    private val onError: (String) -> Unit,
    /** Called ~4 times a second with live mic level and scores, for the on-screen diagnostics. */
    private val onStats: (Stats) -> Unit = {},
) {
    /** [threshold] reports a detection; [sendThreshold] (>= threshold) marks it as worth sending. */
    data class ModelSpec(val name: String, val file: String, val threshold: Float, val sendThreshold: Float)

    /** [send] is false for detections above the detection threshold but below the send threshold. */
    data class Detection(val name: String, val score: Float, val timestamp: Long, val send: Boolean)

    /** [level] is the recent mic peak (0..1); [words] the latest and recent-peak score per word. */
    data class Stats(val level: Float, val words: List<WordStat>, val chunks: Int)

    data class WordStat(val name: String, val score: Float, val peak: Float, val threshold: Float)

    @Volatile private var stopRequested = false
    private var thread: Thread? = null

    fun start() {
        stopRequested = false
        thread = Thread({ run() }, "wakeword-engine").also { it.start() }
    }

    fun stop() {
        stopRequested = true
        thread?.join(3000)
        thread = null
    }

    private class Classifier(val spec: ModelSpec, val session: OrtSession, val frames: Int)

    @SuppressLint("MissingPermission")
    private fun run() {
        val env = OrtEnvironment.getEnvironment()
        val closeables = mutableListOf<AutoCloseable>()
        var record: AudioRecord? = null
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) error("Microphone permission not granted")

            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }.also { closeables += it }
            val mel = env.createSession(ModelCatalog.readBytes(context, ModelCatalog.MEL), opts)
                .also { closeables += it }
            val emb = env.createSession(ModelCatalog.readBytes(context, ModelCatalog.EMBEDDING), opts)
                .also { closeables += it }
            val classifiers = models.map { spec ->
                val s = env.createSession(ModelCatalog.readBytes(context, spec.file), opts)
                    .also { closeables += it }
                Classifier(spec, s, ModelCatalog.framesOf(s))
            }

            val pipeline = FeaturePipeline(env, mel, emb)
            // Prime the feature buffer with noise, like openWakeWord does, so the first
            // real chunks aren't classified against an empty history.
            repeat(WARMUP_CHUNKS) {
                pipeline.process(FloatArray(CHUNK) { Random.nextFloat() * 2000f - 1000f })
            }

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4)
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Could not open the microphone" }
            record.startRecording()

            val shorts = ShortArray(CHUNK)
            var filled = 0
            var chunks = 0
            // Separate cooldowns so a weak, unsent detection can't suppress a strong one right after.
            val lastSent = HashMap<String, Long>()
            val lastSeen = HashMap<String, Long>()
            var level = 0f
            val scores = FloatArray(classifiers.size)
            val peaks = FloatArray(classifiers.size)

            while (!stopRequested) {
                val n = record.read(shorts, filled, CHUNK - filled)
                check(n >= 0) { "Microphone read error ($n)" }
                filled += n
                if (filled < CHUNK) continue
                filled = 0

                var chunkPeak = 0
                for (v in shorts) chunkPeak = maxOf(chunkPeak, kotlin.math.abs(v.toInt()))
                level = maxOf(chunkPeak / 32768f, level * 0.85f)

                pipeline.process(FloatArray(CHUNK) { shorts[it].toFloat() })
                if (++chunks <= IGNORE_CHUNKS) continue

                val now = SystemClock.elapsedRealtime()
                for ((i, c) in classifiers.withIndex()) {
                    val score = classify(env, c, pipeline) ?: continue
                    scores[i] = score
                    peaks[i] = maxOf(score, peaks[i] * 0.97f)
                    if (score < c.spec.threshold) continue
                    val send = score >= c.spec.sendThreshold
                    val history = if (send) lastSent else lastSeen
                    val last = history[c.spec.name]
                    if (last != null && now - last < cooldownMs) continue
                    history[c.spec.name] = now
                    onDetection(Detection(c.spec.name, score, System.currentTimeMillis(), send))
                }

                if (chunks % 3 == 0) {
                    onStats(
                        Stats(
                            level = level,
                            words = classifiers.mapIndexed { i, c ->
                                WordStat(c.spec.name, scores[i], peaks[i], c.spec.threshold)
                            },
                            chunks = chunks,
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            if (!stopRequested) onError(e.message ?: e.javaClass.simpleName)
        } finally {
            record?.let {
                runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
                it.release()
            }
            closeables.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /** Runs one classifier on the latest features of [pipeline]; null until enough history exists. */
    private fun classify(env: OrtEnvironment, c: Classifier, pipeline: FeaturePipeline): Float? {
        val features = pipeline.lastFeatures(c.frames) ?: return null
        return OnnxTensor.createTensor(env, arrayOf(features)).use { t ->
            c.session.run(mapOf(c.session.inputNames.first() to t)).use { res ->
                @Suppress("UNCHECKED_CAST")
                (res[0].value as Array<FloatArray>)[0][0]
            }
        }
    }

    /** Streaming mel-spectrogram -> embedding features, shared by all classifiers. */
    private class FeaturePipeline(
        private val env: OrtEnvironment,
        private val mel: OrtSession,
        private val emb: OrtSession,
    ) {
        private var rawTail = FloatArray(0)
        private val melBuf = ArrayDeque<FloatArray>()
        private val features = ArrayDeque<FloatArray>()

        init {
            repeat(MEL_WINDOW) { melBuf.addLast(FloatArray(MEL_BINS) { 1f }) }
        }

        fun process(chunk: FloatArray) {
            // 480 samples of left context so the mel frames line up between chunks.
            val window = rawTail + chunk
            rawTail = window.copyOfRange(maxOf(0, window.size - 480), window.size)

            computeMel(window).forEach { melBuf.addLast(it) }
            while (melBuf.size > MEL_MAX) melBuf.removeFirst()

            val start = melBuf.size - MEL_WINDOW
            val input = Array(1) {
                Array(MEL_WINDOW) { r ->
                    val row = melBuf[start + r]
                    Array(MEL_BINS) { c -> floatArrayOf(row[c]) }
                }
            }
            features.addLast(embed(input))
            while (features.size > FEATURE_MAX) features.removeFirst()
        }

        /** The most recent [n] embedding frames, or null if not enough yet. */
        fun lastFeatures(n: Int): Array<FloatArray>? {
            if (features.size < n) return null
            val offset = features.size - n
            return Array(n) { features[offset + it] }
        }

        private fun computeMel(samples: FloatArray): Array<FloatArray> {
            val shape = longArrayOf(1, samples.size.toLong())
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), shape).use { t ->
                mel.run(mapOf(mel.inputNames.first() to t)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = res[0].value as Array<Array<Array<FloatArray>>>
                    val frames = out[0][0]
                    // Same transform openWakeWord applies to the raw mel output.
                    Array(frames.size) { i -> FloatArray(frames[i].size) { j -> frames[i][j] / 10f + 2f } }
                }
            }
        }

        private fun embed(input: Array<Array<Array<FloatArray>>>): FloatArray =
            OnnxTensor.createTensor(env, input).use { t ->
                emb.run(mapOf(emb.inputNames.first() to t)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = res[0].value as Array<Array<Array<FloatArray>>>
                    out[0][0][0].copyOf()
                }
            }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1280 // 80 ms
        private const val MEL_WINDOW = 76
        private const val MEL_BINS = 32
        private const val MEL_MAX = 970
        private const val FEATURE_MAX = 120
        private const val WARMUP_CHUNKS = 30
        private const val IGNORE_CHUNKS = 5
    }
}
