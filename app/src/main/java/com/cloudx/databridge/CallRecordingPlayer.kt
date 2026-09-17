package com.cloudx.databridge

import android.media.MediaPlayer

/**
 * Tiny single-track player for journey-timeline recordings. One playback at
 * a time across the whole app — starting a second URL stops the first, so
 * two timeline rows can never talk over each other. Call [release] when the
 * hosting dialog/fragment is destroyed.
 */
object CallRecordingPlayer {

    @Volatile var playingUrl: String? = null
        private set

    private var player: MediaPlayer? = null
    private var onState: ((playing: Boolean, url: String?) -> Unit)? = null

    fun setListener(listener: ((playing: Boolean, url: String?) -> Unit)?) {
        onState = listener
    }

    val isPlaying: Boolean get() = try { player?.isPlaying == true } catch (_: Exception) { false }

    /** Toggles [url]: same URL while playing → pause/stop; otherwise switch. */
    fun toggle(presignedUrl: String, onError: (String) -> Unit = {}) {
        if (isPlaying && playingUrl == presignedUrl) { stop(); return }
        stop()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(presignedUrl)
                setOnPreparedListener { it.start(); playingUrl = presignedUrl; emit() }
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> onError("Couldn't play this recording"); stop(); true }
                prepareAsync()
            }
            player = mp
        } catch (e: Exception) {
            onError("Couldn't play this recording")
            stop()
        }
    }

    fun stop() {
        playingUrl = null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        emit()
    }

    fun release() {
        onState = null
        stop()
    }

    private fun emit() {
        try { onState?.invoke(isPlaying, playingUrl) } catch (_: Exception) {}
    }
}
