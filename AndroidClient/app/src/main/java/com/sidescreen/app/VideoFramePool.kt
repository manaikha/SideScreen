package com.sidescreen.app

import java.util.concurrent.ConcurrentLinkedQueue

class VideoFramePool {

    private val pool = ConcurrentLinkedQueue<VideoFrame>()

    fun acquire(
        format: VideoPixelFormat,
        width: Int,
        height: Int
    ): VideoFrame {
        while (true) {
            val frame = pool.poll() ?: return VideoFrame(format, width, height)

            if (
                frame.format == format &&
                frame.width == width &&
                frame.height == height
            ) {
                return frame
            }
        }
    }

    fun release(frame: VideoFrame) {
        pool.offer(frame)
    }

    fun clear() {
        pool.clear()
    }
}
