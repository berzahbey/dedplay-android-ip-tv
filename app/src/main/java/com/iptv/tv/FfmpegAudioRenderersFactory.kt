package com.iptv.tv

import android.content.Context
import android.os.Handler
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Sadece FFmpeg SES cozucusunu ekler (cihazin kendi cozucusunden SONRA: cihaz destekliyorsa
 * yine donanim kullanilir; desteklemiyorsa - orn. tablette AC3/E-AC3 - FFmpeg devreye girer).
 * Kutuphanedeki deneysel FFmpeg GORUNTU cozucusu Media3 1.3.1 ile uyumsuz oldugu (cokme)
 * icin eklenti modu kapali tutulur ve o yuklenmez.
 */
class FfmpegAudioRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out
        )
        try {
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        } catch (_: Throwable) {
            // FFmpeg yuklenemezse sadece cihazin kendi cozuculeri kullanilir
        }
    }
}
