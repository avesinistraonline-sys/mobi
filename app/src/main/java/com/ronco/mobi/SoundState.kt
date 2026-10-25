package com.ronco.mobi

import java.util.concurrent.ConcurrentLinkedQueue

/** Estado compartilhado entre a tela, o servico, o modelo do motor e o sintetizador. */
object SoundState {
    // ---------- saidas do modelo (lidas pelo sintetizador) ----------
    @Volatile var rpm = 850f       // rpm do motor (agora e o rpm REAL do Mobi: velocidade / relacao da marcha)
    @Volatile var thr = 0f         // carga / pedal estimado (0..1)
    @Volatile var over = 0f        // 1 = desacelerando com o pe fora (corte de combustivel)
    @Volatile var boost = 0f
    @Volatile var scAmp = 0f
    @Volatile var bov = 0f
    @Volatile var sg = 1f          // "tamanho" do motor (potencia): mais grave e mais cheio
    @Volatile var agg = 1f         // relacao potencia/peso (1 = Mobi original): mais agressivo e aberto
    /** Eventos de estouro: [tipo (0 estalo, 1 estouro grave, 2 burble), intensidade] */
    val popEvents = ConcurrentLinkedQueue<FloatArray>()

    // ---------- configuracoes ----------
    @Volatile var volume = 0.7f
    @Volatile var cylinders = 4        // 3, 4, 6, 8, 10 ou 12
    @Volatile var aspiration = 0       // 0 aspirado, 1 turbo, 2 supercharger
    @Volatile var powerCv = 105f       // potencia do carro virtual (Mobi original: 75 cv)
    @Volatile var massKg = 1000f       // peso do carro virtual (Mobi original: ~1000 kg com motorista)
    @Volatile var drive = 1            // estilo de conducao: 0 economico, 1 normal, 2 esportivo (muda quando o motorista troca de marcha)
    @Volatile var idleRpm = 850f       // marcha lenta do Mobi
    @Volatile var redRpm = 6300f       // corte do Mobi
    @Volatile var pops = false         // estouros (o Mobi original nao tem)
    @Volatile var engineOn = true      // false = so o som da aspiracao (turbo/supercharger)
    @Volatile var relief = 2           // 0 BOV (chiado), 1 flutter, 2 misto
    @Volatile var width = 0.25f        // largura estereo (0 = mono)
    @Volatile var monoOut = false      // true = mesmo sinal nos dois canais
    @Volatile var chanTest = 0         // 1 = inicia o teste de canais (esquerdo, direito, ambos)

    /** Camadas: Graves, Corpo, Escape, Admissao, Mecanico, Rasgo, Estouros, Turbo geral, Turbo assobio, Turbo sopro, Turbo alivio/flutter (1.0 = normal) */
    val mix = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)

    // ---------- jeito de dirigir (aprendido automaticamente) ----------
    @Volatile var learn = true         // aprende o jeito do motorista (aceleracao, frenagem)
    @Volatile var aggr = 0.5f          // 0 = muito suave, 1 = pe fundo
    @Volatile var ebrake = 0.5f        // 0 = freia no freio, 1 = usa muito o freio motor / desacelera suave
    @Volatile var grade = 0f           // inclinacao estimada da pista (+ subida, - descida), fracao
    @Volatile var testGrade = -99f     // > -99 forca a inclinacao (so no modo teste)

    // ---------- informacoes para a tela ----------
    @Volatile var running = false
    @Volatile var gpsFix = false
    @Volatile var kmh = 0f
    @Volatile var gear = 0             // 0 = ponto morto / parado
    @Volatile var testKmh = -1f        // >= 0 ativa o modo teste (ignora o GPS)

    val turbo get() = aspiration == 1
    val sc get() = aspiration == 2
}
