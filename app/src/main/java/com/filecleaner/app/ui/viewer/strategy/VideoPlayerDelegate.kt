package com.filecleaner.app.ui.viewer.strategy

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Handler
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.io.File

/**
 * Encapsulates video playback: VideoView lifecycle, seek bar updates,
 * play/pause/speed controls, play overlay.
 * Caller must invoke [pause] in onPause and [destroy] in onDestroyView.
 */
class VideoPlayerDelegate(
    private val videoContainer: View,
    private val videoView: VideoView,
    private val playOverlay: ImageView,
    private val videoControls: View,
    private val btnVideoPlay: ImageButton,
    private val seekVideo: SeekBar,
    private val tvVideoCurrent: TextView,
    private val tvVideoDuration: TextView,
    private val btnVideoSpeed: TextView?,
    private val handler: Handler,
    private val strPlayVideo: String,
    private val strPauseVideo: String
) {

    private var isPlaying = false
    private var isInitialized = false
    private var mediaPlayer: MediaPlayer? = null

    /** Show the video. [onUnsupported] is called if playback fails. */
    fun show(file: File, onUnsupported: () -> Unit) {
        videoContainer.visibility = View.VISIBLE
        videoView.setVideoURI(Uri.fromFile(file))

        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            isInitialized = true
            seekVideo.max = mp.duration
            tvVideoDuration.text = formatTime(mp.duration)
            tvVideoCurrent.text = formatTime(0)

            mp.setOnCompletionListener {
                isPlaying = false
                btnVideoPlay.setImageResource(android.R.drawable.ic_media_play)
                playOverlay.visibility = View.VISIBLE
                videoControls.visibility = View.GONE
                seekVideo.progress = 0
                tvVideoCurrent.text = formatTime(0)
            }
        }

        videoView.setOnErrorListener { _, _, _ ->
            videoContainer.visibility = View.GONE
            onUnsupported()
            true
        }

        playOverlay.setOnClickListener {
            playOverlay.visibility = View.GONE
            videoControls.visibility = View.VISIBLE
            videoView.start()
            isPlaying = true
            btnVideoPlay.setImageResource(android.R.drawable.ic_media_pause)
            updateSeekBar()
        }

        videoView.setOnClickListener {
            if (isInitialized) {
                videoControls.visibility =
                    if (videoControls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        btnVideoPlay.contentDescription = strPlayVideo
        btnVideoPlay.setOnClickListener {
            if (isPlaying) {
                videoView.pause()
                isPlaying = false
                btnVideoPlay.setImageResource(android.R.drawable.ic_media_play)
                btnVideoPlay.contentDescription = strPlayVideo
            } else {
                videoView.start()
                isPlaying = true
                btnVideoPlay.setImageResource(android.R.drawable.ic_media_pause)
                btnVideoPlay.contentDescription = strPauseVideo
                updateSeekBar()
            }
        }

        seekVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView.seekTo(progress)
                    tvVideoCurrent.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Speed control
        val speeds = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
        val speedLabels = arrayOf("0.5x", "1x", "1.5x", "2x")
        var speedIndex = 1
        btnVideoSpeed?.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            btnVideoSpeed.text = speedLabels[speedIndex]
            try {
                mediaPlayer?.playbackParams = PlaybackParams().setSpeed(speeds[speedIndex])
            } catch (_: Exception) { }
        }
    }

    fun pause() {
        if (isPlaying) {
            videoView.pause()
            isPlaying = false
            btnVideoPlay.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    fun destroy() {
        videoView.setOnPreparedListener(null)
        videoView.setOnErrorListener(null)
        videoView.setOnCompletionListener(null)
        videoView.stopPlayback()
        isPlaying = false
        isInitialized = false
    }

    private fun updateSeekBar() {
        if (isPlaying && isInitialized) {
            try {
                seekVideo.progress = videoView.currentPosition
                tvVideoCurrent.text = formatTime(videoView.currentPosition)
            } catch (_: Exception) { }
            handler.postDelayed({ updateSeekBar() }, 500)
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
