package com.ronco.mobi

import android.content.Context
import android.content.SharedPreferences

/**
 * Perfis e ajustes compartilhados entre a tela do celular (MainActivity) e as telas do Android Auto.
 *
 * O rpm e SEMPRE o do Mobi (faixa e cambio reais, ver EngineModel). Um perfil guarda so o que muda o CARATER do som:
 * tipo de motor, aspiracao, potencia, peso, estilo de conducao, estouros, volume e camadas.
 */
object Profiles {
    private val S = SoundState
    private lateinit var app: Context
    private var loaded = false

    /** "b0..b2" = perfis base, "u:Nome" = perfil salvo por voce. */
    @Volatile var curId = "b0"

    /** Sobe a cada ajuste feito fora do celular (ex.: pelo carro), para a tela do celular se atualizar. */
    @Volatile var version = 0

    val profileNames = listOf("Mobi apimentado (recomendado)", "Mobi original (75 cv)", "Mobi V8 (brincadeira)")

    /** Garante que o processo tem os ajustes salvos carregados (o carro pode abrir sem o app do celular). */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        app = ctx.applicationContext
        migrate()
        curId = prefs().getString("profile_id", "b0") ?: "b0"
        if (curId.startsWith("u:") && !savedNames().contains(curId.substring(2))) curId = "b0"
        loadProfile(curId)
        loaded = true
    }

    /** Usado pelo MainActivity: registra o contexto e recarrega o perfil atual. */
    fun attach(ctx: Context) {
        app = ctx.applicationContext
        if (!loaded) { ensureLoaded(ctx); return }
        curId = prefs().getString("profile_id", curId) ?: curId
        if (curId.startsWith("u:") && !savedNames().contains(curId.substring(2))) curId = "b0"
        loadProfile(curId)
    }

    /** Versao 2 do app: o modelo de rpm mudou por completo, entao os perfis antigos (com rpm/cambio inventados) sao descartados. */
    private fun migrate() {
        val p = prefs()
        if (p.getInt("schema", 1) >= 2) return
        p.edit().clear().putInt("schema", 2).apply()
    }

    fun prefs(): SharedPreferences = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun prefix(id: String) = if (id.startsWith("b")) "p${id.substring(1)}" else "u_${id.substring(2)}"
    fun savedNames(): List<String> =
        (prefs().getString("names", "") ?: "").split("\n").filter { it.isNotBlank() }
    fun setSavedNames(l: List<String>) { prefs().edit().putString("names", l.joinToString("\n")).apply() }
    fun modeOf(id: String) =
        if (id.startsWith("b")) id.substring(1).toIntOrNull() ?: 0 else prefs().getInt("${prefix(id)}_mode", 0)
    fun idLabel(id: String): String =
        if (id.startsWith("b")) profileNames[modeOf(id).coerceIn(0, 2)] else "★ ${id.substring(2)}"

    /** Todos os perfis disponiveis (3 base + os seus). */
    fun allIds(): List<String> = listOf("b0", "b1", "b2") + savedNames().map { "u:$it" }

    // ---------- jeito de dirigir aprendido (vale para o motorista, nao por perfil) ----------

    fun loadDriver() {
        val q = prefs()
        S.learn = q.getBoolean("drv_learn", true)
        S.aggr = q.getFloat("drv_aggr", 0.5f); S.ebrake = q.getFloat("drv_eb", 0.5f)
    }

    fun saveDriver() {
        prefs().edit().putBoolean("drv_learn", S.learn).putFloat("drv_aggr", S.aggr).putFloat("drv_eb", S.ebrake).apply()
    }

    // ---------- saida de audio (vale para o aparelho/carro, nao por perfil) ----------

    fun loadOutput() {
        S.monoOut = prefs().getBoolean("out_mono", false)
    }

    fun saveOutput() {
        prefs().edit().putBoolean("out_mono", S.monoOut).apply()
    }

    // ---------- perfis ----------

    /** Valores de fabrica de cada perfil base (slot 0, 1 ou 2). */
    fun defaults(slot: Int) {
        S.mix.fill(1f)
        S.idleRpm = 850f; S.redRpm = 6300f
        S.drive = 1; S.relief = 2; S.width = 0.25f; S.engineOn = true; S.volume = 0.7f
        when (slot) {
            1 -> { S.cylinders = 4; S.aspiration = 0; S.powerCv = 75f; S.massKg = 1000f; S.pops = false }     // Mobi como ele e
            2 -> { S.cylinders = 8; S.aspiration = 0; S.powerCv = 380f; S.massKg = 1150f; S.pops = true }     // V8 de brincadeira
            else -> { S.cylinders = 4; S.aspiration = 1; S.powerCv = 105f; S.massKg = 1000f; S.pops = false }  // um pouco mais potente
        }
    }

    fun saveProfile(id: String) {
        val k = prefix(id)
        prefs().edit()
            .putInt("${k}_v2", 1)
            .putInt("${k}_mode", baseMode)
            .putString("${k}_mix", S.mix.joinToString(","))
            .putFloat("${k}_idle", S.idleRpm).putFloat("${k}_red", S.redRpm)
            .putInt("${k}_cyl", S.cylinders).putInt("${k}_asp", S.aspiration)
            .putFloat("${k}_cv", S.powerCv).putFloat("${k}_kg", S.massKg).putInt("${k}_drv", S.drive)
            .putBoolean("${k}_eng", S.engineOn).putInt("${k}_rel", S.relief).putFloat("${k}_wid", S.width)
            .putBoolean("${k}_pops", S.pops).putFloat("${k}_vol", S.volume)
            .apply()
    }

    fun save() = saveProfile(curId)

    /** Salva e avisa a tela do celular (se aberta) que algo mudou por fora. */
    fun saveFromCar() { save(); version++ }

    private fun loadList(src: String?, dst: FloatArray) {
        src?.split(",")?.forEachIndexed { i, s -> if (i < dst.size) s.toFloatOrNull()?.let { dst[i] = it } }
    }

    fun loadProfile(id: String) {
        val mode = modeOf(id).coerceIn(0, 2)
        baseMode = mode
        defaults(mode)
        val p = prefs()
        val k = prefix(id)
        if (p.contains("${k}_v2")) {
            loadList(p.getString("${k}_mix", null), S.mix)
            S.idleRpm = p.getFloat("${k}_idle", 850f); S.redRpm = p.getFloat("${k}_red", 6300f)
            S.cylinders = p.getInt("${k}_cyl", 4); S.aspiration = p.getInt("${k}_asp", 0)
            S.powerCv = p.getFloat("${k}_cv", 105f); S.massKg = p.getFloat("${k}_kg", 1000f); S.drive = p.getInt("${k}_drv", 1)
            S.engineOn = p.getBoolean("${k}_eng", true); S.relief = p.getInt("${k}_rel", 2); S.width = p.getFloat("${k}_wid", 0.25f)
            S.pops = p.getBoolean("${k}_pops", false); S.volume = p.getFloat("${k}_vol", 0.7f)
        }
        loadOutput()
        loadDriver()
    }

    /** Troca de perfil (usado pelo carro): salva o atual, carrega o novo. */
    fun selectProfile(id: String) {
        if (id == curId) return
        saveProfile(curId)
        curId = id
        prefs().edit().putString("profile_id", id).apply()
        S.testKmh = -1f
        loadProfile(id)
        version++
    }

    fun setAspiration(a: Int) { S.aspiration = a }

    /** Perfil base (0..2) que serve de ponto de partida do perfil atual. */
    private var baseMode = 0
}
