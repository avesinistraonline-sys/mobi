package com.ronco.mobi.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.ronco.mobi.SoundState
import kotlin.math.min

/**
 * Desenha o velocimetro em tela cheia na superficie do Android Auto (a area do "mapa" de apps de navegacao).
 * Arco = rpm do som, numero grande = km/h, embaixo = marcha e rpm.
 */
class SpeedRenderer : SurfaceCallback {
    private val S = SoundState
    private val handler = Handler(Looper.getMainLooper())

    private var surface: Surface? = null
    private var sw = 0
    private var sh = 0
    private val stable = Rect()

    /** So desenha enquanto a tela do velocimetro esta por cima. */
    @Volatile var active = false

    private var msg = ""
    private var msgUntil = 0L

    private val bg = 0xFF0B0F14.toInt()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF1E2833.toInt() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA7B4.toInt(); textAlign = Paint.Align.CENTER }
    private val left = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA7B4.toInt(); textAlign = Paint.Align.LEFT }
    private val oval = RectF()

    private val tick = object : Runnable {
        override fun run() {
            draw()
            if (surface != null) handler.postDelayed(this, 100)
        }
    }

    /** Mostra uma mensagem curta (ex.: "Volume 65%") por 1,8 s no velocimetro. */
    fun flash(text: String) {
        msg = text
        msgUntil = SystemClock.elapsedRealtime() + 1800
    }

    fun release() {
        handler.removeCallbacks(tick)
        surface = null
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        sw = surfaceContainer.width
        sh = surfaceContainer.height
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onStableAreaChanged(stableArea: Rect) { stable.set(stableArea) }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        handler.removeCallbacks(tick)
        surface = null
    }

    private fun draw() {
        val s = surface ?: return
        if (!active || !s.isValid) return
        val canvas = try { s.lockCanvas(null) } catch (e: Exception) { return } ?: return
        try { paint(canvas) } finally { try { s.unlockCanvasAndPost(canvas) } catch (_: Exception) { } }
    }

    private fun levelColor(f: Float) = when {
        f < .6f -> 0xFF37D67A.toInt()
        f < .85f -> 0xFFFFB020.toInt()
        else -> 0xFFFF4D4D.toInt()
    }

    private fun engineLabel(): String {
        val eng = if (S.cylinders <= 4) "${S.cylinders} cil" else "V${S.cylinders}"
        val asp = when (S.aspiration) { 1 -> "Turbo"; 2 -> "Supercharger"; else -> "Aspirado" }
        return "$eng · $asp · ${S.powerCv.toInt()} cv"
    }

    private fun paint(c: Canvas) {
        c.drawColor(bg)
        val w = if (sw > 0) sw else c.width
        val h = if (sh > 0) sh else c.height
        val area = if (stable.width() > 0 && stable.height() > 0) Rect(stable) else Rect(0, 0, w, h)
        val cx = area.exactCenterX()
        val cy = area.exactCenterY() - area.height() * .02f
        val r = min(area.width(), area.height()) * .44f

        val running = S.running
        val rpmSpan = (S.redRpm - S.idleRpm).coerceAtLeast(1000f)
        val frac = if (running) ((S.rpm - S.idleRpm) / rpmSpan).coerceIn(0f, 1f) else 0f

        track.strokeWidth = r * .11f
        arc.strokeWidth = r * .11f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, 135f, 270f, false, track)
        if (frac > .005f) {
            arc.color = levelColor(frac)
            c.drawArc(oval, 135f, 270f * frac, false, arc)
        }

        // velocidade
        big.textSize = r * .95f
        big.color = if (running) 0xFFFFFFFF.toInt() else 0xFF5B6773.toInt()
        c.drawText("${S.kmh.toInt()}", cx, cy + r * .30f, big)
        mid.textSize = r * .17f
        c.drawText(if (running) "km/h" else "Motor desligado", cx, cy + r * .52f, mid)
        if (!running) {
            mid.textSize = r * .15f
            c.drawText("toque em LIGAR", cx, cy + r * .78f, mid)
        }

        // marcha e rpm (no vao inferior do arco)
        if (running) {
            big.textSize = r * .30f
            big.color = 0xFFFFFFFF.toInt()
            c.drawText((if (S.gear == 0) "N" else "${S.gear}ª") + "  ·  ${S.rpm.toInt()} rpm", cx, cy + r * .93f, big)
        }

        // canto superior esquerdo: motor e GPS
        left.textSize = r * .13f
        val gps = when {
            !running -> ""
            S.testKmh >= 0f -> "  ·  modo teste"
            S.gpsFix -> "  ·  GPS ok"
            else -> "  ·  aguardando GPS…"
        }
        c.drawText(engineLabel() + gps, area.left + r * .10f, area.top + r * .22f, left)

        // aviso rapido (volume etc.)
        if (SystemClock.elapsedRealtime() < msgUntil) {
            big.textSize = r * .20f
            big.color = 0xFFFFB020.toInt()
            c.drawText(msg, cx, area.top + r * .22f, big)
        }
    }
}
