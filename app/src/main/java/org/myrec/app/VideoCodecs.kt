package org.myrec.app

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat

object VideoCodecs {
    fun decoder(format: MediaFormat): MediaCodec {
        val query = MediaFormat.createVideoFormat(format.getString(MediaFormat.KEY_MIME)!!,
            format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
        if (format.containsKey(MediaFormat.KEY_PROFILE)) query.setInteger(MediaFormat.KEY_PROFILE, format.getInteger(MediaFormat.KEY_PROFILE))
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(query)
            ?: error("This device cannot decode this source format and size")
        return MediaCodec.createByCodecName(name)
    }
}
