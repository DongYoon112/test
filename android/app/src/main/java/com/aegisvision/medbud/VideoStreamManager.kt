package com.aegisvision.medbud

import android.util.Log
import com.aegisvision.medbud.perception.VisionAnalysisManager
import com.meta.wearable.dat.camera.types.VideoFrame
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceViewRenderer
import java.nio.ByteBuffer
import org.webrtc.VideoFrame as RtcVideoFrame

/**
 * Converts DAT [VideoFrame]s (packed I420 in a single ByteBuffer) into
 * WebRTC [RtcVideoFrame]s and forwards them to the peer connection.
 *
 * Glasses deliver ~1–3 FPS, so frames pass straight through without any
 * frame-rate adaptation or scaling.
 */
class VideoStreamManager(
    private val webRtc: WebRTCClient,
    private val preview: SurfaceViewRenderer,
    /** Called with a fresh JPEG ~once per [perceptionIntervalMs] (null disables). */
    private val onPerceptionJpeg: ((ByteArray) -> Unit)? = null,
    private val perceptionIntervalMs: Long = 1000L,
) {

    private var frameCount = 0
    private var lastLogMs = 0L
    private var lastPerceptionMs = 0L

    fun onCameraFrame(frame: VideoFrame) {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastLogMs > 1000) {
            // Sample the centre pixel of the Y plane to verify the camera isn't
            // delivering all-black frames. Y==0 is pure black; normal scenes
            // give values across the 0–255 range.
            val src = frame.buffer
            val mid = (frame.height / 2) * frame.width + frame.width / 2
            val yMid = src.get(src.position() + mid).toInt() and 0xff
            Log.i(TAG, "frames=$frameCount size=${frame.width}x${frame.height} Y[mid]=$yMid")
            lastLogMs = now
        }
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0 || (w % 2) != 0 || (h % 2) != 0) {
            Log.w(TAG, "Skipping frame with invalid dims: ${w}x$h")
            return
        }

        val src = frame.buffer
        val frameSize = w * h
        val chromaSize = frameSize / 4
        if (src.remaining() < frameSize + 2 * chromaSize) {
            Log.w(TAG, "Truncated frame, skipping")
            return
        }

        try {
            val buffer = JavaI420Buffer.allocate(w, h)

            val yView = slice(src, 0, frameSize)
            val uView = slice(src, frameSize, chromaSize)
            val vView = slice(src, frameSize + chromaSize, chromaSize)

            copyPlane(yView, w, buffer.dataY, buffer.strideY, w, h)
            copyPlane(uView, w / 2, buffer.dataU, buffer.strideU, w / 2, h / 2)
            copyPlane(vView, w / 2, buffer.dataV, buffer.strideV, w / 2, h / 2)

            val rtcFrame = RtcVideoFrame(buffer, 0, System.nanoTime())
            // Local preview: render straight to the SurfaceView, bypassing
            // WebRTC's VideoSource adapter (which was silently dropping
            // frames because declared capture size != actual size).
            preview.onFrame(rtcFrame)
            // Push a copy to WebRTC for the browser stream.
            webRtc.pushVideoFrame(rtcFrame)
            rtcFrame.release()

            // Optional perception tap: at most one JPEG every
            // `perceptionIntervalMs`, converted off the planes we already
            // have so we don't double-copy the frame.
            val sink = onPerceptionJpeg
            if (sink != null && now - lastPerceptionMs >= perceptionIntervalMs) {
                lastPerceptionMs = now
                try {
                    val yArr = bufferToBytes(yView, w * h)
                    val uArr = bufferToBytes(uView, (w / 2) * (h / 2))
                    val vArr = bufferToBytes(vView, (w / 2) * (h / 2))
                    val jpeg = VisionAnalysisManager.encodeI420ToJpeg(yArr, uArr, vArr, w, h)
                    sink(jpeg)
                } catch (t: Throwable) {
                    Log.w(TAG, "perception JPEG encode failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to push frame", t)
        }
    }

    private fun slice(src: ByteBuffer, offset: Int, length: Int): ByteBuffer {
        val dup = src.duplicate()
        dup.position(src.position() + offset)
        dup.limit(src.position() + offset + length)
        return dup.slice()
    }

    /** Pull [size] bytes from position 0 of a slice into a fresh ByteArray. */
    private fun bufferToBytes(src: ByteBuffer, size: Int): ByteArray {
        val dup = src.duplicate()
        dup.position(0)
        val out = ByteArray(size)
        dup.get(out, 0, size)
        return out
    }

    private fun copyPlane(
        src: ByteBuffer, srcStride: Int,
        dst: ByteBuffer, dstStride: Int,
        width: Int, height: Int
    ) {
        val row = ByteArray(width)
        for (r in 0 until height) {
            src.position(r * srcStride)
            src.get(row, 0, width)
            dst.position(r * dstStride)
            dst.put(row, 0, width)
        }
    }

    companion object { private const val TAG = "VideoStreamManager" }
}
