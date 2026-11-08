package org.claudevoice.prototype.audiofocus

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as FrameworkAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File
import java.util.Locale

/**
 * Plays the test sound as a media-playback service and holds media audio focus itself, in
 * both modes, so every focus change reaches this code and is logged. Media3's own focus
 * handling is off, because it pauses on a transient loss and that would hide what Android
 * asked for.
 */
class PlayerService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)
        session = MediaSession.Builder(this, player).build()
        watchTheRestOfTheAudioSystem()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(
                run = intent.getIntExtra(EXTRA_RUN, 1),
                mode = Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: Mode.SELF_DUCK.name),
                url = intent.getStringExtra(EXTRA_URL).orEmpty().trim(),
            )
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(run: Int, mode: Mode, url: String) {
        abandonFocus()
        EventLog.begin(this, run, mode)
        EventLog.add(
            "start",
            mapOf(
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
                "source" to (url.ifEmpty { "test tone" }),
                "outputs" to outputs(),
                "audioMode" to AudioNames.mode(audio.mode),
            ),
        )
        player.setMediaItem(MediaItem.fromUri(if (url.isEmpty()) Uri.fromFile(testTone()) else Uri.parse(url)))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = 1f
        player.prepare()

        val attrs = FrameworkAttributes.Builder()
            .setUsage(FrameworkAttributes.USAGE_MEDIA)
            .setContentType(FrameworkAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(FocusPolicy.willPauseWhenDucked(mode))
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ onFocusChange(mode, it) }, main)
            .build()
        focusRequest = request
        val granted = audio.requestAudioFocus(request)
        EventLog.add(
            "focus-request",
            mapOf(
                "result" to when (granted) {
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
                    AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
                    else -> "failed"
                },
                "willPauseWhenDucked" to FocusPolicy.willPauseWhenDucked(mode).toString(),
            ),
        )
        player.play()
    }

    private fun onFocusChange(mode: Mode, change: Int) {
        val reaction = FocusPolicy.react(mode, change)
        player.volume = reaction.volume
        EventLog.add(
            "focus",
            mapOf(
                "change" to FocusPolicy.name(change),
                "volume" to String.format(Locale.ROOT, "%.2f", reaction.volume),
                "playing" to player.isPlaying.toString(),
                "audioMode" to AudioNames.mode(audio.mode),
                "note" to reaction.note,
            ),
        )
    }

    private fun stop() {
        EventLog.add("stop")
        abandonFocus()
        player.stop()
        stopSelf()
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun testTone(): File {
        val seconds = 60
        val f = File(cacheDir, "test-tone-v1.wav")
        if (f.length() != ToneWav.size(seconds)) {
            // Written beside it and renamed, so a run killed mid-write leaves no broken file.
            val part = File(cacheDir, "test-tone-v1.wav.part")
            part.writeBytes(ToneWav.make(seconds))
            part.renameTo(f)
        }
        return f
    }

    private fun outputs(): String =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString(",") { AudioNames.device(it) }

    // What the other apps do with sound: who plays, who records, and the phone's audio mode.
    private fun watchTheRestOfTheAudioSystem() {
        audio.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                EventLog.add(
                    "players",
                    mapOf(
                        "active" to configs.joinToString(",") {
                            "${AudioNames.usage(it.audioAttributes.usage)}/${AudioNames.content(it.audioAttributes.contentType)}"
                        }.ifEmpty { "none" },
                    ),
                )
            }
        }, main)
        audio.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                EventLog.add(
                    "recorders",
                    mapOf(
                        "active" to configs.joinToString(",") {
                            "${AudioNames.source(it.clientAudioSource)}@${AudioNames.device(it.audioDevice)}"
                        }.ifEmpty { "none" },
                    ),
                )
            }
        }, main)
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                EventLog.add("devices-added", mapOf("devices" to added.joinToString(",") { AudioNames.device(it) }))
            }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                EventLog.add("devices-removed", mapOf("devices" to removed.joinToString(",") { AudioNames.device(it) }))
            }
        }, main)
        if (Build.VERSION.SDK_INT >= 31) {
            audio.addOnModeChangedListener(mainExecutor) { EventLog.add("audio-mode", mapOf("mode" to AudioNames.mode(it))) }
            audio.addOnCommunicationDeviceChangedListener(mainExecutor) {
                EventLog.add("communication-device", mapOf("device" to AudioNames.device(it)))
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            EventLog.add("player", mapOf("playing" to isPlaying.toString()))
        }
        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            EventLog.add("player-suppressed", mapOf("reason" to reason.toString()))
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            EventLog.add("player-play-when-ready", mapOf("value" to playWhenReady.toString(), "reason" to reason.toString()))
        }
        override fun onPlayerError(error: PlaybackException) {
            EventLog.add("player-error", mapOf("error" to error.errorCodeName))
        }
    }

    override fun onDestroy() {
        abandonFocus()
        session.release()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.claudevoice.prototype.audiofocus.START"
        const val ACTION_STOP = "org.claudevoice.prototype.audiofocus.STOP"
        const val EXTRA_RUN = "run"
        const val EXTRA_MODE = "mode"
        const val EXTRA_URL = "url"
    }
}
