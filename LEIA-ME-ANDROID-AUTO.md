# Ronco Mobi v2

## O que mudou
- **rpm real do Mobi**: o rpm agora é a velocidade dividida pela relação da marcha (5 marchas). Marcha lenta 850, corte 6300; 60 km/h em 5ª = ~2200 rpm. Acabou o "7000 rpm a 10 km/h".
- **Peso, potência e tipo de motor** mudam só o caráter do som (tamanho, graves, agressividade), nunca a faixa de rpm. Padrão: "Mobi apimentado" (105 cv).
- **Som novo**: pulsos de combustão por cilindro (irregulares), escapamento ressonante, admissão, ronco grave do bloco, timbre que abre com a carga, motor mais calmo na desaceleração.
- **Trocas de marcha** nas suas velocidades (~10, 30, 40 e 52 km/h).
- **Aprende seu jeito de dirigir** (GPS): pé suave/fundo muda onde troca e quão rápido o pedal estimado reage; desaceleração suave indica freio motor. A altitude do GPS dá a inclinação da pista: na subida segura a marcha, na descida entra freio motor. Liga/desliga e reinicia em Ajustes. O aprendizado fica salvo.
- Perfis antigos foram descartados (eram baseados no rpm inventado).
- `ferramentas/ronco-teste.html`: bancada para ouvir o som no navegador (mesmo algoritmo do app).

## Android Auto
A tela do carro não aparecia porque a barra de ações do NavigationTemplate tinha 4 botões com texto, e o Android Auto aceita poucos títulos ali. Agora são 4 ícones (ligar/parar, ajustes, vol −, vol +). Se algo falhar ao montar a tela, o carro mostra a mensagem de erro em vez de fechar.
Também baixei o nível mínimo da API do carro (4 → 2) e subi a versão (2.0).

### Se ainda não aparecer
1. Desinstale o app antigo e instale o novo APK.
2. Abra o app uma vez no celular e conceda a localização.
3. Android Auto > toque 10x em **Versão** > ⋮ > Configurações do desenvolvedor > **Fontes desconhecidas**.
4. Android Auto > Personalizar o launcher > marque Ronco Mobi. Desconecte e reconecte o cabo.
5. Se aparecer uma mensagem de erro na tela do carro, me envie o texto.

### Som só no alto-falante esquerdo
O som novo é quase mono (centro), então mesmo assim sai dos dois lados. Há "Testar canais" e "Mono duplicado" em Ajustes.

## Na tela do carro
Ícones: ligar/parar, ajustes, volume −, volume +. Em Ajustes: carro virtual (potência, peso, motor, aspiração, estilo de condução, estouros), camadas, estéreo, perfis, rpm e modo teste. Números ajustam com − e +; "Passo ×N" aumenta o toque.
