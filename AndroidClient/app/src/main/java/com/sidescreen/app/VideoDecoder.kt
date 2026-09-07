package com.sidescreen.app

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.Image
import java.nio.ByteBuffer
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Display
import java.util.concurrent.ConcurrentLinkedQueue

private fun diagLog(msg: String) = DiagLog.log("VD", msg)

class VideoDecoder(
    private val framePool: VideoFramePool,
    private val display: Display? = null,
    initialWidth: Int = 1920,
    initialHeight: Int = 1200,
    // Exposed so MainActivity can detect a codec-negotiation/decoder mismatch
    // and recreate the decoder (see MainActivity.onStreamCodecSelected).
    val mime: String = MediaFormat.MIMETYPE_VIDEO_HEVC,
) {
    private var decoder: MediaCodec? = null
    private var decoderThread: HandlerThread? = null
    private var decoderHandler: Handler? = null

    private var frameCount = 0L
    private var droppedFrames = 0L
    private var staleOutputDrops = 0L
    private var lastStatsTime = System.currentTimeMillis()
    private var inputFrameCount = 0L
    private var outputFrameCount = 0L

    // Decoder pipeline latency (input enqueue -> output buffer available),
    // accumulated over ~60 frames then logged. High values indicate the codec
    // is queuing frames internally (compose/present can't keep up downstream),
    // which surfaces to the user as input lag on the captured display.
    private var latencySumNs: Long = 0
    private var latencySamples: Int = 0
    private var latencyMaxNs: Long = 0

    private val frameTimes = ArrayDeque<Long>(120)

    private val displayRefreshRate = display?.refreshRate ?: 60f

    private var currentWidth = initialWidth
    private var currentHeight = initialHeight

    @Volatile private var isRunning = false

    @Volatile private var needsKeyframe = true

    private var lastKeyframeRequestNs = 0L

    var onFrameRendered: ((Long) -> Unit)? = null
    var onFrameStats: ((fps: Double, variance: Double) -> Unit)? = null
    var onFrame: ((VideoFrame) -> Unit)? = null

    private var pixelFormat = VideoPixelFormat.YUV420P
    private var chromaInterleaved = false
    private var outStride = 0
    private var outSliceHeight = 0
    private var outWidth = initialWidth
    private var outHeight = initialHeight
    private var discardOutputsUntilKeyframe = false
    private var planeScratch = ByteArray(0)
    var onFrameDecoded: ((ByteArray) -> Unit)? = null
    var onKeyframeRequired: ((force: Boolean, reason: String) -> Unit)? = null

    /** Fired once when the decoder has accepted many frames but never output any —
     *  the black-screen-with-live-stats signature (stream above the device's
     *  decode limit, or an unusable decoder). Counts only frames actually queued
     *  to MediaCodec, so pre-keyframe drops on a slow start can't trigger it. */
    var onDecoderStalled: (() -> Unit)? = null
    private var stallReported = false
    private var queuedInputCount = 0L

    // Available input buffer indices — fed by onInputBufferAvailable callback
    private val availableInputBuffers = ConcurrentLinkedQueue<Int>()

    init {
        setupDecoder()
    }

    fun updateResolution(
        width: Int,
        height: Int,
    ) {
        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            release()
            setupDecoder()
            requestKeyframe("resolution changed", force = true)
        }
    }

    private fun setupDecoder() {
        decoderThread = HandlerThread("DecoderThread", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        decoderHandler = Handler(decoderThread!!.looper)

        // Find a decoder that supports our resolution (prefer HW, fallback to SW)
        val decoderName = findBestDecoder(currentWidth, currentHeight)
        diagLog("setupDecoder: ${currentWidth}x$currentHeight, decoder=$decoderName")

        val codec =
            if (decoderName != null) {
                MediaCodec.createByCodecName(decoderName)
            } else {
                MediaCodec.createDecoderByType(mime)
            }

        val callback =
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                ) {
                    availableInputBuffers.offer(index)
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    handleOutputBuffer(codec, index, info)
                }

                override fun onError(
                    codec: MediaCodec,
                    e: MediaCodec.CodecException,
                ) {
                    diagLog("Codec error: ${e.diagnosticInfo}")
                    Log.e(TAG, "Codec error: ${e.diagnosticInfo}", e)
                    needsKeyframe = true
                    requestKeyframe("codec error", force = true)
                }

                override fun onOutputFormatChanged(
                    codec: MediaCodec,
                    format: MediaFormat,
                ) {
                    diagLog("Output format changed: $format")
                }
            }
        codec.setCallback(callback, decoderHandler)

        val format =
            MediaFormat.createVideoFormat(
                mime,
                currentWidth,
                currentHeight,
            )

        var configured = false

        // Attempt 1: Full low-latency config
        try {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, displayRefreshRate.toInt())
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            codec.configure(format, null, null, 0)
            configured = true
            diagLog("Configured with full low-latency")
        } catch (e: Exception) {
            diagLog("Full low-latency config failed: ${e.message}")
            codec.reset()
            codec.setCallback(callback, decoderHandler)
        }

        // Attempt 2: Without KEY_LOW_LATENCY
        if (!configured) {
            try {
                val basicFormat =
                    MediaFormat.createVideoFormat(
                        mime,
                        currentWidth,
                        currentHeight,
                    )
                basicFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
                basicFormat.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                codec.configure(basicFormat, null, null, 0)
                configured = true
                diagLog("Configured with basic format")
            } catch (e: Exception) {
                diagLog("Basic config failed: ${e.message}")
                codec.reset()
                codec.setCallback(callback, decoderHandler)
            }
        }

        // Attempt 3: Minimal config (just resolution)
        if (!configured) {
            try {
                val minimalFormat =
                    MediaFormat.createVideoFormat(
                        mime,
                        currentWidth,
                        currentHeight,
                    )
                codec.configure(minimalFormat, null, null, 0)
                diagLog("Configured with minimal format")
            } catch (e: Exception) {
                diagLog("All configure attempts failed: ${e.message}")
                Log.e(TAG, "All configure attempts failed", e)
                codec.release()
                decoderThread?.quitSafely()
                decoderThread = null
                decoderHandler = null
                throw e
            }
        }

        codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        needsKeyframe = true
        isRunning = true
        codec.start()
        decoder = codec
        diagLog(
            "Decoder started: ${currentWidth}x$currentHeight @ ${displayRefreshRate}Hz, " +
                "output=byte-buffer",
        )
    }

    /**
     * Find the best decoder for [mime] at the given resolution.
     * Prefers hardware decoders, falls back to software if HW can't handle the resolution.
     * Returns codec name to use with MediaCodec.createByCodecName(), or null for default.
     */
    private fun findBestDecoder(
        width: Int,
        height: Int,
    ): String? {
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val targetRate = displayRefreshRate.toDouble().coerceAtLeast(30.0)
            var hwRateDecoder: String? = null
            var hwSizeDecoder: String? = null
            var swRateDecoder: String? = null
            var swSizeDecoder: String? = null

            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                val caps =
                    try {
                        info.getCapabilitiesForType(mime)
                    } catch (_: Exception) {
                        continue
                    }

                val videoCaps = caps.videoCapabilities ?: continue
                val isHardware =
                    !info.name.startsWith("c2.android.") &&
                        !info.name.startsWith("OMX.google.")
                val supported = videoCaps.isSizeSupported(width, height)
                val rateSupported =
                    supported &&
                        try {
                            videoCaps.areSizeAndRateSupported(width, height, targetRate)
                        } catch (_: Exception) {
                            false
                        }

                diagLog(
                    "$mime decoder '${info.name}': " +
                        "width=${videoCaps.supportedWidths}, " +
                        "height=${videoCaps.supportedHeights}, " +
                        "hw=$isHardware, supports ${width}x$height=$supported, " +
                        "supports @${"%.0f".format(targetRate)}fps=$rateSupported",
                )

                if (supported) {
                    if (isHardware && rateSupported && hwRateDecoder == null) {
                        hwRateDecoder = info.name
                    } else if (isHardware && hwSizeDecoder == null) {
                        hwSizeDecoder = info.name
                    } else if (!isHardware && rateSupported && swRateDecoder == null) {
                        swRateDecoder = info.name
                    } else if (!isHardware && swSizeDecoder == null) {
                        swSizeDecoder = info.name
                    }
                }
            }

            // Prefer hardware that advertises the target refresh rate, then any
            // hardware decoder for the size, then software as a last resort.
            val chosen = hwRateDecoder ?: hwSizeDecoder ?: swRateDecoder ?: swSizeDecoder
            if (chosen != null) {
                diagLog(
                    "Selected decoder: $chosen " +
                        "(rateSupported=${chosen == hwRateDecoder || chosen == swRateDecoder})",
                )
            } else {
                diagLog("No decoder supports ${width}x$height — will use default")
            }
            return chosen
        } catch (e: Exception) {
            diagLog("Decoder search failed: ${e.message}")
        }
        return null
    }

    fun decode(
        frameData: ByteArray,
        frameSize: Int = frameData.size,
        frameTimestamp: Long = System.nanoTime(),
        isKeyframe: Boolean = false,
    ) {
        if (!isRunning) {
            diagLog("decode called but isRunning=false")
            onFrameDecoded?.invoke(frameData)
            return
        }

        inputFrameCount++
        if (inputFrameCount == 1L) {
            val header =
                frameData
                    .take(minOf(16, frameSize))
                    .joinToString(" ") { String.format("%02x", it) }
            diagLog(
                "First frame: size=$frameSize, header=[$header], " +
                    "keyframe=$isKeyframe, output=byte-buffer",
            )
        }
        if (inputFrameCount % 60L == 0L) {
            diagLog(
                "Decode stats: input=$inputFrameCount, output=$outputFrameCount, " +
                    "dropped=$droppedFrames, availBufs=${availableInputBuffers.size}",
            )
        }
        val codec =
            decoder ?: run {
                diagLog("decoder is null in decode()")
                onFrameDecoded?.invoke(frameData)
                return
            }

        if (needsKeyframe && !isKeyframe) {
            dropFrame(
                frameData,
                isKeyframe,
                "waiting for keyframe",
                waitForKeyframe = true,
            )
            return
        }

        // Direct feed: grab an available input buffer and queue immediately.
        val index = availableInputBuffers.poll()
        if (index == null) {
            // Decoder input pool exhausted (typically a WiFi burst saturating
            // MediaCodec). Do NOT pause the pipeline — keep feeding so the
            // cursor tracks live. Reference state diverges briefly (cursor
            // trail visible), but a force-keyframe request bypasses every
            // layer's throttle and rebuilds the reference within ~100-200 ms,
            // which feels better than a 1-2 s freeze waiting on the next
            // throttled request to land.
            droppedFrames++
            if (droppedFrames <= 3L || droppedFrames % 60L == 0L) {
                diagLog("Dropping frame (no input buffer, dropped=$droppedFrames)")
            }
            requestKeyframe("no input buffer", force = true)
            onFrameDecoded?.invoke(frameData)
            return
        }

        queueFrame(codec, index, frameData, frameSize, frameTimestamp, isKeyframe)
    }

    private fun queueFrame(
        codec: MediaCodec,
        index: Int,
        frameData: ByteArray,
        frameSize: Int,
        frameTimestamp: Long,
        isKeyframe: Boolean,
    ) {
        try {
            val inputBuffer =
                codec.getInputBuffer(index)
                    ?: throw IllegalStateException("Input buffer $index is null")
            inputBuffer.clear()
            inputBuffer.put(frameData, 0, frameSize)
            codec.queueInputBuffer(index, 0, frameSize, frameTimestamp / 1000, 0)
            queuedInputCount++
            if (queuedInputCount == STALL_DETECT_INPUT_FRAMES && outputFrameCount == 0L && !stallReported) {
                stallReported = true
                diagLog("Decoder stalled: $queuedInputCount frames queued, none out")
                onDecoderStalled?.invoke()
            }
            if (isKeyframe) {
                needsKeyframe = false
            }
        } catch (e: Exception) {
            needsKeyframe = true
            requestKeyframe("queue input failed")
            Log.e(TAG, "decode direct feed error", e)
        } finally {
            onFrameDecoded?.invoke(frameData)
        }
    }

    private fun dropFrame(
        frameData: ByteArray,
        isKeyframe: Boolean,
        reason: String,
        waitForKeyframe: Boolean,
        requestRefresh: Boolean = waitForKeyframe,
    ) {
        droppedFrames++
        if (droppedFrames <= 3L || droppedFrames % 60L == 0L) {
            diagLog("Dropping frame ($reason, keyframe=$isKeyframe, dropped=$droppedFrames)")
        }
        if (waitForKeyframe) {
            needsKeyframe = true
        }
        if (requestRefresh) {
            requestKeyframe(reason)
        }
        onFrameDecoded?.invoke(frameData)
    }

    private fun requestKeyframe(
        reason: String,
        force: Boolean = false,
    ) {
        val now = System.nanoTime()
        val interval =
            if (force) FORCE_KEYFRAME_REQUEST_INTERVAL_NS else KEYFRAME_REQUEST_INTERVAL_NS
        if (now - lastKeyframeRequestNs < interval) {
            return
        }
        lastKeyframeRequestNs = now
        diagLog("Requesting keyframe: reason=$reason, force=$force")
        onKeyframeRequired?.invoke(force, reason)
    }

    private fun handleOutputBuffer(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        try {
            outputFrameCount++

            if (outputFrameCount == 1L) {
                diagLog("First output frame! size=${info.size}, flags=${info.flags}")
            }

            val nowNs = System.nanoTime()
            val latencyNs = nowNs - info.presentationTimeUs * 1000L
            val hasValidLatency = latencyNs in 0..MAX_REASONABLE_LATENCY_NS

            if (hasValidLatency) {
                latencySumNs += latencyNs
                latencySamples++
                if (latencyNs > latencyMaxNs) latencyMaxNs = latencyNs
            }

            if (outputFrameCount % 60L == 0L) {
                val avgMs =
                    if (latencySamples > 0) {
                        latencySumNs / latencySamples / 1_000_000.0
                    } else {
                        0.0
                    }
                val maxMs = latencyMaxNs / 1_000_000.0

                diagLog(
                    "Output #$outputFrameCount: decoder latency " +
                        "avg=${"%.1f".format(avgMs)}ms " +
                        "max=${"%.1f".format(maxMs)}ms " +
                        "over $latencySamples samples, " +
                        "input bufs avail=${availableInputBuffers.size}, " +
                        "dropped=$droppedFrames",
                )

                latencySumNs = 0
                latencySamples = 0
                latencyMaxNs = 0
            }

            val shouldRender =
                outputFrameCount == 1L ||
                    !hasValidLatency ||
                    latencyNs <= MAX_RENDER_LATENCY_NS

            if (!shouldRender) {
                droppedFrames++
                staleOutputDrops++

                if (staleOutputDrops <= 3L || staleOutputDrops % 60L == 0L) {
                    diagLog(
                        "Dropping stale output frame: " +
                            "latency=${"%.1f".format(latencyNs / 1_000_000.0)}ms, " +
                            "staleDrops=$staleOutputDrops",
                    )
                }

                codec.releaseOutputBuffer(index, false)
                updateStats()
                return
            }

            if (discardOutputsUntilKeyframe) {
                if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) {
                    codec.releaseOutputBuffer(index, false)
                    droppedFrames++
                    updateStats()
                    return
                }

                discardOutputsUntilKeyframe = false
                diagLog("Resumed output at keyframe")
            }

            detectOutputFormat(codec)

            val width = outWidth.coerceAtLeast(1)
            val height = outHeight.coerceAtLeast(1)

            val frame = framePool.acquire(pixelFormat, width, height)

            try {
                if (!fillFrame(codec, index, pixelFormat, width, height, frame)) {
                    framePool.release(frame)
                    codec.releaseOutputBuffer(index, false)
                    droppedFrames++
                    return
                }

                onFrame?.invoke(frame)
                trackFrameTiming(nowNs)
                updateStats()

                // Ownership transfers to the renderer callback.
                // It must return the frame to framePool when no longer needed.
            } catch (e: Exception) {
                framePool.release(frame)
                throw e
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Output buffer handling failed", e)
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (_: Exception) {
            }
        }
    }

    private fun detectOutputFormat(codec: MediaCodec) {
        val format = try {
            codec.outputFormat
        } catch (_: Exception) {
            return
        }

        val colorFormat =
            if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
                format.getInteger(MediaFormat.KEY_COLOR_FORMAT)
            } else {
                0
            }

        outWidth =
            if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                format.getInteger(MediaFormat.KEY_WIDTH)
            } else {
                currentWidth
            }

        outHeight =
            if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                format.getInteger(MediaFormat.KEY_HEIGHT)
            } else {
                currentHeight
            }

        outStride =
            if (format.containsKey("stride")) {
                format.getInteger("stride")
            } else {
                outWidth
            }

        outSliceHeight =
            if (format.containsKey("slice-height")) {
                format.getInteger("slice-height")
            } else {
                outHeight
            }

        pixelFormat =
            when (colorFormat) {
                1, 0x7F000789 -> VideoPixelFormat.RGBA
                19, 20, 0x7F420888 -> VideoPixelFormat.YUV420P
                21, 22 -> VideoPixelFormat.NV12
                else -> VideoPixelFormat.YUV420P
            }

        diagLog(
            "Output format: color-format=0x${colorFormat.toString(16)}, " +
                "pixelFormat=$pixelFormat, " +
                "size=${outWidth}x$outHeight, " +
                "stride=$outStride, slice=$outSliceHeight",
        )
    }

    private fun fillFrame(
        codec: MediaCodec,
        index: Int,
        format: VideoPixelFormat,
        width: Int,
        height: Int,
        frame: VideoFrame,
    ): Boolean {
        return when (format) {
            VideoPixelFormat.YUV420P -> fillYuv420(codec, index, width, height, frame)
            VideoPixelFormat.NV12 -> fillNv12(codec, index, width, height, frame)
            VideoPixelFormat.RGBA -> fillRgba(codec, index, width, height, frame)
        }
    }

    private fun fillYuv420(
        codec: MediaCodec,
        index: Int,
        width: Int,
        height: Int,
        frame: VideoFrame,
    ): Boolean {
        val image =
            try {
                codec.getOutputImage(index)
            } catch (e: Exception) {
                diagLog("getOutputImage failed: ${e.message}")
                null
            }

        if (image != null) {
            try {
                val planes = image.planes
                if (planes.size < 3) return false

                val yPlane = planes[0]
                val uPlane = planes[1]
                val vPlane = planes[2]

                val y = frame.y ?: return false

                copyPlane(
                    yPlane.buffer,
                    yPlane.rowStride,
                    yPlane.pixelStride,
                    width,
                    height,
                    y,
                )

                if (uPlane.pixelStride == 1 && vPlane.pixelStride == 1) {
                    val u = frame.u ?: return false
                    val v = frame.v ?: return false

                    copyPlane(
                        uPlane.buffer,
                        uPlane.rowStride,
                        uPlane.pixelStride,
                        (width + 1) / 2,
                        (height + 1) / 2,
                        u,
                    )

                    copyPlane(
                        vPlane.buffer,
                        vPlane.rowStride,
                        vPlane.pixelStride,
                        (width + 1) / 2,
                        (height + 1) / 2,
                        v,
                    )
                } else {
                    chromaInterleaved = true
                    pixelFormat = VideoPixelFormat.NV12

                    // The acquired frame has to match the actual output format.
                    // This path is only used when Qualcomm exposes interleaved
                    // chroma through flexible Image planes.
                    val uv = frame.uv
                    if (uv == null) {
                        image.close()
                        return false
                    }

                    copyInterleavedChroma(
                        uPlane.buffer,
                        vPlane.buffer,
                        uPlane.rowStride,
                        vPlane.rowStride,
                        uPlane.pixelStride,
                        vPlane.pixelStride,
                        (width + 1) / 2,
                        (height + 1) / 2,
                        uv,
                    )
                }

                return true
            } finally {
                image.close()
            }
        }

        val buffer = codec.getOutputBuffer(index) ?: return false
        val y = frame.y ?: return false
        val u = frame.u ?: return false
        val v = frame.v ?: return false

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val ySize = width * height
        val uvSize = chromaWidth * chromaHeight

        if (buffer.remaining() < ySize + uvSize * 2) return false

        buffer.get(y, 0, ySize)
        buffer.get(u, 0, uvSize)
        buffer.get(v, 0, uvSize)

        return true
    }

    private fun fillNv12(
        codec: MediaCodec,
        index: Int,
        width: Int,
        height: Int,
        frame: VideoFrame,
    ): Boolean {
        val image =
            try {
                codec.getOutputImage(index)
            } catch (_: Exception) {
                null
            }

        if (image != null) {
            try {
                val planes = image.planes
                if (planes.size < 2) return false

                val yPlane = planes[0]
                val uvPlane = planes[1]

                val y = frame.y ?: return false
                val uv = frame.uv ?: return false

                copyPlane(
                    yPlane.buffer,
                    yPlane.rowStride,
                    yPlane.pixelStride,
                    width,
                    height,
                    y,
                )

                copyPlaneInterleaved(
                    uvPlane.buffer,
                    uvPlane.rowStride,
                    uvPlane.pixelStride,
                    (width + 1) / 2,
                    (height + 1) / 2,
                    uv,
                )

                return true
            } finally {
                image.close()
            }
        }

        val buffer = codec.getOutputBuffer(index) ?: return false
        val y = frame.y ?: return false
        val uv = frame.uv ?: return false

        val ySize = width * height
        val uvSize = ((width + 1) / 2) * ((height + 1) / 2) * 2

        if (buffer.remaining() < ySize + uvSize) return false

        buffer.get(y, 0, ySize)
        buffer.get(uv, 0, uvSize)

        return true
    }

    private fun fillRgba(
        codec: MediaCodec,
        index: Int,
        width: Int,
        height: Int,
        frame: VideoFrame,
    ): Boolean {
        val rgba = frame.rgba ?: return false
        val buffer = codec.getOutputBuffer(index) ?: return false
        val required = width * height * 4

        if (buffer.remaining() < required) return false

        buffer.get(rgba, 0, required)
        return true
    }

    private fun copyPlane(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        dst: ByteArray,
    ) {
        val base = src.position()
        val rowBytes = width

        ensureScratch(rowBytes)

        for (row in 0 until height) {
            val rowStart = base + row * rowStride

            if (pixelStride == 1) {
                src.position(rowStart)
                val count = minOf(width, src.limit() - rowStart)
                if (count > 0) src.get(dst, row * width, count)

                if (count < width) {
                    java.util.Arrays.fill(
                        dst,
                        row * width + maxOf(count, 0),
                        row * width + width,
                        0x80.toByte(),
                    )
                }
            } else {
                val needed = width * pixelStride
                ensureScratch(needed)

                src.position(rowStart)
                val count = minOf(needed, src.limit() - rowStart)

                if (count > 0) src.get(planeScratch, 0, count)

                val dstOffset = row * width
                var x = 0
                var out = 0

                while (x < count && out < width) {
                    dst[dstOffset + out] = planeScratch[x]
                    x += pixelStride
                    out++
                }

                while (out < width) {
                    dst[dstOffset + out] = 0x80.toByte()
                    out++
                }
            }
        }

        src.position(base)
    }

    private fun copyPlaneInterleaved(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        dst: ByteArray,
    ) {
        val base = src.position()
        val rowBytes = width * 2

        for (row in 0 until height) {
            val rowStart = base + row * rowStride
            src.position(rowStart)

            if (pixelStride == 2) {
                val count = minOf(rowBytes, src.limit() - rowStart)
                if (count > 0) src.get(dst, row * rowBytes, count)
            } else {
                val dstOffset = row * rowBytes
                val count = minOf(width, src.limit() - rowStart)

                var i = 0
                while (i < count) {
                    val value = src.get(rowStart + i)
                    dst[dstOffset + i * 2] = value
                    i++
                }
            }
        }

        src.position(base)
    }

    private fun copyInterleavedChroma(
        u: ByteBuffer,
        v: ByteBuffer,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int,
        dst: ByteArray,
    ) {
        val uBase = u.position()
        val vBase = v.position()

        for (row in 0 until height) {
            val dstOffset = row * width * 2

            for (x in 0 until width) {
                val uPos = uBase + row * uRowStride + x * uPixelStride
                val vPos = vBase + row * vRowStride + x * vPixelStride

                if (uPos < u.limit()) dst[dstOffset + x * 2] = u.get(uPos)
                if (vPos < v.limit()) dst[dstOffset + x * 2 + 1] = v.get(vPos)
            }
        }

        u.position(uBase)
        v.position(vBase)
    }

    private fun ensureScratch(size: Int) {
        if (planeScratch.size < size) {
            planeScratch = ByteArray(size)
        }
    }

    private fun trackFrameTiming(timestamp: Long) {
        frameTimes.addLast(timestamp)
        if (frameTimes.size > 120) frameTimes.removeFirst()

        if (frameTimes.size >= 60 && frameCount % 60L == 0L) {
            val deltas = frameTimes.zipWithNext { a, b -> (b - a) / 1_000_000.0 }
            if (deltas.isNotEmpty()) {
                val avgDelta = deltas.average()
                val variance = deltas.map { (it - avgDelta) * (it - avgDelta) }.average()
                val stdDev = kotlin.math.sqrt(variance)
                onFrameStats?.invoke(1000.0 / avgDelta, stdDev)
            }
        }
        onFrameRendered?.invoke(timestamp)
    }

    private fun updateStats() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastStatsTime
        if (elapsed >= 1000) {
            frameCount = 0
            droppedFrames = 0
            staleOutputDrops = 0
            lastStatsTime = now
        }
    }

    fun release() {
        isRunning = false
        try {
            availableInputBuffers.clear()
            decoder?.stop()
            decoder?.release()
            decoder = null
            decoderThread?.quitSafely()
            decoderThread = null
            decoderHandler = null
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val STALL_DETECT_INPUT_FRAMES = 120L
        private const val KEYFRAME_REQUEST_INTERVAL_NS = 1_000_000_000L
        private const val FORCE_KEYFRAME_REQUEST_INTERVAL_NS = 200_000_000L
        private const val MAX_RENDER_LATENCY_NS = 100_000_000L
        private const val MAX_REASONABLE_LATENCY_NS = 2_000_000_000L
    }
}
