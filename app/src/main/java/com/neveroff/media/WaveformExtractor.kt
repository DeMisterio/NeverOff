package com.neveroff.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Turns a decoded voice note into a small array of normalised amplitudes (0..1), one per
 * waveform bar, so the chat bubble can draw a real spectrum instead of a placeholder.
 *
 * Decoding is done once per file and memoised both in-process and next to the decrypted
 * audio as a ".wf" sidecar, because scrubbing re-composes constantly and re-running
 * MediaCodec on every frame would be ruinous.
 */
object WaveformExtractor {

    const val BAR_COUNT = 56

    /** Above this the bar is drawn at full height; below it we still keep a visible stub. */
    private const val MIN_BAR = 0.08f

    private val memo = ConcurrentHashMap<String, FloatArray>()

    suspend fun extract(file: File, barCount: Int = BAR_COUNT): FloatArray =
        withContext(Dispatchers.IO) {
            val key = "${file.absolutePath}:$barCount"
            memo[key]?.let { return@withContext it }

            val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.wf$barCount")
            readSidecar(sidecar, barCount)?.let {
                memo[key] = it
                return@withContext it
            }

            val bars = runCatching { decode(file, barCount) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: syntheticFallback(file, barCount)

            memo[key] = bars
            runCatching { writeSidecar(sidecar, bars) }
            bars
        }

    private fun decode(file: File, barCount: Int): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)

            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return FloatArray(0)

            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            // One RMS value per decoded output buffer; resampled to barCount at the end.
            val frames = ArrayList<Float>(512)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            // Hard stop so a malformed file can never spin this loop forever.
            val deadline = System.nanoTime() + 8_000_000_000L

            while (!outputDone && System.nanoTime() < deadline) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                    else -> if (outIndex >= 0) {
                        if (info.size > 0) {
                            codec.getOutputBuffer(outIndex)?.let { out ->
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                var sumSquares = 0.0
                                var count = 0
                                while (shorts.hasRemaining()) {
                                    val sample = shorts.get() / 32768f
                                    sumSquares += (sample * sample).toDouble()
                                    count++
                                }
                                if (count > 0) frames.add(sqrt(sumSquares / count).toFloat())
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            return normalise(resample(frames, barCount))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Averages an arbitrary-length RMS series down (or up) to exactly [barCount] buckets. */
    private fun resample(frames: List<Float>, barCount: Int): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)
        val out = FloatArray(barCount)
        for (i in 0 until barCount) {
            val start = (i.toLong() * frames.size / barCount).toInt()
            val end = (((i + 1).toLong() * frames.size / barCount).toInt()).coerceAtLeast(start + 1)
            var sum = 0f
            var n = 0
            for (j in start until end.coerceAtMost(frames.size)) {
                sum += frames[j]
                n++
            }
            out[i] = if (n > 0) sum / n else 0f
        }
        return out
    }

    /**
     * Scales to the loudest bar and applies a gamma curve — raw RMS on speech is heavily
     * bottom-weighted, so a linear mapping renders as an almost flat line.
     */
    private fun normalise(bars: FloatArray): FloatArray {
        if (bars.isEmpty()) return bars
        val peak = bars.max()
        if (peak <= 0f) return FloatArray(bars.size) { MIN_BAR }
        return FloatArray(bars.size) { i ->
            ((bars[i] / peak).pow(0.65f)).coerceIn(MIN_BAR, 1f)
        }
    }

    /**
     * Used when the file can't be decoded (codec refusal, truncated upload). Derived from the
     * filename so a given message always renders the same shape rather than flickering.
     */
    private fun syntheticFallback(file: File, barCount: Int): FloatArray {
        var seed = file.name.hashCode().toLong() or 1L
        return FloatArray(barCount) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val unit = (abs((seed ushr 16).toInt() % 1000) / 1000f)
            (0.25f + unit * 0.6f).coerceIn(MIN_BAR, 1f)
        }
    }

    private fun readSidecar(sidecar: File, barCount: Int): FloatArray? {
        if (!sidecar.exists()) return null
        val bytes = runCatching { sidecar.readBytes() }.getOrNull() ?: return null
        if (bytes.size != barCount) return null
        return FloatArray(barCount) { (bytes[it].toInt() and 0xFF) / 255f }
    }

    private fun writeSidecar(sidecar: File, bars: FloatArray) {
        sidecar.writeBytes(ByteArray(bars.size) { (bars[it] * 255f).toInt().coerceIn(0, 255).toByte() })
    }
}
