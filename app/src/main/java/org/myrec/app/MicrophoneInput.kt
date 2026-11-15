package org.myrec.app

import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.net.URLEncoder

data class MicrophoneInput(val id: Int, val type: Int, val product: String, val address: String) {
    constructor(
        device: AudioDeviceInfo
    ) : this(device.id, device.type, device.productName.toString(), device.address)

    val usable: Boolean
        get() =
            type in
                listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_MIC,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_LINE_ANALOG,
                    AudioDeviceInfo.TYPE_LINE_DIGITAL,
                    AudioDeviceInfo.TYPE_AUX_LINE,
                )

    val legacyKey
        get() = "$type:$product"

    val key
        get() =
            "$type:${URLEncoder.encode(product, "UTF-8")}:${URLEncoder.encode(address.ifBlank { "route-$id" }, "UTF-8")}"

    fun gainKey(discovered: List<MicrophoneInput>): String =
        if (address.isBlank() && discovered.count { it.type==type && it.product==product }<=1)
            "$type:${URLEncoder.encode(product,"UTF-8")}:default"
        else key

    val label: String
        get() {
            if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
                return when (address.lowercase()) {
                    "bottom" -> "Phone · Bottom mic"
                    "back",
                    "rear" -> "Phone · Rear mic"
                    "front" -> "Phone · Front mic"
                    "top" -> "Phone · Top mic"
                    "" -> "Phone microphone"
                    else -> "Phone mic · $address"
                }
            val kind =
                when (type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                    else -> "Line input"
                }
            return if (product.isBlank()) kind else "$product · $kind"
        }

    companion object {
        fun devices(manager: AudioManager) =
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { MicrophoneInput(it).usable }

        fun labels(inputs: List<MicrophoneInput>): List<String> =
            inputs.map { input ->
                if (inputs.count { it.label == input.label } == 1) input.label
                else
                    input.label +
                        " · " +
                        if (
                            input.address.isNotBlank() &&
                                inputs.count {
                                    it.label == input.label && it.address == input.address
                                } == 1
                        )
                            input.address
                        else "Route ${input.id}"
            }
    }
}
