package dev.pam.media

import android.content.Context
import java.io.File
import java.util.function.BooleanSupplier
import java.util.function.DoubleConsumer

/**
 * Stable JVM entry point used by other PAM Native plugins (notably
 * `pushinbr/pam-native-background-transfer`) to transcode inside their own
 * workers. Plugins are compiled as independent Android libraries, so callers
 * bind reflectively; the signature is part of this package's public contract
 * and is kept through R8 by `consumer-rules.pro`.
 */
object MediaTranscoding {
    const val CONTRACT_VERSION = 1

    /**
     * Blocks until [destination] holds the transcoded MP4.
     *
     * @param options JSON `{"preset":1-4,"maxBitrate":int,"fastStart":bool,"audio":bool}`
     * @return JSON `{"path","mimeType","width","height","durationMillis","bytes","bitrate","fastStart"}`
     */
    @JvmStatic
    fun transcode(
        context: Context,
        source: File,
        destination: File,
        options: String,
        cancelled: BooleanSupplier,
        progress: DoubleConsumer,
    ): String = VideoTranscoder(context.applicationContext)
        .transcode(source, destination, TranscodeOptions.parse(options), cancelled::getAsBoolean, progress::accept)
        .toJson()
        .toString()
}
