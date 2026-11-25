package org.myrec.app

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat

object VideoCodecs {
    class UnsupportedFormat(format: MediaFormat) : IllegalStateException(
        "No decoder supports ${format.getString(MediaFormat.KEY_MIME)} at " +
            "${format.getInteger(MediaFormat.KEY_WIDTH)}×${format.getInteger(MediaFormat.KEY_HEIGHT)}" +
            if (format.containsKey(MediaFormat.KEY_PROFILE)) " (profile ${format.getInteger(MediaFormat.KEY_PROFILE)})" else ""
    )

    fun decoder(format: MediaFormat, softwareOnly: Boolean = false): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val query = MediaFormat.createVideoFormat(mime,
            format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
        if (format.containsKey(MediaFormat.KEY_PROFILE)) query.setInteger(MediaFormat.KEY_PROFILE, format.getInteger(MediaFormat.KEY_PROFILE))
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = if (!softwareOnly) codecs.findDecoderForFormat(query) else codecs.codecInfos.firstOrNull { info ->
            !info.isEncoder && mime in info.supportedTypes &&
                (if (android.os.Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else
                    info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")) &&
                info.getCapabilitiesForType(mime).isFormatSupported(query)
        }?.name
        return MediaCodec.createByCodecName(name ?: throw UnsupportedFormat(query))
    }
}
