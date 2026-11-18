package org.claudevoice.prototype.micmute

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Holds everything a measurement needs while the Claude app is in front: the mute, the 50 ms
 * watch of Android's mute state and recording configurations, the media session that catches an
 * earbud tap, the stand-in recording, the word schedule and the tones. ADR-CVM-008.
 */
class MuteService : Service() {
    private lateinit var audio: AudioManager
    private val debounce = Debounce(windowMs = 700)
    private val watch = MuteWatch()
    private val owner = MuteOwner()
    // Mute (B), the owner's own switch, which no app can read: marked by hand on the screen.
    @Volatile private var switchMarkedOn = false
    private var session: MediaSession? = null
    private var silentTrack: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var standIn: Thread? = null
    @Volatile private var tapAtMs: Long? = null
    private val delays = mutableListOf<Long>()
    @Volatile private var schedule: Schedule? = null
    @Volatile private var readings = 0
    @Volatile private var passes = 0

    private val muteChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val muted = audio.isMicrophoneMute
            EventLog.add("mute-broadcast", mapOf("muted" to "$muted"))
            if (watch.observe(muted)) EventLog.add("LEAK", mapOf("seen" to "broadcast", "leaks" to "${watch.leaks}"))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Muted by either way under test: the app's own mute (A), or the owner's switch marked on (B). */
    private fun muted(): Boolean = Muting.expected(audio.isMicrophoneMute, switchMarkedOn)

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        EventLog.open(this)
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        registerReceiver(muteChanged, IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED), RECEIVER_NOT_EXPORTED)
        running = true
        thread(name = "watch", isDaemon = true) { watchLoop() }
        EventLog.add("service-start", mapOf("muted" to "${audio.isMicrophoneMute}"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TOGGLE -> toggle(source = intent.getStringExtra(SOURCE) ?: "phone")
            STAND_IN -> setStandIn(intent.getBooleanExtra(ON, false), intent.getIntExtra(AUDIO_SOURCE, 0), intent.getIntExtra(DEVICE, 0))
            SESSION -> setSession(intent.getBooleanExtra(ON, false), intent.getBooleanExtra(SILENT_TRACK, true))
            SWITCH_B -> markSwitch(intent.getBooleanExtra(ON, false))
            SCHEDULE -> startSchedule()
            TONE -> playTestTone(intent.getBooleanExtra(CALL_STREAM, true))
        }
        return START_NOT_STICKY
    }

    /** One toggle, from the screen, the notification or an earbud tap. */
    private fun toggle(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (!debounce.accept(now)) {
            EventLog.add("tap-ignored", mapOf("source" to source))
            return
        }
        setMuted(!audio.isMicrophoneMute, source, now)
    }

    private fun setMuted(muted: Boolean, source: String, at: Long = SystemClock.elapsedRealtime()) {
        if (muted) {
            tapAtMs = at
            readings = 0
            passes = 0
            // Mute first, then expect it: the watch must never see the moment before as a leak.
            audio.isMicrophoneMute = true
            owner.mutedByUs()
            watch.expect(true)
        } else {
            EventLog.add("m1b-summary", mapOf("readings" to "$readings", "pass" to "$passes", "verdict" to if (readings > 0 && passes == readings) "pass" else "fail"))
            // Stop expecting the mute first, so the unmute asked for is never counted as a leak.
            watch.expect(false)
            audio.isMicrophoneMute = false
            owner.unmutedByUs()
        }
        EventLog.add(if (muted) "mute" else "unmute", mapOf("source" to source, "now" to "${audio.isMicrophoneMute}"))
        playTone(if (muted) Tones.falling(RATE) else Tones.rising(RATE), callStream = true)
        refreshNotification()
    }

    /**
     * Mute (B): the tester flips the owner's microphone switch in Quick Settings, then marks it
     * here. From the mark on, M1a and M1b judge as for the app's own mute; the tap time is the mark.
     */
    private fun markSwitch(on: Boolean) {
        if (on) {
            tapAtMs = SystemClock.elapsedRealtime()
            readings = 0
            passes = 0
        } else {
            EventLog.add("m1b-summary", mapOf("readings" to "$readings", "pass" to "$passes", "verdict" to if (readings > 0 && passes == readings) "pass" else "fail"))
        }
        switchMarkedOn = on
        EventLog.add(if (on) "b-on" else "b-off", mapOf("app-muted" to "${audio.isMicrophoneMute}"))
    }

    /** M1b and M5: Android's mute state and every capture's silencing, every 50 ms. */
    private fun watchLoop() {
        var lastConfigs = ""
        var lastVerdict: String? = null
        while (running) {
            // M5 watches the app's own mute (A) only: a leak is Android reopening it, not the switch.
            if (watch.observe(audio.isMicrophoneMute)) EventLog.add("LEAK", mapOf("seen" to "poll", "leaks" to "${watch.leaks}"))
            val configs = audio.activeRecordingConfigurations
            val described = configs.joinToString(";") {
                "${it.clientAudioSource}/${it.audioDevice?.type ?: -1}/silenced=${it.isClientSilenced}"
            }
            if (described != lastConfigs) {
                EventLog.add("captures", mapOf("list" to described.ifEmpty { "none" }))
                lastConfigs = described
            }
            if (muted() && standIn == null) {
                val verdict = Reading.judge(configs.map { it.isClientSilenced })
                readings++
                if (verdict == "pass") passes++
                // Logged when the verdict changes, not at every 50 ms reading; the summary has the counts.
                if (verdict != lastVerdict) EventLog.add("m1b-reading", mapOf("verdict" to verdict))
                lastVerdict = verdict
            } else {
                lastVerdict = null
            }
            scheduleStep()
            SystemClock.sleep(50)
        }
    }

    /** M1a and M4: records with the Claude app's source and device, counting sound while muted. */
    private fun setStandIn(on: Boolean, source: Int, deviceType: Int) {
        if (!on) {
            standIn = null
            return
        }
        if (standIn != null) return
        val worker = thread(name = "stand-in", isDaemon = true) { record(source, deviceType) }
        standIn = worker
    }

    @Suppress("MissingPermission") // the screen asks for RECORD_AUDIO before it starts the stand-in
    private fun record(source: Int, deviceType: Int) {
        val size = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 4)
        val device = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == deviceType }
        if (device != null) recorder.setPreferredDevice(device)
        recorder.startRecording()
        EventLog.add("stand-in-start", mapOf("source" to "$source", "device" to "${device?.type ?: "default"}", "routed" to "${recorder.routedDevice?.type}"))
        val chunk = ShortArray(RATE / 100)
        val clock = SilenceClock(minRun = RATE / 100)
        var heardWhileMuted = 0L
        var samplesWhileMuted = 0L
        var silent = false
        var wasMuted = false
        var heardWhileOpen = 0L
        var samplesWhileOpen = 0L
        var lastTap: Long? = null
        while (standIn === Thread.currentThread()) {
            val read = recorder.read(chunk, 0, chunk.size)
            if (read <= 0) continue
            val endedAt = SystemClock.elapsedRealtime()
            val tap = tapAtMs
            if (tap != lastTap) {
                clock.reset()
                lastTap = tap
            }
            if (muted()) {
                wasMuted = true
                if (samplesWhileOpen > 0) {
                    // Shows the stand-in heard the tone while open, so a pass could have been a fail.
                    EventLog.add("m1a-open-interval", mapOf("samples" to "$samplesWhileOpen", "nonzero" to "$heardWhileOpen"))
                    heardWhileOpen = 0
                    samplesWhileOpen = 0
                }
                // Until silence begins is the mute's delay, M4's measure; from then on, any sound fails M1a.
                if (silent) {
                    heardWhileMuted += Silence.nonZero(chunk.copyOf(read))
                    samplesWhileMuted += read
                }
                val start = clock.feed(chunk.copyOf(read))
                if (start != null) silent = true
                if (start != null && tap != null) {
                    // The read ended at endedAt; the silent sample was this many samples before it.
                    val fed = clock.seen
                    val silentAt = endedAt - (fed - start) * 1000 / RATE
                    val delay = silentAt - tap
                    delays += delay
                    EventLog.add("m4-delay", mapOf("ms" to "$delay", "p95" to "${Percentile.of(delays, 95)}", "count" to "${delays.size}"))
                }
            } else {
                if (wasMuted) {
                    wasMuted = false
                    val verdict = if (silent && heardWhileMuted == 0L) "pass" else "fail"
                    EventLog.add("m1a-muted-interval", mapOf("silence-began" to "$silent", "samples" to "$samplesWhileMuted", "nonzero" to "$heardWhileMuted", "verdict" to verdict))
                    heardWhileMuted = 0
                    samplesWhileMuted = 0
                }
                silent = false
                heardWhileOpen += Silence.nonZero(chunk.copyOf(read))
                samplesWhileOpen += read
            }
        }
        recorder.stop()
        recorder.release()
        EventLog.add("stand-in-stop")
    }

    /** M2: a media session, kept the media-button target by a silent track, so a tap reaches it. */
    private fun setSession(on: Boolean, withSilentTrack: Boolean = true) {
        if (!on) {
            session?.release()
            session = null
            silentTrack?.stop()
            silentTrack?.release()
            silentTrack = null
            EventLog.add("session-off")
            return
        }
        if (session != null) return
        if (withSilentTrack) startSilentTrack()
        session = MediaSession(this, "mic-mute").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = earbud("play")
                override fun onPause() = earbud("pause")
            })
            setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE).setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true
        }
        // A pass with the silent track is a pass for that trick only; M2 is run once without it too.
        EventLog.add("session-on", mapOf("silent-track" to "$withSilentTrack"))
    }

    private fun startSilentTrack() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(RATE * 2)
            .build()
        track.write(ShortArray(RATE), 0, RATE)
        track.setLoopPoints(0, RATE, -1)
        track.play()
        silentTrack = track
    }

    private fun earbud(kind: String) {
        EventLog.add("earbud-tap", mapOf("kind" to kind))
        toggle(source = "earbud")
    }

    /** M1c: mutes the slots the schedule chose, from the next whole minute. */
    private fun startSchedule() {
        val next = Schedule.random(slots = 12, random = Random(System.nanoTime()))
        val startWall = Schedule.nextMinute(System.currentTimeMillis())
        scheduleStartWall = startWall
        schedule = next
        EventLog.add("m1c-schedule", mapOf("pattern" to next.pattern, "starts" to java.time.Instant.ofEpochMilli(startWall).toString(), "slot-ms" to "${next.slotMs}"))
    }

    @Volatile private var scheduleStartWall = 0L
    private var scheduledMuted: Boolean? = null

    private fun scheduleStep() {
        val plan = schedule ?: return
        val elapsed = System.currentTimeMillis() - scheduleStartWall
        if (elapsed < 0) return
        if (elapsed > plan.slotMs * plan.muted.size) {
            if (audio.isMicrophoneMute) setMuted(false, "schedule")
            schedule = null
            scheduledMuted = null
            EventLog.add("m1c-schedule-done")
            return
        }
        val want = plan.mutedAt(elapsed)
        if (want != scheduledMuted) {
            scheduledMuted = want
            if (want != audio.isMicrophoneMute) setMuted(want, "schedule")
        }
    }

    /** M6: a random one of the two tones; the owner answers on the screen which one was heard. */
    private fun playTestTone(callStream: Boolean) {
        val falling = Random.nextBoolean()
        EventLog.add("m6-tone", mapOf("played" to if (falling) "mute" else "unmute", "stream" to if (callStream) "call" else "media"))
        playTone(if (falling) Tones.falling(RATE) else Tones.rising(RATE), callStream)
    }

    private fun playTone(samples: ShortArray, callStream: Boolean) {
        val usage = if (callStream) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
        thread(isDaemon = true) {
            SystemClock.sleep(samples.size * 1000L / RATE + 200)
            track.release()
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Mic-mute test", NotificationManager.IMPORTANCE_LOW))
        val toggle = PendingIntent.getService(
            this, 0, Intent(this, MuteService::class.java).setAction(TOGGLE).putExtra(SOURCE, "notification"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val muted = runCatching { audio.isMicrophoneMute }.getOrDefault(false)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (muted) "Microphone muted" else "Microphone open")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (muted) "Unmute" else "Mute", toggle).build())
            .setOngoing(true)
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(1, notification())
    }

    /** Lifts a mute this app set, so stopping the test never leaves the owner's phone muted. */
    private fun restoreMute(why: String) {
        if (owner.restoreOnStop(audio.isMicrophoneMute)) {
            watch.expect(false)
            audio.isMicrophoneMute = false
            owner.unmutedByUs()
            EventLog.add("mute-restored", mapOf("on" to why, "now" to "${audio.isMicrophoneMute}"))
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        restoreMute("task-removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        standIn = null
        restoreMute("service-stop")
        setSession(false)
        unregisterReceiver(muteChanged)
        EventLog.add("service-stop", mapOf("muted" to "${audio.isMicrophoneMute}", "leaks" to "${watch.leaks}"))
        super.onDestroy()
    }

    companion object {
        const val RATE = 48_000
        const val TOGGLE = "toggle"
        const val STAND_IN = "stand-in"
        const val SESSION = "session"
        const val SCHEDULE = "schedule"
        const val TONE = "tone"
        const val ON = "on"
        const val SOURCE = "source"
        const val AUDIO_SOURCE = "audio-source"
        const val DEVICE = "device"
        const val CALL_STREAM = "call-stream"
        const val SILENT_TRACK = "silent-track"
        const val SWITCH_B = "switch-b"
        private const val CHANNEL = "mic-mute"

        val SOURCES = listOf(
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
        val DEVICES = listOf(
            "built-in" to AudioDeviceInfo.TYPE_BUILTIN_MIC,
            "earbuds SCO" to AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            "earbuds LE Audio" to AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
