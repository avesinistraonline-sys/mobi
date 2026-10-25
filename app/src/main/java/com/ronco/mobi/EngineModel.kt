package com.ronco.mobi

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Velocidade (GPS) -> marcha, rpm REAL do Mobi, pedal estimado, carga, turbo/supercharger e estouros.
 *
 * O rpm nao e mais "inventado": e a velocidade dividida pela relacao da marcha do Mobi 1.0 (5 marchas),
 * entao a 60 km/h em 5a o motor fica em ~2200 rpm, como no carro de verdade.
 * Peso, potencia e tipo de motor do carro "virtual" mudam so o CARATER do som (tamanho, graves, agressividade),
 * nunca a faixa de rpm.
 *
 * Espelho 1:1 do prototipo ronco-teste.html (mesmos numeros), para o que voce ouve no teste ser o que toca no carro.
 */
class EngineModel {
    private val S = SoundState

    private class Ev(val t: Long, val type: Float, val amp: Float)

    private var v = 0.0              // velocidade suavizada (m/s)
    private var gpsMs = 0.0
    private var acc = 0.0            // aceleracao suavizada (m/s2)
    private var pedal = 0.0
    private var prevPedal = 0.0
    private var gear = 1
    private var last = 0L
    private var lastShiftSec = -9999.0
    private var shiftUntil = 0L
    private var boostP = 0.0
    private var heat = 0.0
    private var wob = 0.0
    private var clutchIn = false
    private val sched = ArrayList<Ev>()

    fun onSpeed(ms: Float) { gpsMs = ms.toDouble() }

    // ---------- topografia (altitude do GPS) ----------
    private var altF = Double.NaN
    private var dist = 0.0
    private val ptD = DoubleArray(8)
    private val ptA = DoubleArray(8)
    private var ptN = 0
    private var gradeF = 0.0

    /** Altitude do GPS (so aceita medidas com boa precisao vertical; o erro e grande, entao e suavizada). */
    fun onAltitude(alt: Double, vAcc: Float) {
        if (vAcc > 25f) return
        altF = if (altF.isNaN()) alt else altF + (alt - altF) * 0.25
    }

    private fun updateGrade(kmh: Double) {
        if (kmh < 15.0 || altF.isNaN()) return
        val last = if (ptN > 0) ptD[(ptN - 1) % 8] else -1e9
        if (dist - last < 40.0) return
        ptD[ptN % 8] = dist; ptA[ptN % 8] = altF; ptN++
        if (ptN >= 5) {
            val old = max(0, ptN - 8)
            val oi = old % 8
            val span = dist - ptD[oi]
            if (span >= 160.0) {
                var g = (altF - ptA[oi]) / span
                g = if (abs(g) < 0.008) 0.0 else g - Math.signum(g) * 0.008       // zona morta: ruido do GPS
                gradeF += (g.coerceIn(-0.12, 0.12) * 0.8 - gradeF) * 0.3
            }
        }
    }

    // ---------- aprendizado do jeito de dirigir ----------
    private var accT = 0.0; private var accPeak = 0.0; private var accOn = false
    private var decT = 0.0; private var decSum = 0.0; private var decOn = false

    private fun learnStep(dt: Double, kmh: Double) {
        if (!S.learn || S.testKmh >= 0f) return
        // arrancadas / retomadas: pico de aceleracao do evento -> suave (<0.7 m/s2) ... pe fundo (>2 m/s2)
        if (acc > 0.35 && kmh > 2.0) { accOn = true; accT += dt; accPeak = max(accPeak, acc) }
        else if (accOn && acc < 0.15) {
            if (accT > 1.5) {
                val x = ((accPeak - 0.7) / 1.3).coerceIn(0.0, 1.0)
                S.aggr = (S.aggr + (x - S.aggr) * 0.15).toFloat()
            }
            accOn = false; accT = 0.0; accPeak = 0.0
        }
        // desaceleracoes acima de 25 km/h: suave = usa freio motor, forte = freio
        if (acc < -0.25 && kmh > 25.0) { decOn = true; decT += dt; decSum += -acc * dt }
        else if (decOn && acc > -0.1) {
            if (decT > 2.0) {
                val d = decSum / decT
                val x = ((1.6 - d) / 1.1).coerceIn(0.0, 1.0)
                S.ebrake = (S.ebrake + (x - S.ebrake) * 0.2).toFloat()
            }
            decOn = false; decT = 0.0; decSum = 0.0
        }
    }

    fun resetLearning() { S.aggr = 0.5f; S.ebrake = 0.5f }

    private fun ev(type: Int, amp: Double, t: Long) { sched.add(Ev(t, type.toFloat(), amp.coerceIn(0.0, 1.0).toFloat())) }

