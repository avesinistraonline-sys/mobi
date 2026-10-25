package com.ronco.mobi

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Sintetizador do motor (DSP puro, sem Android). Espelho 1:1 do prototipo ronco-teste.html.
 *
 * Cadeia: ignicao por cilindro (irregular, com jitter) -> pulso de escape -> guia de onda do escapamento
 *         (comb com reflexao invertida) -> modos fixos do abafador -> passa-baixas que abre com a carga
 *       + admissao (pulsos + ruido de fluxo), graves do bloco, chiado de combustao, mecanico,
 *       + estouros, turbo (assobio, alivio, flutter) e supercharger.
 * As ressonancias do escapamento ficam FIXAS enquanto o rpm varre os harmonicos: e isso que faz soar como um motor de verdade.
 */
class DspParams {
    @JvmField var rpm = 850.0
    @JvmField var thr = 0.0
    @JvmField var over = 0.0
    @JvmField var cyl = 4
    @JvmField var sg = 1.0
    @JvmField var agg = 1.0
    @JvmField var asp = 0
    @JvmField var boost = 0.0
    @JvmField var sc = 0.0
    @JvmField val mix = DoubleArray(11) { 1.0 }
    @JvmField var vol = 0.7
    @JvmField var width = 0.25
    @JvmField var mono = false
    @JvmField var engineOn = true
}

private const val SRD = 44100.0
private const val TAU = 2.0 * PI

private fun pole(fc: Double): Double = 1.0 - exp(-TAU * fc / SRD)

