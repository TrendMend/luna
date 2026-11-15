package dev.luna.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import kotlin.random.Random

/**
 * The quick "Hi!" that says Luna is listening. The clips are recorded once (Luna's own voice) and carried in the app, so the cue plays the moment
 * Luna opens: it does not wait for the connection, the agent or the model, which is most of a second at best.
 */
object GreetingCue {
    val clips: List<Int> = listOf(R.raw.cue_hi, R.raw.cue_hello, R.raw.cue_hey, R.raw.cue_hi_there, R.raw.cue_yes, R.raw.cue_listening)

    /** A different one each time: never the one before it. */
    fun pick(random: Random = Random, last: Int? = null): Int {
        val choices = if (last != null && clips.size > 1) clips - last else clips
        return choices[random.nextInt(choices.size)]
    }

    private var last: Int? = null

    /** Plays one clip as assistant speech and lets go of the player afterwards. Failing to play only means no cue. */
    fun play(context: Context) {
        val clip = pick(last = last).also { last = it }
        runCatching {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val player = MediaPlayer.create(context.applicationContext, clip, attrs, AudioManager.AUDIO_SESSION_ID_GENERATE) ?: return
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { p, _, _ -> p.release(); true }
            player.start()
        }
    }
}
