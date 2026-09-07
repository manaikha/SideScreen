package com.sidescreen.app

class VideoFrame(
    val format: VideoPixelFormat,
    val width: Int,
    val height: Int
) {
    val y: ByteArray?
    val u: ByteArray?
    val v: ByteArray?
    val uv: ByteArray?
    val rgba: ByteArray?

    init {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2

        when (format) {
            VideoPixelFormat.YUV420P -> {
                y = ByteArray(width * height)
                u = ByteArray(chromaWidth * chromaHeight)
                v = ByteArray(chromaWidth * chromaHeight)
                uv = null
                rgba = null
            }

            VideoPixelFormat.NV12 -> {
                y = ByteArray(width * height)
                u = null
                v = null
                uv = ByteArray(chromaWidth * chromaHeight * 2)
                rgba = null
            }

            VideoPixelFormat.RGBA -> {
                y = null
                u = null
                v = null
                uv = null
                rgba = ByteArray(width * height * 4)
            }
        }
    }
}
