package com.ronco.mobi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val S = SoundState
    private lateinit var big: TextView
    private lateinit var detail: TextView
    private lateinit var toggle: Button
    private lateinit var testSwitch: Switch
    private lateinit var testSeek: SeekBar
    private lateinit var testLabel: TextView
    private lateinit var gearBox: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateUi(); handler.postDelayed(this, 200) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, dp(16), 0, dp(4)) }
    private fun title(t: String) = TextView(this).apply { text = t; textSize = 19f; setPadding(0, dp(28), 0, dp(4)) }
    private fun ord(i: Int) = "${i}ª"

    private fun seek(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = value.coerceIn(0, max)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { save() }
        })
    }

    /** Titulo com o valor atual + barra (de [min] a [max]), adicionados ao [parent]. */
    private fun row(parent: LinearLayout, text: (Int) -> String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
        val tv = TextView(this).apply { this.text = text(value); textSize = 15f; setPadding(0, dp(12), 0, dp(2)) }
        parent.addView(tv)
        parent.addView(seek(max - min, value - min) { tv.text = text(it + min); onChange(it + min) })
    }

    private fun spinner(items: List<String>, sel: Int, onSel: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onSel(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ================= PERFIS (a logica mora em Profiles.kt, compartilhada com o Android Auto) =================

    private val profileNames get() = Profiles.profileNames
    private var curId: String
        get() = Profiles.curId
        set(v) { Profiles.curId = v }
    private var shownVersion = 0

    private fun defaults(slot: Int) = Profiles.defaults(slot)
    private fun prefs() = Profiles.prefs()
    private fun savedNames() = Profiles.savedNames()
    private fun setSavedNames(l: List<String>) = Profiles.setSavedNames(l)
    private fun prefix(id: String) = Profiles.prefix(id)
    private fun idLabel(id: String) = Profiles.idLabel(id)
    private fun saveProfile(id: String) = Profiles.saveProfile(id)
    private fun save() = Profiles.save()
    private fun loadProfile(id: String) = Profiles.loadProfile(id)

    private fun askSaveNew() {
        val et = EditText(this).apply { hint = "Nome do perfil"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Salvar o perfil atual como...").setView(et)
            .setPositiveButton("Salvar") { _, _ ->
                val name = et.text.toString().trim().replace("\n", " ").take(40)
                if (name.isNotEmpty()) {
                    val list = savedNames().toMutableList()
                    if (!list.contains(name)) list.add(name)
                    setSavedNames(list)
                    saveProfile(curId)
                    val id = "u:$name"
                    saveProfile(id)
                    curId = id
                    prefs().edit().putString("profile_id", id).apply()
                    recreate()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun confirmDelete() {
        val id = curId
        AlertDialog.Builder(this).setTitle("Excluir \"${id.substring(2)}\"?")
            .setPositiveButton("Excluir") { _, _ ->
                val e = prefs().edit()
                prefs().all.keys.filter { it.startsWith(prefix(id) + "_") }.forEach { e.remove(it) }
                e.apply()
                setSavedNames(savedNames().filter { it != id.substring(2) })
                curId = "b0"
                prefs().edit().putString("profile_id", curId).apply()
                loadProfile(curId)
                recreate()
            }.setNegativeButton("Cancelar", null).show()
    }

    // ================= TELA =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Profiles.attach(this)
        shownVersion = Profiles.version

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        root.addView(TextView(this).apply { text = "Ronco Mobi"; textSize = 26f })

        big = TextView(this).apply { textSize = 44f; gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(4)) }
        detail = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        root.addView(big); root.addView(detail)

        toggle = Button(this).apply {
            setOnClickListener {
                if (S.running) stopService(Intent(this@MainActivity, EngineSoundService::class.java))
                else startEngine()
            }
        }
        root.addView(toggle)

        root.addView(title("Perfil sonoro"))
        val ids = Profiles.allIds()
        root.addView(spinner(ids.map { idLabel(it) }, ids.indexOf(curId).coerceAtLeast(0)) { pos ->
            val id = ids[pos]
            if (id != curId) {
                saveProfile(curId)
                curId = id
                prefs().edit().putString("profile_id", id).apply()
                S.testKmh = -1f
                loadProfile(id)
                recreate()
            }
        })
        root.addView(Button(this).apply { text = "Salvar como novo perfil…"; setOnClickListener { askSaveNew() } })
        if (curId.startsWith("u:")) {
            root.addView(Button(this).apply { text = "Excluir este perfil"; setOnClickListener { confirmDelete() } })
        }
        root.addView(Button(this).apply {
            text = "Restaurar padrão deste perfil"
            setOnClickListener { defaults(Profiles.modeOf(curId).coerceIn(0, 2)); Profiles.loadOutput(); save(); recreate() }
        })

        root.addView(title("Carro virtual"))
        root.addView(note("O rpm é sempre o do Mobi de verdade (marcha lenta ~850, corte ~6300, câmbio de 5 marchas: 60 km/h em 5ª = ~2200 rpm). Potência, peso e motor mudam só o caráter do som: mais potência e menos peso = som maior, mais grave e mais agressivo."))
        row(root, { "Potência: $it cv  (Mobi original: 75)" }, 50, 700, S.powerCv.toInt()) { S.powerCv = it.toFloat() }
        row(root, { "Peso: $it kg  (Mobi original: ~1000)" }, 600, 2500, S.massKg.toInt()) { S.massKg = it.toFloat() }
        root.addView(label("Tipo de motor"))
        val cyls = listOf(3, 4, 6, 8, 10, 12)
        root.addView(spinner(listOf("3 cilindros", "4 cilindros (Mobi)", "V6", "V8", "V10", "V12"), cyls.indexOf(S.cylinders).coerceAtLeast(0)) {
            S.cylinders = cyls[it]; save()
        })
        root.addView(label("Aspiração"))
        root.addView(spinner(listOf("Aspirado", "Turbo", "Supercharger"), S.aspiration) { S.aspiration = it; save() })
        root.addView(label("Estilo de condução (quando o motorista troca de marcha)"))
        root.addView(spinner(listOf("Econômico (troca cedo)", "Normal", "Esportivo (troca tarde)"), S.drive) { S.drive = it; save() })
        row(root, { "Marcha lenta: $it rpm" }, 600, 1200, S.idleRpm.toInt()) { S.idleRpm = it.toFloat() }
        row(root, { "Corte (rpm máximo): $it rpm" }, 5000, 7500, S.redRpm.toInt()) { S.redRpm = it.toFloat() }

        root.addView(title("Jeito de dirigir"))
        root.addView(note("O app aprende com o GPS: se você acelera suave ou pisa fundo, se desacelera devagar (freio motor) ou freia forte. Também usa a altitude do GPS para notar subidas e descidas (segura a marcha na subida, freio motor na descida)."))
        root.addView(Switch(this).apply {
            text = "Aprender meu jeito de dirigir  "
            isChecked = S.learn
            setOnCheckedChangeListener { _, c -> S.learn = c; Profiles.saveDriver() }
        })
        val drvInfo = note("")
        fun showDrv() { drvInfo.text = "Aprendido: pé ${(S.aggr * 100).toInt()}% (0 = suave, 100 = fundo) · freio motor ${(S.ebrake * 100).toInt()}% · pista ${"%.1f".format(S.grade * 100)}%" }
        showDrv()
        root.addView(drvInfo)
        root.addView(Button(this).apply { text = "Reiniciar aprendizado"; setOnClickListener { S.aggr = 0.5f; S.ebrake = 0.5f; Profiles.saveDriver(); showDrv() } })

        root.addView(title("Som"))
        row(root, { "Volume geral: $it%" }, 0, 100, (S.volume * 100).toInt()) { S.volume = it / 100f }
        root.addView(Switch(this).apply {
            text = "Estouros no escape  "
            isChecked = S.pops
            setOnCheckedChangeListener { _, c -> S.pops = c; save() }
        })
        root.addView(Switch(this).apply {
            text = "Só o som da aspiração (sem motor)  "
            isChecked = !S.engineOn
            setOnCheckedChangeListener { _, c -> S.engineOn = !c; save() }
        })
        root.addView(label("Alívio do turbo ao tirar o pé"))
        root.addView(spinner(listOf("BOV (chiado)", "Flutter (tu-tu-tu)", "Misto"), S.relief) { S.relief = it; save() })
        row(root, { "Largura estéreo: $it%" }, 0, 100, (S.width * 100).toInt()) { S.width = it / 100f }

        root.addView(title("Saída de áudio (carro / Android Auto)"))
        root.addView(note("Se no carro o som sair só de um alto-falante, use \"Mono duplicado\" e rode o teste de canais."))
        root.addView(spinner(listOf("Estéreo", "Mono duplicado (mesmo som nos dois lados)"), if (S.monoOut) 1 else 0) {
            S.monoOut = it == 1; Profiles.saveOutput()
        })
        root.addView(Button(this).apply {
            text = "Testar canais (esquerdo, direito, ambos)"
            setOnClickListener { S.chanTest = 1; if (!S.running) startEngine() }
        })

        root.addView(title("Camadas do som"))
        val names = listOf("Graves (ronco)", "Corpo do motor", "Escape", "Admissão (rugido)", "Mecânico / válvulas", "Rasgo (combustão)", "Estouros", "Turbo / supercharger (geral)", "Turbo: assobio", "Turbo: sopro de ar", "Turbo: alívio e flutter")
        for (i in names.indices) {
            row(root, { "${names[i]}: $it%" }, 0, 100, (S.mix[i] * 50f).toInt().coerceIn(0, 100)) { S.mix[i] = it / 50f }
        }

        root.addView(title("Modo teste (sem sair de casa)"))
        testLabel = TextView(this).apply { text = "Velocidade simulada: 0 km/h" }
        testSwitch = Switch(this).apply {
            text = "Ignorar o GPS e usar a velocidade abaixo  "
            setOnCheckedChangeListener { _, _ -> applyTest() }
        }
        testSeek = seek(160, 0) { applyTest() }
        root.addView(testSwitch); root.addView(testLabel); root.addView(testSeek)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(6), 0, dp(2)) }

    private fun applyTest() {
        val v = testSeek.progress
        testLabel.text = "Velocidade simulada: $v km/h"
        S.testKmh = if (testSwitch.isChecked) v.toFloat() else -1f
    }

    private fun startEngine() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return }
        startForegroundService(Intent(this, EngineSoundService::class.java))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, EngineSoundService::class.java))
        }
    }

    private fun updateUi() {
        val on = S.running
        toggle.text = if (on) "PARAR" else "INICIAR"
        if (!on) { big.text = "Desligado"; detail.text = ""; return }
        big.text = "${S.kmh.toInt()} km/h"
        val gps = when {
            S.testKmh >= 0f -> "modo teste"
            S.gpsFix -> "GPS ok"
            else -> "aguardando GPS..."
        }
        val g = if (S.gear == 0) "N" else "${S.gear}ª"
        detail.text = "$g marcha  •  ${S.rpm.toInt()} rpm  •  $gps"
    }

    override fun onResume() {
        super.onResume()
        if (Profiles.version != shownVersion) { shownVersion = Profiles.version; recreate(); return }
        handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh); save(); super.onPause() }
}