    private fun liftPops(now: Long) {
        val k = min(1.0, 0.35 + 0.35 * S.agg)
        var t = now + 60
        ev(1, 0.8 * k, t)
        val n = 3 + Random.nextInt(5)
        for (i in 0 until n) {
            t += 55L + Random.nextInt(140)
            ev(if (Random.nextDouble() < 0.3) 1 else 0, max(0.25, 0.7 - 0.07 * i) * k * (0.7 + 0.3 * Random.nextDouble()), t)
        }
        for (i in 0 until n + 2) {
            t += 30L + Random.nextInt(70)
            ev(2, (0.25 + 0.2 * Random.nextDouble()) * k, t)
        }
    }

    private fun shiftPops(now: Long, up: Boolean) {
        val k = min(1.0, 0.35 + 0.35 * S.agg)
        if (up) {
            ev(1, 0.7 * k, now + 70)
            ev(0, 0.5 * k, now + 140 + Random.nextInt(60))
        } else {
            var t = now + 90
            for (i in 0 until 3) {
                ev(if (Random.nextDouble() < 0.4) 1 else 0, (0.8 - 0.1 * i) * k, t)
                t += 55L + Random.nextInt(90)
            }
        }
    }

    private fun rpmGear(kmh: Double, g: Int) = kmh / MOBI_K[g - 1] * 1000.0

