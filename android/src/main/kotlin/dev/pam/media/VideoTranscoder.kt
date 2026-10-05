package dev.pam.media

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Options shared by `Media::transcode()` and the background-transfer bridge. */
internal data class TranscodeOptions(val preset: Int, val maxBitrate: Int, val fastStart: Boolean, val audio: Boolean) {
    companion object {
        fun parse(json: String): TranscodeOptions {
            val root = JSONObject(json)
            val preset = root.optInt("preset", 4)
            require(preset in 1..4) { "Unknown video preset" }
            return TranscodeOptions(
                preset = preset,
                maxBitrate = root.optInt("maxBitrate", 0).coerceIn(0, 50_000_000),
                fastStart = root.optBoolean("fastStart", true),
                audio = root.optBoolean("audio", true),
            )
        }
    }
}

internal data class TranscodeOutput(
    val file: File,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val bitrate: Int,
    val fastStart: Boolean,
) {
    fun toJson(path: String = file.absolutePath): JSONObject = JSONObject()
        .put("path", path).put("mimeType", "video/mp4").put("width", width).put("height", height)
        .put("durationMillis", durationMillis).put("bytes", file.length()).put("bitrate", bitrate).put("fastStart", fastStart)
}

/**
 * Re-encodes video into a progressive H.264 Main@4.1 / AAC-LC MP4 with
 * display-correct orientation, bounded dimensions, 2 s keyframes and
 * optional fast start. Runs Media3 Transformer on a private looper; blocks
 * the calling (background) thread until the export finishes.
 */
@OptIn(UnstableApi::class)
internal class VideoTranscoder(private val context: Context) {
    data class Probe(
        val width: Int,
        val height: Int,
        val durationMillis: Long,
        val hasAudio: Boolean,
        val audioChannels: Int?,
        val audioSampleRate: Int?,
    )

    data class Target(val width: Int, val height: Int, val bitrate: Int, val audioBitrate: Int)

