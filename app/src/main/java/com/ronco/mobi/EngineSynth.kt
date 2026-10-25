package com.ronco.mobi

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Liga o [EngineDsp] (o sintetizador de verdade, sem Android) ao AudioTrack.
 * A cada bloco de 512 amostras le o [SoundState], passa para o DSP e escreve estereo 16 bits.
 * Tambem faz o teste de canais (bipe esquerdo, direito, ambos).
 */
class EngineSynth {
    private val S = SoundState
    private val sr = 44100
    private val frames = 512

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ render() }, "EngineSynth").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(800)
        thread = null
    }

    private fun render() {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(max(minBuf, frames * 4 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        val dsp = EngineDsp()
        val p = DspParams()
        val buf = FloatArray(frames * 2)
        val out16 = ShortArray(frames * 2)
        var tPhase = 0; var tCount = 0; var tPh = 0.0          // teste de canais: 1 esquerdo, 2 direito, 3 ambos

        while (running) {
            if (S.chanTest == 1) { S.chanTest = 0; tPhase = 1; tCount = 0; tPh = 0.0 }
            p.rpm = S.rpm.toDouble(); p.thr = S.thr.toDouble(); p.over = S.over.toDouble()
            p.cyl = S.cylinders; p.sg = S.sg.toDouble(); p.agg = S.agg.toDouble()
            p.asp = S.aspiration; p.boost = S.boost.toDouble(); p.sc = S.scAmp.toDouble()
            for (i in 0 until 11) p.mix[i] = S.mix[i].toDouble()
            p.vol = S.volume.toDouble(); p.width = S.width.toDouble(); p.mono = S.monoOut; p.engineOn = S.engineOn
            val b = S.bov
            if (b > 0f) { S.bov = 0f; dsp.trigBov(b.toDouble(), S.relief) }
            while (true) {
                val e = S.popEvents.poll() ?: break
                dsp.addPop(e[0].toInt(), e[1].toDouble())
            }

            dsp.process(p, buf, frames)

            for (i in 0 until frames) {
                var l = buf[2 * i]; var r = buf[2 * i + 1]
                if (tPhase > 0) {                                  // teste: bipe 440 Hz esquerdo -> direito -> ambos
                    val tone = (sin(2.0 * PI * tPh) * 0.5).toFloat()
                    tPh += 440.0 / sr; if (tPh >= 1.0) tPh -= 1.0
                    l = if (tPhase != 2) tone else 0f
                    r = if (tPhase != 1) tone else 0f
                    if (++tCount >= sr) { tCount = 0; tPhase = if (tPhase >= 3) 0 else tPhase + 1 }
                }
                out16[2 * i] = (l * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                out16[2 * i + 1] = (r * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            track.write(out16, 0, frames * 2)
        }
        track.stop()
        track.release()
    }
}
