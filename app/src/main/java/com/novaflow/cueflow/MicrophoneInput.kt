package com.novaflow.cueflow

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

internal enum class MicrophoneInput(val label: String) {
    AUTOMATIC("Automatic"),
    PHONE("Phone mic");

    fun preferredDevice(manager: AudioManager): AudioDeviceInfo? = when (this) {
        AUTOMATIC -> manager.usbMicrophone()
        PHONE -> manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }
}

internal fun AudioManager.usbMicrophone(): AudioDeviceInfo? =
    getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.isUsbMicrophone }

internal fun AudioManager.watchAudioInputs(onChanged: () -> Unit): AudioDeviceCallback =
    object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isSource }) onChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isSource }) onChanged()
        }
    }.also { registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }

private val AudioDeviceInfo.isUsbMicrophone: Boolean
    get() = isSource && when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
        else -> false
    }

internal fun AudioDeviceInfo.microphoneLabel(): String = when {
    isUsbMicrophone -> productName.toString().trim().takeIf { it.isNotEmpty() }?.let { "USB mic · $it" } ?: "USB mic"
    type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
    else -> productName.toString().trim().ifEmpty { "External mic" }
}