private class Rng(seed: Int) {
    private var s: Int = if (seed == 0) 1 else seed
    fun next(): Double {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        s = x
        return (x.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
    fun sym(): Double = next() * 2.0 - 1.0
    fun gauss(): Double = (next() + next() + next() - 1.5) * 2.0
}

/** Filtro de estado variavel (TPT): estavel em baixa frequencia. */
private class Svf {
    var a1 = 0.0; var a2 = 0.0; var a3 = 0.0; var k = 1.0
    var c1 = 0.0; var c2 = 0.0
    var lp = 0.0; var bp = 0.0; var hp = 0.0
    fun set(fc: Double, q: Double) {
        val g = tan(PI * min(fc, SRD * 0.45) / SRD)
        k = 1.0 / q
        a1 = 1.0 / (1.0 + g * (g + k)); a2 = g * a1; a3 = g * a2
    }
    fun run(x: Double): Double {
        val v3 = x - c2
        val v1 = a1 * c1 + a2 * v3
        val v2 = c2 + a2 * c1 + a3 * v3
        c1 = 2.0 * v1 - c1; c2 = 2.0 * v2 - c2
        bp = k * v1; lp = v2; hp = x - k * v1 - v2
        return bp
    }
}

private class Bank(val delay: Int) {
    val buf = DoubleArray(1024)
    var w = 0
    var sA = 0.0; var sD = 0.0; var dmp = 0.0
    val ring = DoubleArray(64)
    val m1 = Svf().also { it.set(105.0, 2.6) }
    val m2 = Svf().also { it.set(235.0, 3.2) }
    val m3 = Svf().also { it.set(430.0, 3.5) }
    var l1 = 0.0; var l2 = 0.0; var s1 = 0.0; var s2 = 0.0
    val b1 = Svf().also { it.set(320.0, 0.7) }
}

class EngineDsp {
    private val rng = Rng(20241024)
    private var phase = 0.0
    private var rpmS = 900.0
    private var thrS = 0.1
    private var ovS = 0.0
    private var sgS = 1.0
    private var aggS = 1.0
    private var cyl = 0
    private var nSlots = 4
    private var nBanks = 1
    private var lastSlot = 0
    private val slotBank = IntArray(12)
    private val slotTarget = DoubleArray(12) { 1.0 }
    private val slotGain = DoubleArray(12) { 1.0 }
    private val bankA = Bank(489)
    private val bankB = Bank(527)
    private var head = 0
    // admissao
    private var iA = 0.0; private var iD = 0.0
    private val iRing = DoubleArray(64)
    private val iL = Svf(); private val iR = Svf()
    private var iL1 = 0.0; private var iL2 = 0.0; private var iR1 = 0.0; private var iR2 = 0.0
    private var aI = 0.1
    // rasp / mecanico
    private val rasp1 = Svf().also { it.set(950.0, 0.9) }
    private val rasp2 = Svf().also { it.set(1900.0, 1.0) }
    private var raspEnv = 0.0
    private val mech = Svf().also { it.set(2600.0, 2.0) }
    private var mechEnv = 0.0
    // lfos / marcha lenta
    private val lf = doubleArrayOf(0.13, 0.41, 0.77, 0.23)
    private var idleHunt = 0.0; private var idleTgt = 0.0; private var idleCnt = 0
    // saida
    private val hpL = Svf().also { it.set(46.0, 0.75) }
    private val hpR = Svf().also { it.set(46.0, 0.75) }
    private var shL = 0.0; private var shR = 0.0
    // turbo / supercharger
    private var nT = 0.0; private var wh1 = 0.0
    private val tw = Svf().also { it.set(2100.0, 1.2) }
    private var bovEnv = 0.0
    private val bovSvf = Svf().also { it.set(2600.0, 1.0) }
    private var flut = 0.0; private var flutPh = 0.0
    private val flutSvf = Svf().also { it.set(1000.0, 0.9) }
    private var scPh = 0.0; private var scA = 0.0; private var scLP = 0.0
    // estouros
    private val nV = 8
    private val vEnv = DoubleArray(8); private val vDec = DoubleArray(8)
    private val vTh = DoubleArray(8); private val vThD = DoubleArray(8)
    private val vPh = DoubleArray(8); private val vF = DoubleArray(8)
    private val vSvf = Array(8) { Svf() }
    private val vDel = IntArray(8); private val vAmp = DoubleArray(8)
    private var nextV = 0

    init { setCyl(4) }

    private fun setCyl(n: Int) {
        cyl = n; nSlots = n
        val pat: IntArray = when (n) {
            3 -> intArrayOf(0, 0, 0)
            6 -> intArrayOf(0, 1, 0, 1, 0, 1)
            8 -> intArrayOf(0, 1, 1, 0, 1, 0, 0, 1)
            10 -> intArrayOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1)
            12 -> intArrayOf(0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1)
            else -> intArrayOf(0, 0, 0, 0)
        }
        nBanks = if (n >= 6) 2 else 1
        val r = Rng(n * 977 + 13)
        val irr = when (n) { 8 -> 0.10; 4 -> 0.06; 3 -> 0.08; else -> 0.045 }
        for (i in 0 until 12) {
            slotBank[i] = if (i < n && i < pat.size) pat[i] else 0
            slotTarget[i] = 1.0 + r.sym() * irr
            slotGain[i] = slotTarget[i]
        }
        lastSlot = 0
    }

    /** Estouro: tipo 0 estalo, 1 estouro grave, 2 burble. */
    fun addPop(type: Int, amp: Double) {
        val i = nextV; nextV = (nextV + 1) % nV
        val r = rng.next()
        val fc: Double; val dec: Double; val th: Double
        when (type) {
            1 -> { fc = 190.0 + 160.0 * r; dec = exp(-1.0 / (0.050 * SRD)); th = 1.0 }
            0 -> { fc = 650.0 + 700.0 * r; dec = exp(-1.0 / (0.016 * SRD)); th = 0.35 }
            else -> { fc = 140.0 + 120.0 * r; dec = exp(-1.0 / (0.030 * SRD)); th = 0.6 }
        }
        vSvf[i].set(fc, 1.6); vEnv[i] = amp; vDec[i] = dec; vTh[i] = amp * th; vThD[i] = exp(-1.0 / (0.07 * SRD))
        vPh[i] = 0.0; vF[i] = 55.0 + 40.0 * r; vAmp[i] = amp; vDel[i] = (rng.next() * 400.0).toInt()
    }

    /** Alivio do turbo: relief 0 = so chiado (BOV), 1 = so flutter, 2 = misto. */
    fun trigBov(b: Double, relief: Int) {
        if (relief != 1) bovEnv = max(bovEnv, b)
        if (relief != 0) flut = max(flut, b)
    }

    /** Gera n amostras estereo intercaladas (L,R) em out[0 .. 2n). */
    fun process(P: DspParams, out: FloatArray, n: Int) {
        val R = rng
        if (P.cyl != cyl) setCyl(P.cyl)
        val cy = cyl; val nS = nSlots
        val mix = P.mix; val vol = P.vol; val eng = P.engineOn
        sgS += (P.sg - sgS) * 0.2; aggS += (P.agg - aggS) * 0.2
        val sg = sgS; val agg = aggS
        val tauD = (1.9 + 0.9 * min(1.6, sg)) * 1e-3
        val tauA = 0.30e-3
        val aA = 1.0 - exp(-1.0 / (tauA * SRD)); val aD = 1.0 - exp(-1.0 / (tauD * SRD)); val pNorm = tauD * SRD
        val open = ((agg - 1.0) / 3.0).coerceIn(0.0, 1.0)
        val turboMuff = if (P.asp == 1) 0.8 else 1.0
        val fcBase = (560.0 + 1800.0 * open) * turboMuff * (0.92 + 0.12 * min(1.5, sg))
        val gComb = 0.36 + 0.16 * open
        val aDmp = pole(1400.0 + 1200.0 * open); val aSub = pole(90.0)
        val m0 = mix[0]; val m1 = mix[1]; val m2 = mix[2]; val m3 = mix[3]; val m4 = mix[4]; val m5 = mix[5]
        val mTb = mix[7]; val mWh = mix[8]; val mRu = mix[9]; val mRe = mix[10]; val mPop = mix[6]
        val width = P.width
        val bassK = when (cy) { 3 -> 0.95; 4 -> 1.0; 6 -> 1.9; 8 -> 2.5; 10 -> 2.9; 12 -> 3.3; else -> 1.0 }
        for (i in 0 until 4) {
            lf[i] += n * LF_RATE[i] / SRD
            if (lf[i] >= 1.0) lf[i] -= 1.0
        }
        val l1 = sin(TAU * lf[0]); val l2 = sin(TAU * lf[1]); val l3 = sin(TAU * lf[2]); val l4 = sin(TAU * lf[3])
        for (i in 0 until cy) {
            slotTarget[i] = (slotTarget[i] + R.sym() * 0.012 + (1.0 - slotTarget[i]) * 0.004).coerceIn(0.8, 1.2)
            slotGain[i] += (slotTarget[i] - slotGain[i]) * 0.1
        }

        val aThrUp = pole(7.0); val aThrDn = pole(2.2); val aOv = pole(1.8)
        val rpmTarget = P.rpm
        val flowBase = max(0.15, rpmS / 6000.0).pow(0.8)
        val boost = if (P.asp == 1) P.boost else 0.0
        val sc = if (P.asp == 2) P.sc else 0.0
        val aSh = pole(150.0)

        for (s in 0 until n) {
            rpmS += (rpmTarget - rpmS) * (if (rpmTarget > rpmS) 0.00055 else 0.00022)
            val thrIn = P.thr
            thrS += (thrIn - thrS) * (if (thrIn > thrS) aThrUp else aThrDn)
            ovS += (P.over - ovS) * aOv
            val thr = thrS; val ov = ovS
            val idleW = ((1150.0 - rpmS) / 450.0).coerceIn(0.0, 1.0)
            if ((idleCnt++ and 4095) == 0) idleTgt = R.sym()
            idleHunt += (idleTgt - idleHunt) * 0.0006
            val rpm = rpmS * (1.0 + 0.0025 * l1 + 0.0015 * l2 + 0.001 * l4) + idleHunt * (5.0 + 20.0 * idleW)
            val dph = rpm / 120.0 / SRD
            phase += dph; if (phase >= 1.0) phase -= 1.0

            val hd = head and 63; head++
            val injA = bankA.ring[hd]; val injB = bankB.ring[hd]; val injI = iRing[hd]
            bankA.ring[hd] = 0.0; bankB.ring[hd] = 0.0; iRing[hd] = 0.0
            val fp = phase * nS
            val slot = min(nS - 1, fp.toInt())
            if (slot != lastSlot) {
                lastSlot = slot
                val frac = ((fp - slot) / (dph * nS)).coerceIn(0.0, 1.0)
                val ccv = 0.03 + 0.045 * (1.0 - thr) + 0.03 * idleW
                val load = 0.10 + 0.90 * thr
                val burn = 1.0 - 0.85 * ov
                var amp = slotGain[slot] * (1.0 + ccv * R.gauss()) * (0.20 + 0.80 * load.pow(0.85)) * burn * sg * (1.0 + 0.15 * l3 * idleW)
                if (!eng) amp = 0.0
                val jt = 1.0 + max(0.0, 7.0 + 6.0 * R.gauss() * (0.4 + 0.6 * (1.0 - thr)) + 5.0 * idleW * R.gauss())
                val d0 = jt.toInt(); val w1 = frac; val w0 = 1.0 - frac
                val rg = if (nBanks == 1 || slotBank[slot] == 0) bankA.ring else bankB.ring
                rg[(hd + d0) and 63] += amp * w0; rg[(hd + d0 + 1) and 63] += amp * w1
                val ia = slotGain[slot] * (0.12 + 0.88 * thr) * (1.0 + 0.5 * ccv * R.gauss())
                iRing[(hd + d0 + 2) and 63] += ia * w0; iRing[(hd + d0 + 3) and 63] += ia * w1
                raspEnv = min(1.5, raspEnv + thr.pow(1.3) * (0.5 + 0.5 * R.next()) * burn)
                mechEnv = min(1.5, mechEnv + 0.5 + 0.5 * R.next())
            }

            // ---- escapamento ----
            val fcM = fcBase * (0.62 + 0.75 * thr)
            val aM = pole(fcM)
            var exA = 0.0; var exB = 0.0; var bdA = 0.0; var bdB = 0.0; var sub = 0.0
            for (b in 0 until nBanks) {
                val bk = if (b == 0) bankA else bankB
                val x = if (b == 0) injA else injB
                bk.sA += aA * (x - bk.sA); bk.sD += aD * (bk.sA - bk.sD)
                val p = bk.sD * pNorm
                var ri = bk.w - bk.delay; if (ri < 0) ri += 1024
                bk.dmp += aDmp * (bk.buf[ri] - bk.dmp)
                val c = p - gComb * bk.dmp
                bk.buf[bk.w] = c; bk.w = (bk.w + 1) and 1023
                val e = c + 0.60 * bk.m1.run(c) + 0.45 * bk.m2.run(c) + 0.28 * bk.m3.run(c)
                bk.l1 += aM * (e - bk.l1); bk.l2 += aM * (bk.l1 - bk.l2)
                bk.s1 += aSub * (p - bk.s1); bk.s2 += aSub * (bk.s1 - bk.s2)
                bk.b1.run(p)
                if (b == 0) { exA = bk.l2; bdA = bk.b1.lp } else { exB = bk.l2; bdB = bk.b1.lp }
                sub += bk.s2
            }
            val exL: Double; val exR: Double; val bdL: Double; val bdR: Double
            if (nBanks == 1) { exL = exA; exR = exA; bdL = bdA; bdR = bdA }
            else {
                val pa = 0.5 + 0.30 * width
                exL = (exA * pa + exB * (1.0 - pa)) * 1.4; exR = (exB * pa + exA * (1.0 - pa)) * 1.4
                bdL = (bdA * pa + bdB * (1.0 - pa)) * 1.4; bdR = (bdB * pa + bdA * (1.0 - pa)) * 1.4
                sub *= 0.7
            }

            // ---- admissao ----
            iA += A_PI1 * (injI - iA); iD += A_PI2 * (iA - iD)
            val ip = iD * 4.0
            if ((s and 31) == 0) {
                val fcI = 240.0 + 0.10 * rpmS + 520.0 * thr
                iL.set(fcI, 0.9); iR.set(fcI * 1.05, 0.9); aI = pole(fcI * 2.1)
            }
            val nbL = iL.run(R.sym()); val nbR = iR.run(R.sym())
            iL1 += aI * (nbL - iL1); iL2 += aI * (iL1 - iL2)
            iR1 += aI * (nbR - iR1); iR2 += aI * (iR1 - iR2)
            val fl = flowBase * (0.06 + 0.94 * thr.pow(1.25)) * (1.0 - 0.7 * ov) + 0.03 * flowBase * ov
            val iEnv = 0.6 + 1.0 * abs(ip)
            val nM = (iL2 + iR2) * 0.5; val nSd = (iL2 - iR2) * 0.5
            val inL = ip * 0.8 + (nM + width * nSd) * 5.5 * fl * iEnv
            val inR = ip * 0.8 + (nM - width * nSd) * 5.5 * fl * iEnv

            // ---- chiado de combustao e mecanico ----
            raspEnv *= 0.9965; mechEnv *= 0.9972
            val wn = R.sym()
            val rasp = (rasp1.run(wn) * 0.8 + rasp2.run(wn) * 0.5) * raspEnv * (0.3 + 0.7 * open + 0.1 * min(3.0, agg))
            val mechS = mech.run(R.sym()) * mechEnv * min(1.0, rpmS / 5000.0)

            // ---- turbo ----
            nT += (boost - nT) * (if (boost > nT) 0.00045 else 0.00012)
            var tb = 0.0
            if (nT > 0.003 || bovEnv > 0.001 || flut > 0.001) {
                wh1 += (1100.0 + 2100.0 * nT) / SRD; if (wh1 >= 1.0) wh1 -= 1.0
                val whine = sin(TAU * wh1) + 0.15 * sin(TAU * 2.0 * wh1 + 0.6)
                val air = tw.run(wn)
                tb = (whine * 0.030 * mWh + air * 0.06 * mRu) * nT * nT * (0.35 + 0.65 * thr)
                if (bovEnv > 0.001) {
                    bovEnv *= 0.99992; bovSvf.set(2000.0 + 1400.0 * bovEnv, 1.1)
                    tb += bovSvf.run(wn) * bovEnv * 0.9 * mRe
                }
                if (flut > 0.001) {
                    flut *= 0.99996; flutPh += (7.0 + 9.0 * flut) / SRD; if (flutPh >= 1.0) flutPh -= 1.0
                    val g = 0.5 + 0.5 * sin(TAU * flutPh)
                    tb += flutSvf.run(wn) * g * g * g * flut * 6.0 * mRe
                }
                tb *= mTb
            }
            // ---- supercharger ----
            var scs = 0.0
            if (sc > 0.01 || scA > 0.001) {
                scA += (sc - scA) * 0.0006
                scPh += (300.0 + rpmS * 0.15) / SRD; if (scPh >= 1.0) scPh -= 1.0
                val w = sin(TAU * scPh) + 0.5 * sin(TAU * 2.0 * scPh) + 0.25 * sin(TAU * 3.0 * scPh)
                scLP += 0.22 * (w - scLP)
                scs = scLP * 0.045 * scA * mTb * (0.5 + 0.5 * thr)
            }
            // ---- estouros ----
            var pp = 0.0
            for (v in 0 until nV) {
                if (vDel[v] > 0) { vDel[v]--; continue }
                if (vEnv[v] > 0.002 || vTh[v] > 0.002) {
                    val bandV = vSvf[v].run(R.sym()) * vEnv[v]
                    vPh[v] += vF[v] * (0.35 + 0.65 * vTh[v] / (vAmp[v] + 1e-6)) / SRD
                    pp += (bandV * 1.4 + sin(TAU * vPh[v]) * vTh[v] * 0.9) * mPop
                    vEnv[v] *= vDec[v]; vTh[v] *= vThD[v]
                }
            }

            // ---- mistura ----
            val sh = 1.0 + 0.5 * (sg - 1.0)
            var mL = 0.0; var mR = 0.0
            if (eng) {
                val subG = m0 * sub * CAL_SUB * sh * bassK
                val bodyG = CAL_BODY * (0.5 + 0.5 * bassK)
                val exG = CAL_EXH * (0.5 + 0.5 * thr)
                mL = subG + m1 * bdL * bodyG + m2 * exL * exG + m3 * inL * CAL_INTAKE + m5 * rasp * CAL_RASP
                mR = subG + m1 * bdR * bodyG + m2 * exR * exG + m3 * inR * CAL_INTAKE + m5 * rasp * CAL_RASP
                val mc = m4 * mechS * CAL_MECH; mL += mc; mR += mc
            }
            mL += tb + scs + pp; mR += tb + scs + pp
            hpL.run(mL); hpR.run(mR)
            val yL = hpL.hp; val yR = hpR.hp
            shL += aSh * (yL - shL); shR += aSh * (yR - shR)
            val sg2 = 0.45 + 0.5 * min(1.5, max(0.0, sg - 0.8))
            var oL = yL + shL * sg2; var oR = yR + shR * sg2
            if (P.mono) { val mm = (oL + oR) * 0.5; oL = mm; oR = mm }
            out[2 * s] = tanh(oL * vol * CAL_MASTER).toFloat()
            out[2 * s + 1] = tanh(oR * vol * CAL_MASTER).toFloat()
        }
    }

    companion object {
        private val LF_RATE = doubleArrayOf(0.31, 0.83, 1.9, 0.57)
        private val A_PI1 = pole(380.0)
        private val A_PI2 = pole(150.0)
        private const val CAL_MASTER = 0.55
        private const val CAL_SUB = 1.1
        private const val CAL_BODY = 0.9
        private const val CAL_EXH = 0.75
        private const val CAL_INTAKE = 0.55
        private const val CAL_RASP = 0.22
        private const val CAL_MECH = 0.05
    }
}