    fun tick(now: Long) {
        val sec = now / 1000.0
        val dt = if (last == 0L) 0.05 else ((now - last) / 1000.0).coerceIn(0.01, 0.2)
        last = now
        val v0 = v
        v += (gpsMs - v) * (1.0 - exp(-dt * 3.0))
        val accRaw = (v - v0) / dt
        acc += (accRaw - acc) * min(1.0, dt * 2.2)
        val kmh = v * 3.6
        wob += ((Random.nextDouble() * 2.0 - 1.0) - wob) * min(1.0, dt * 1.1)
        if (kmh > 15.0) dist += v * dt
        updateGrade(kmh)
        learnStep(dt, kmh)
        val grade = if (S.testGrade > -50f) S.testGrade.toDouble() else gradeF
        S.grade = grade.toFloat()

        // ---- pedal estimado (carro de referencia: o Mobi de verdade, 75 cv / 1000 kg) ----
        val rpmRef = max(1000.0, S.rpm.toDouble())
        val r = rpmRef / 6000.0
        val avail = 75.0 * 735.5 * r * (1.35 - 0.35 * r)
        val need = 0.012 * 1000.0 * 9.81 * v + 0.5 * 1.2 * 0.62 * v * v * v + 1000.0 * max(-3.0, acc) * v + 1000.0 * 9.81 * grade * v
        var pedalRaw = (need / avail).coerceIn(0.0, 1.0)
        if (kmh < 18.0 && acc > 0.25 && kmh > 1.5) pedalRaw = max(pedalRaw, min(0.9, 0.35 + 0.25 * acc))
        if (kmh < 1.5) pedalRaw = 0.0
        prevPedal = pedal
        pedal += (pedalRaw - pedal) * (1.0 - exp(-dt * (if (pedalRaw > pedal) 4.0 + 4.0 * S.aggr else 2.5)))
        val p = pedal

        // ---- carro virtual: peso / potencia ----
        val pw = (S.powerCv / S.massKg) / (75f / 1000f)
        S.agg = pw.coerceIn(0.45f, 8f)
        S.sg = (S.powerCv / 75f).toDouble().pow(0.30).toFloat().coerceIn(0.75f, 2.2f)

        // ---- marchas (o "motorista" troca por rpm; o estilo muda os pontos de troca) ----
        val style = S.drive.coerceIn(0, 2)
        val habit = 0.88 + 0.24 * S.aggr                              // quem pisa fundo troca mais tarde; quem e suave, mais cedo
        val hill = 1.0 + max(0.0, grade) * 8.0 * min(1.0, p * 2.0)    // subida: segura a marcha (sobe e reduz mais tarde)
        val sc = STYLE_SCALE[style] * habit * hill * (1.0 + 0.12 * p.pow(0.8))
        val dnSc = STYLE_SCALE[style] * (1.0 + 0.3 * (S.ebrake - 0.5)) * (if (p > 0.7) 1.08 else 1.0) * hill
        if (kmh < 2.5) {
            gear = 1
        } else if (sec - lastShiftSec > 0.9 && now >= shiftUntil) {
            val upOk = kmh > UP_KMH[min(gear, 4) - 1] * sc && p > 0.05 && (p < 0.7 || rpmGear(kmh, min(gear + 1, 5)) > 1700.0)
            if (gear < 5 && upOk) {
                gear++; lastShiftSec = sec; shiftUntil = now + 380
                if (S.pops && prevPedal > 0.5) shiftPops(now, true)
                if (S.aspiration == 1 && boostP > 0.3) S.bov = boostP.toFloat()
            } else if (gear > 1 && (kmh < min(DOWN_KMH[gear - 2] * dnSc, UP_KMH[gear - 2] * sc * 0.88) || (gear >= 3 && p > 0.92 && acc > 1.2 && rpmGear(kmh, gear) < 2100.0))) {
                gear--; lastShiftSec = sec; shiftUntil = now + 300
                if (S.pops && p < 0.2) shiftPops(now, false)
            }
        }
        val shifting = now < shiftUntil

        // ---- freando ate parar o motorista pisa na embreagem e o motor cai para a lenta ----
        clutchIn = kmh < 13.0 && p < 0.06 && acc < 0.1

        // ---- rpm ----
        val idle = S.idleRpm + 7.0 * sin(now / 190.0)
        var rpm: Double
        if (kmh < 1.5 || clutchIn) {
            rpm = idle + (if (clutchIn && kmh > 1.5) 90.0 else 0.0)
        } else {
            rpm = rpmGear(kmh, gear)
            if (gear == 1 && kmh < 16.0) {                     // arrancada: embreagem patinando
                val slip = max(0.0, 1.0 - kmh / 16.0)
                val rc = idle + (2400.0 - idle) * min(1.0, p * 1.7) * slip
                rpm = max(rpm, rc)
            }
            rpm = max(idle, rpm)
        }
        if (kmh > 3.0) rpm += sin(now / 900.0) * 18.0 + wob * 35.0
        if (rpm > S.redRpm) rpm = if (floor(now / 75.0).toLong() % 2L == 1L) S.redRpm.toDouble() else S.redRpm - 280.0
        S.rpm = rpm.toFloat()

        // ---- carga para o som ----
        var thr = p
        if (kmh < 1.5) thr = 0.04
        if (shifting) thr = 0.0
        if (clutchIn) thr = min(thr, 0.05)
        val over = if (!shifting && !clutchIn && p < 0.045 && (acc < -0.12 || grade < -0.02) && rpm > 1500.0) 1f else 0f
        S.thr = thr.toFloat(); S.over = over; S.kmh = kmh.toFloat(); S.gear = if (kmh < 1.5) 0 else gear

        // ---- turbo / supercharger ----
        val lift = prevPedal > 0.35 && p < 0.12
        val liftT = prevPedal >= 0.35 && p < 0.35                    // pedal cruzando para baixo com turbo com pressao: alivio / flutter
        val bt = ((rpm - 1400.0) / 2600.0).coerceIn(0.0, 1.0) * min(1.0, p * 1.25).pow(1.1)
        boostP += (bt - boostP) * (1.0 - exp(-dt * (if (bt > boostP) 2.8 else 3.2)))
        S.boost = if (S.aspiration == 1) boostP.toFloat() else 0f
        if (S.aspiration == 1 && liftT && boostP > 0.15) S.bov = min(1.0, 0.35 + boostP * 1.4).toFloat()
        S.scAmp = if (S.aspiration == 2) ((0.35 + 0.65 * ((rpm - 900.0) / 3500.0).coerceIn(0.0, 1.0)) * (0.4 + 0.6 * p)).toFloat() else 0f

        // ---- estouros (so se ligados) ----
        heat = max(heat * (1.0 - dt * 0.25), p)
        if (S.pops && lift && heat > 0.4 && rpm > 2200.0) liftPops(now)
        if (S.pops && over > 0f && heat > 0.15 && Random.nextDouble() < dt * 9.0 * heat * min(1.0, 0.3 + 0.4 * S.agg))
            ev(2, 0.25 + 0.3 * Random.nextDouble(), now)
        if (!S.pops) sched.clear()
        var i = 0
        while (i < sched.size) {
            val e = sched[i]
            if (e.t <= now) { S.popEvents.add(floatArrayOf(e.type, e.amp)); sched.removeAt(i) } else i++
        }
    }

    companion object {
        /** km/h por 1000 rpm em cada marcha (Fiat Mobi 1.0, 5 marchas, pneu 175/65 R14). 5a: 60 km/h = ~2200 rpm. */
        val MOBI_K = doubleArrayOf(6.1, 11.1, 16.2, 21.3, 27.0)
        /** Velocidades (km/h) de troca ajustadas para trocar de fato em ~10, 30, 40 e 52 km/h (compensa o atraso do GPS). */
        private val UP_KMH = doubleArrayOf(9.0, 28.0, 37.5, 48.5)
        /** Reduz quando a velocidade cai abaixo disso: 2a->1a, 3a->2a, 4a->3a, 5a->4a. */
        private val DOWN_KMH = doubleArrayOf(6.0, 22.0, 31.0, 42.0)
        private val STYLE_SCALE = doubleArrayOf(0.92, 1.0, 1.25)   // economico / normal / esportivo
    }
}