    fun probe(source: File): Probe {
        val retriever = MediaMetadataRetriever()
        var width: Int
        var height: Int
        var rotation: Int
        var duration: Long
        try {
            retriever.setDataSource(source.path)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } finally {
            runCatching { retriever.release() }
        }
        require(width > 0 && height > 0) { "The source has no readable video track" }
        if (rotation % 180 != 0) width = height.also { height = width }
        var hasAudio = false
        var channels: Int? = null
        var sampleRate: Int? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    hasAudio = true
                    channels = format.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }?.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    sampleRate = format.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }?.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    break
                }
            }
        } catch (_: Exception) {
            // Audio metadata is optional; the export still runs.
        } finally {
            runCatching { extractor.release() }
        }
        return Probe(width, height, duration, hasAudio, channels, sampleRate)
    }

    fun target(probe: Probe, options: TranscodeOptions): Target {
        val preset = when (options.preset) {
            4 -> if (probe.durationMillis > LONG_VIDEO_MS) 2 else 3
            else -> options.preset
        }
        val (maxLong, maxShort, cap, audio) = when (preset) {
            1 -> listOf(854, 480, 800_000, 96_000)
            2 -> listOf(1280, 720, 1_500_000, 128_000)
            else -> listOf(1920, 1080, 2_500_000, 128_000)
        }
        val longest = maxOf(probe.width, probe.height)
        val shortest = minOf(probe.width, probe.height)
        val scale = minOf(1.0, maxLong.toDouble() / longest, maxShort.toDouble() / shortest)
        val width = even((probe.width * scale).toInt())
        val height = even((probe.height * scale).toInt())
        val tier = when {
            minOf(width, height) >= 1080 -> 2_500_000
            minOf(width, height) >= 720 -> 1_500_000
            else -> 1_000_000
        }
        var bitrate = minOf(tier, cap)
        if (options.maxBitrate > 0) bitrate = minOf(bitrate, options.maxBitrate)
        return Target(width, height, bitrate, audio)
    }

    fun transcode(
        source: File,
        destination: File,
        options: TranscodeOptions,
        cancelled: () -> Boolean,
        progress: (Double) -> Unit,
    ): TranscodeOutput {
        require(source.isFile) { "Video source does not exist" }
        require(source.canonicalPath != destination.canonicalPath) { "Transcode destination must differ from the source" }
        val probe = probe(source)
        val target = target(probe, options)
        val directory = destination.absoluteFile.parentFile ?: error("Invalid destination")
        directory.mkdirs()
        val raw = File(directory, ".${destination.name}.raw.mp4")
        val staged = File(directory, ".${destination.name}.part")
        raw.delete()
        staged.delete()
        val thread = HandlerThread("pam-media-transcode").apply { start() }
        val handler = Handler(thread.looper)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val exported = AtomicReference<ExportResult?>(null)
        val active = AtomicReference<Transformer?>(null)
        try {
            handler.post {
                try {
                    val transformer = transformer(thread, target, object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            exported.set(exportResult)
                            finished.countDown()
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            failure.set(exportException)
                            finished.countDown()
                        }
                    })
                    active.set(transformer)
                    transformer.start(composition(source, probe, target, options), raw.absolutePath)
                    poll(handler, transformer, active, progress)
                } catch (error: Throwable) {
                    failure.set(error)
                    finished.countDown()
                }
            }
            while (!finished.await(200, TimeUnit.MILLISECONDS)) {
                if (cancelled()) {
                    val transformer = active.getAndSet(null)
                    val stopped = CountDownLatch(1)
                    handler.post {
                        runCatching { transformer?.cancel() }
                        stopped.countDown()
                    }
                    stopped.await(5, TimeUnit.SECONDS)
                    throw TranscodeCancelledException()
                }
            }
            active.set(null)
            failure.get()?.let { throw IllegalStateException("Video transcoding failed: ${it.message}", it) }
            val result = exported.get() ?: error("Video transcoding produced no output")
            val fastStart = options.fastStart && Mp4FastStart.rewrite(raw, staged)
            if (!fastStart) check(raw.renameTo(staged)) { "Unable to stage the transcoded video" }
            raw.delete()
            check(staged.length() > 0) { "Transcoded video is empty" }
            if (!staged.renameTo(destination)) {
                destination.delete()
                check(staged.renameTo(destination)) { "Unable to move the transcoded video into place" }
            }
            progress(1.0)
            // Media3 may encode portrait output as landscape plus a container rotation; report display size.
            val written = runCatching { probe(destination) }.getOrNull()
            val duration = written?.durationMillis?.takeIf { it > 0 }
                ?: result.durationMs.takeUnless { it == C.TIME_UNSET || it <= 0 } ?: probe.durationMillis
            val width = written?.width ?: target.width
            val height = written?.height ?: target.height
            val bitrate = if (duration > 0) (destination.length() * 8_000 / duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
            return TranscodeOutput(destination, width, height, duration, bitrate, options.fastStart && Mp4FastStart.isFastStart(destination))
        } catch (error: Throwable) {
            raw.delete()
            staged.delete()
            throw error
        } finally {
            thread.quitSafely()
        }
    }

    private fun transformer(thread: HandlerThread, target: Target, listener: Transformer.Listener): Transformer {
        val video = VideoEncoderSettings.Builder()
            .setBitrate(target.bitrate)
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            .setEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.AVCProfileMain, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            .setiFrameIntervalSeconds(KEYFRAME_INTERVAL_SECONDS)
            .build()
        val audio = AudioEncoderSettings.Builder()
            .setBitrate(target.audioBitrate)
            .setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            .build()
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(video)
            .setRequestedAudioEncoderSettings(audio)
            .setEnableFallback(true)
            .build()
        return Transformer.Builder(context)
            .setLooper(thread.looper)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            .addListener(listener)
            .build()
    }

    private fun composition(source: File, probe: Probe, target: Target, options: TranscodeOptions): Composition {
        // Presentation runs after rotation is applied, so the output carries no rotation metadata.
        val video = listOf<Effect>(Presentation.createForWidthAndHeight(target.width, target.height, Presentation.LAYOUT_SCALE_TO_FIT))
        val audio = mutableListOf<AudioProcessor>()
        if (options.audio && probe.hasAudio) {
            if (probe.audioChannels == 1) {
                audio += ChannelMixingAudioProcessor().apply { putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 2)) }
            }
            val rate = probe.audioSampleRate
            if (rate != null && rate != 44_100 && rate != 48_000) audio += SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
            .setRemoveAudio(!options.audio)
            .setEffects(Effects(audio, video))
            .build()
        return Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    private fun poll(handler: Handler, transformer: Transformer, active: AtomicReference<Transformer?>, progress: (Double) -> Unit) {
        if (active.get() !== transformer) return
        val holder = ProgressHolder()
        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
            runCatching { progress(holder.progress.coerceIn(0, 100) / 100.0 * 0.98) }
        }
        handler.postDelayed({ poll(handler, transformer, active, progress) }, 250)
    }

    private fun even(value: Int) = maxOf(2, value - value % 2)

    companion object {
        private const val KEYFRAME_INTERVAL_SECONDS = 2f
        private const val LONG_VIDEO_MS = 3L * 60L * 1000L
    }
}

internal class TranscodeCancelledException : IllegalStateException("Transcode cancelled")
