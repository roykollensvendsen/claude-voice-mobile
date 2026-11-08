package org.claudevoice.prototype.audiofocus

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder

/** Readable names for the Android numbers the log records. */
object AudioNames {
    fun usage(u: Int): String = when (u) {
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "VOICE_SIGNALLING"
        AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "ACCESSIBILITY"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAVIGATION"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "SONIFICATION"
        AudioAttributes.USAGE_NOTIFICATION -> "NOTIFICATION"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "RINGTONE"
        AudioAttributes.USAGE_ALARM -> "ALARM"
        AudioAttributes.USAGE_GAME -> "GAME"
        AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
        else -> "usage$u"
    }

    fun content(c: Int): String = when (c) {
        AudioAttributes.CONTENT_TYPE_MUSIC -> "music"
        AudioAttributes.CONTENT_TYPE_SPEECH -> "speech"
        AudioAttributes.CONTENT_TYPE_MOVIE -> "movie"
        AudioAttributes.CONTENT_TYPE_SONIFICATION -> "sonification"
        else -> "content$c"
    }

    fun source(s: Int): String = when (s) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        else -> "source$s"
    }

    fun mode(m: Int): String = when (m) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        else -> "mode$m"
    }

    fun device(d: AudioDeviceInfo?): String = when (d?.type) {
        null -> "none"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone-mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb-device"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-sco"
        26 -> "ble-headset" // TYPE_BLE_HEADSET, API 31
        27 -> "ble-speaker" // TYPE_BLE_SPEAKER, API 31
        else -> "type${d.type}"
    }
}
