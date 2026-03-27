package com.filecleaner.app.ui.viewer.strategy

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.bumptech.glide.Glide
import com.filecleaner.app.R
import java.io.File

/**
 * Encapsulates audio playback: MediaPlayer lifecycle, seek bar updates,
 * album art extraction, play/pause toggle.
 * Caller must invoke [pause] in onPause and [destroy] in onDestroyView.
 */
class AudioPlayerDelegate(
    private val audioContainer: View,
    private val ivAudioArt: ImageView,
    private val tvAudioTitle: TextView,
    private val btnAudioPlay: ImageButton,
    private val seekAudio: SeekBar,
    private val tvAudioCurrent: TextView,
    private val tvAudioDuration: TextView,
    private val handler: Handler,
    private val strPlayAudio: String,
    private val strPauseAudio: String,
    private val strAudioError: String
) {

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    fun show(file: File, fragment: androidx.fragment.app.Fragment) {
        audioContainer.visibility = View.VISIBLE

        // Extract album art & title
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val art = retriever.embeddedPicture
            if (art != null) {
                Glide.with(fragment).load(art)
                    .placeholder(R.drawable.ic_audio)
                    .error(R.drawable.ic_audio)
                    .into(ivAudioArt)
            }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            tvAudioTitle.text = title ?: file.nameWithoutExtension
        } catch (_: Exception) {
            tvAudioTitle.text = file.nameWithoutExtension
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
            val mp = mediaPlayer!!
            seekAudio.max = mp.duration
            tvAudioDuration.text = formatTime(mp.duration)
            tvAudioCurrent.text = formatTime(0)

            btnAudioPlay.contentDescription = strPlayAudio
            btnAudioPlay.setOnClickListener {
                if (isPlaying) {
                    mp.pause()
                    isPlaying = false
                    btnAudioPlay.setImageResource(android.R.drawable.ic_media_play)
                    btnAudioPlay.contentDescription = strPlayAudio
                } else {
                    mp.start()
                    isPlaying = true
                    btnAudioPlay.setImageResource(android.R.drawable.ic_media_pause)
                    btnAudioPlay.contentDescription = strPauseAudio
                    updateSeekBar()
                }
            }

            seekAudio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        mp.seekTo(progress)
                        tvAudioCurrent.text = formatTime(progress)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            mp.setOnCompletionListener {
                isPlaying = false
                btnAudioPlay.setImageResource(android.R.drawable.ic_media_play)
                btnAudioPlay.contentDescription = strPlayAudio
                seekAudio.progress = 0
                tvAudioCurrent.text = formatTime(0)
            }
        } catch (_: Exception) {
            tvAudioTitle.text = strAudioError
        }
    }

    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying = false
                btnAudioPlay.setImageResource(android.R.drawable.ic_media_play)
            }
        }
    }

    fun destroy() {
        mediaPlayer?.setOnCompletionListener(null)
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
    }

    private fun updateSeekBar() {
        val mp = mediaPlayer ?: return
        if (isPlaying && mp.isPlaying) {
            seekAudio.progress = mp.currentPosition
            tvAudioCurrent.text = formatTime(mp.currentPosition)
            handler.postDelayed({ updateSeekBar() }, 500)
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
