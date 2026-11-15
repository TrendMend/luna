package dev.luna.assistant.wakeword

import com.google.gson.JsonParser
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin port must give the scores the real openWakeWord gives. `golden.json` is made by running openWakeWord's own Python
 * (the one the listener uses) over `speech.wav` in 1280-sample chunks; `worker/` has nothing to do with it. The clip is
 * Windows speech saying "Hey Jarvis ... Alexa ... Hey Mycroft ... Ron? ... Rulon, are you there?", so the words really fire.
 * Regenerate both with listener/tools/make_wakeword_golden.py if the models change.
 */
class OpenWakeWordTest {
    private val assets = File("src/main/assets/wakeword")
    private val resources = File("src/test/resources/wakeword")
    private val ids = listOf("alexa", "hey_jarvis", "hey_mycroft", "hey_rhasspy", "ron", "rulon")

    private fun detector() = OpenWakeWord(
        File(assets, "melspectrogram.onnx").readBytes(),
        File(assets, "embedding_model.onnx").readBytes(),
        ids.associateWith { File(assets, "$it.onnx").readBytes() },
    )

    private fun pcm(): ShortArray {
        val stream = AudioSystem.getAudioInputStream(File(resources, "speech.wav"))
        assertEquals(16_000f, stream.format.sampleRate)
        val bytes = stream.readAllBytes()
        return ShortArray(bytes.size / 2) { ((bytes[it * 2 + 1].toInt() shl 8) or (bytes[it * 2].toInt() and 0xff)).toShort() }
    }

    private val golden = JsonParser.parseString(File(resources, "golden.json").readText()).asJsonObject

    private fun reference(id: String): List<Float> = golden.getAsJsonObject("scores").getAsJsonArray(id).map { it.asFloat }
    private fun referenceEmbeddings(): List<List<Float>> = golden.getAsJsonArray("last_embedding").map { row -> row.asJsonArray.map { it.asFloat } }

    @Test
    fun scores_and_embeddings_match_the_python_reference_frame_by_frame() {
        val samples = pcm()
        val skip = golden.get("warmup_ignore_frames").asInt          // Python starts from random noise, we start from zeros
        val expected = ids.associateWith { reference(it) }
        val embeddings = referenceEmbeddings()
        val frames = expected.getValue("alexa").size
        var worstScore = 0f
        var worstEmbedding = 0f
        detector().use { d ->
            for (i in 0 until frames) {
                val scores = d.process(samples.copyOfRange(i * OpenWakeWord.CHUNK, (i + 1) * OpenWakeWord.CHUNK))
                if (i < skip) continue
                for (id in ids) worstScore = maxOf(worstScore, abs(scores.getValue(id) - expected.getValue(id)[i]))
                val mine = d.lastEmbedding()
                for (k in mine.indices) worstEmbedding = maxOf(worstEmbedding, abs(mine[k] - embeddings[i][k]))
            }
        }
        println("worst score difference $worstScore, worst embedding difference $worstEmbedding over ${frames - skip} frames")
        assertTrue("scores differ by $worstScore", worstScore < 0.01f)
        assertTrue("embeddings differ by $worstEmbedding", worstEmbedding < 0.01f)
    }

    @Test
    fun every_word_in_the_clip_is_heard() {
        val samples = pcm()
        val peaks = HashMap<String, Float>()
        detector().use { d ->
            for (i in 0 until samples.size / OpenWakeWord.CHUNK) {
                val scores = d.process(samples.copyOfRange(i * OpenWakeWord.CHUNK, (i + 1) * OpenWakeWord.CHUNK))
                if (d.ready) scores.forEach { (id, s) -> peaks[id] = maxOf(peaks[id] ?: 0f, s) }
            }
        }
        // the listener's thresholds: 0.5, and 0.7 for "ron" and "rulon"
        for ((id, threshold) in mapOf("alexa" to 0.5f, "hey_jarvis" to 0.5f, "hey_mycroft" to 0.5f, "ron" to 0.7f, "rulon" to 0.7f)) {
            assertTrue("$id peaked at ${peaks[id]}", (peaks[id] ?: 0f) >= threshold)
        }
        assertTrue("hey_rhasspy fired by itself: ${peaks["hey_rhasspy"]}", (peaks["hey_rhasspy"] ?: 0f) < 0.5f)
    }

    @Test
    fun silence_scores_nothing_and_reset_starts_again() {
        detector().use { d ->
            val silence = ShortArray(OpenWakeWord.CHUNK)
            repeat(30) { d.process(silence) }
            assertTrue(d.ready)
            assertTrue(d.process(silence).values.all { it < 0.1f })
            d.reset()
            assertTrue(!d.ready)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_chunk_must_be_exactly_80_ms() {
        detector().use { it.process(ShortArray(1000)) }
    }
}
