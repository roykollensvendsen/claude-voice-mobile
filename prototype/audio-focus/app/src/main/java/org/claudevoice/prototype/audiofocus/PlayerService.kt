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
import android.media.audiofx.LoudnessEnhancer
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
 * Plays the test sound as a media-playback service. In the modes that take the sound it
 * holds audio focus itself, so every focus change reaches this code and is logged. Media3's own focus
 * handling is off, because it pauses on a transient loss and that would hide what Android
 * asked for.
 */
class PlayerService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var mode = Mode.SELF_DUCK
    private var playingUsages: Set<Int> = emptySet()
    private var othersRecording = false
    private var duck = FocusPolicy.DEFAULT_DUCK
    private var lastFocusChange: Int? = null
    private var soundType = SoundType.MEDIA
    /** What the player plays as now: the choice itself, or for "auto" media or a call. */
    private var playingAs = SoundType.MEDIA
    private var level = 1f
    private var enhancer: LoudnessEnhancer? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(attributesFor(SoundType.MEDIA), /* handleAudioFocus = */ false)
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
                mode = Mode.fromSaved(intent.getStringExtra(EXTRA_MODE)),
                url = intent.getStringExtra(EXTRA_URL).orEmpty().trim(),
                duck = FocusPolicy.duckLevel(intent.getIntExtra(EXTRA_DUCK_PERCENT, 40)),
                soundType = SoundType.fromSaved(intent.getStringExtra(EXTRA_SOUND_TYPE)),
            )
            ACTION_STOP -> stop()
            ACTION_SET_DUCK -> setDuck(FocusPolicy.duckLevel(intent.getIntExtra(EXTRA_DUCK_PERCENT, 40)))
            ACTION_SHOW_VOLUME -> showVolume()
        }
        return START_NOT_STICKY
    }

    private fun start(run: Int, mode: Mode, url: String, duck: Float, soundType: SoundType) {
        abandonFocus()
        this.mode = mode
        this.duck = duck
        this.soundType = soundType
        playingAs = soundType.playsAs(audio.mode)
        player.setAudioAttributes(attributesFor(playingAs), /* handleAudioFocus = */ false)
        lastFocusChange = null
        playingUsages = audio.activePlaybackConfigurations.map { it.audioAttributes.usage }.toSet()
        othersRecording = audio.activeRecordingConfigurations.isNotEmpty()
        EventLog.begin(this, run, mode)
        EventLog.add(
            "start",
            mapOf(
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
                "source" to (url.ifEmpty { "test tone" }),
                "outputs" to outputs(),
                "audioMode" to AudioNames.mode(audio.mode),
                "alreadyPlaying" to playingUsages.joinToString(",") { AudioNames.usage(it) }.ifEmpty { "none" },
                "alreadyRecording" to othersRecording.toString(),
                "duck" to String.format(Locale.ROOT, "%.2f", duck),
                "soundType" to soundType.label,
                "playsAs" to playingAs.label,
            ),
        )
        logVolumes("start")
        player.setMediaItem(MediaItem.fromUri(if (url.isEmpty()) Uri.fromFile(testTone()) else Uri.parse(url)))
        player.repeatMode = Player.REPEAT_MODE_ONE
        setLevel(1f)
        player.prepare()

        if (!FocusPolicy.requestsFocus(mode)) {
            EventLog.add("focus-request", mapOf("result" to "none; this mode never asks for focus"))
            followWhatPlays("start")
            player.play()
            return
        }
        val attrs = FrameworkAttributes.Builder()
            .setUsage(playingAs.usage)
            .setContentType(playingAs.contentType)
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
        lastFocusChange = change
        val reaction = FocusPolicy.react(mode, change, duck)
        setLevel(reaction.volume)
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

    /** In the watching mode: duck while an assistant or a call plays, full volume otherwise. */
    private fun followWhatPlays(why: String) {
        if (mode != Mode.WATCH) return
        val volume = FocusPolicy.watchVolume(playingUsages, audio.mode, othersRecording, duck, playingAs.usage)
        if (volume == level && why != "start") return
        setLevel(volume)
        EventLog.add(
            "watch",
            mapOf(
                "why" to why,
                "volume" to String.format(Locale.ROOT, "%.2f", volume),
                "audioMode" to AudioNames.mode(audio.mode),
            ),
        )
    }

    /** A new duck level from the slider, applied at once if the player is ducked now. */
    private fun setDuck(level: Float) {
        duck = level
        EventLog.add("duck-level", mapOf("duck" to String.format(Locale.ROOT, "%.2f", level)))
        if (mode == Mode.WATCH) {
            followWhatPlays("duck-level")
        } else {
            lastFocusChange?.let { setLevel(FocusPolicy.react(mode, it, duck).volume) }
        }
    }

    /** Sets a level: up to 1 is the player's volume, above 1 a boost on its audio session. */
    private fun setLevel(level: Float) {
        this.level = level
        val loudness = FocusPolicy.loudness(level)
        player.volume = loudness.volume
        val result = runCatching {
            val e = enhancer ?: LoudnessEnhancer(player.audioSessionId).also { enhancer = it }
            e.setTargetGain(loudness.boostMillibels)
            e.enabled = loudness.boostMillibels > 0
        }
        if (loudness.boostMillibels > 0 || result.isFailure) {
            EventLog.add(
                "boost",
                mapOf(
                    "millibels" to loudness.boostMillibels.toString(),
                    "result" to (result.exceptionOrNull()?.let { "failed: ${it.javaClass.simpleName}" } ?: "on"),
                ),
            )
        }
    }

    /** For "auto": plays as a call while the phone is in one, as media otherwise. */
    private fun switchSoundIfNeeded(audioMode: Int) {
        val next = soundType.playsAs(audioMode)
        if (next == playingAs) return
        EventLog.add("sound-type", mapOf("from" to playingAs.label, "to" to next.label))
        playingAs = next
        player.setAudioAttributes(attributesFor(next), /* handleAudioFocus = */ false)
    }

    private fun attributesFor(type: SoundType): AudioAttributes = AudioAttributes.Builder()
        .setUsage(type.usage)
        .setContentType(type.contentType)
        .build()

    /** Opens Android's own volume panel for the stream the book plays on, during a call too. */
    private fun showVolume() {
        audio.adjustStreamVolume(playingAs.stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        logVolumes("show-volume")
    }

    /** The phone's own volume steps for media, the call and accessibility, as numbers. */
    private fun logVolumes(why: String) {
        fun step(stream: Int) = "${audio.getStreamVolume(stream)}/${audio.getStreamMaxVolume(stream)}"
        EventLog.add(
            "volumes",
            mapOf(
                "why" to why,
                "media" to step(AudioManager.STREAM_MUSIC),
                "call" to step(AudioManager.STREAM_VOICE_CALL),
                "accessibility" to step(AudioManager.STREAM_ACCESSIBILITY),
                "audioMode" to AudioNames.mode(audio.mode),
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
                playingUsages = configs.map { it.audioAttributes.usage }.toSet()
                EventLog.add(
                    "players",
                    mapOf(
                        "active" to configs.joinToString(",") {
                            "${AudioNames.usage(it.audioAttributes.usage)}/${AudioNames.content(it.audioAttributes.contentType)}"
                        }.ifEmpty { "none" },
                    ),
                )
                followWhatPlays("players")
            }
        }, main)
        audio.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                othersRecording = configs.isNotEmpty()
                EventLog.add(
                    "recorders",
                    mapOf(
                        "active" to configs.joinToString(",") {
                            "${AudioNames.source(it.clientAudioSource)}@${AudioNames.device(it.audioDevice)}"
                        }.ifEmpty { "none" },
                    ),
                )
                followWhatPlays("recorders")
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
            audio.addOnModeChangedListener(mainExecutor) {
                EventLog.add("audio-mode", mapOf("mode" to AudioNames.mode(it)))
                logVolumes("audio-mode")
                switchSoundIfNeeded(it)
                followWhatPlays("audio-mode")
            }
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
        enhancer?.release()
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
        const val ACTION_SET_DUCK = "org.claudevoice.prototype.audiofocus.SET_DUCK"
        const val EXTRA_DUCK_PERCENT = "duckPercent"
        const val EXTRA_SOUND_TYPE = "soundType"
        const val ACTION_SHOW_VOLUME = "org.claudevoice.prototype.audiofocus.SHOW_VOLUME"
    }
}
