# Resultados dos testes e limitações

Data: 2026-10-16 · Versão do app: 0.1.0-mvp (versionCode 1).

Legenda: ✅ executado e aprovado · ⚠️ executado com ressalva · ⏳ pendente (depende de hardware) · ❌ reprovado.

## Teste A — Ambiente de desenvolvimento

| Item | Resultado | Evidência / como reproduzir |
| --- | --- | --- |
| Compilação (AGP 8.13.2, Kotlin 2.2.21, compileSdk/targetSdk 35, minSdk 26) | ✅ | `./gradlew assembleDebug` |
| Lint Android | ✅ 0 erros (avisos cosméticos: i18n/KTX/versões) | `./gradlew lintDebug` |
| Testes unitários | ✅ **147 testes** executados, 0 falhas (+ 4 testes de rede ao vivo, opcionais) | `./gradlew testDebugUnitTest` |
| Protocolo = referência HarmonyOS | ✅ vetores de ouro + vetores gerados executando o TypeScript original (`tools/harmony-vectors.mjs`) | `HarmonyCrossTest`, `ProtocolTest` |
| Referência HarmonyOS em si | ✅ 48 testes do `harmony-sdk` (protocolo, autenticação, voz) passam neste ambiente | `node --test harmony-sdk/tests/{protocol-codec,auth-calculations,guest-exchange,business-commands,version-exchange,voice-session,launcher-status}.test.mjs` |
| Sessão BLE completa contra óculos emulados | ✅ handshake em 2 links, bond após prova, status/bateria, conta vinculada recusada, prova falsa recusada, troca de identidade bloqueada, reconexão automática, timeout | `GlassesSessionTest` |
| Sem OTA | ✅ negócio 9 recusado; envelope no negócio 18 recusado; arquivo só com a trava do teleprompter e só informação/pedaço/cancelar; nenhuma string de OTA/firmware no DEX | `outboundPolicyAndDelivery`, `fileChannelOnlyUnderLeaseAndSplitAcrossWrites`, `outboundGateOnlyInfoChunkCancel`; `strings classes*.dex \| grep -ci fota` = 0 |
| Decodificador Opus (microfone dos óculos) | ✅ SNR **205 dB** contra libopus 1.5.2 do repositório (praticamente bit-exato) | `OpusAndEndpointTest` + `tools/opus_reference.c` |
| Rodada de voz (negócio 13) | ✅ sequência rc1 → áudio → rc2 → transcrição final → blocos ≤ 24 → EOF → saída, com áudio Opus sintético; sem áudio / só silêncio / “pode fechar” / saída pelos óculos | `VoiceAndLensTest` |
| Display (negócio 19) | ✅ abrir → ack → páginas ≤ 90 → fechar; ausência de ack tratada | `VoiceAndLensTest` |
| Provedores (formato) | ✅ OpenAI, Gemini, Anthropic, compatível: URL, cabeçalhos, corpo, parsing, truncamento, erros 401/429/503 | `ProviderTest` |
| Provedores (rede real) | ✅ endpoints oficiais alcançados via TLS; chave inválida → OpenAI HTTP 401, Gemini HTTP 400 “API key not valid”, Anthropic HTTP 401 — mensagens pt-BR corretas | `RUN_LIVE_PROVIDER_TESTS=1 ./gradlew testDebugUnitTest --tests '*LiveProviderTest*'` |
| Pergunta real em pt-BR com chave válida | ⏳ sem credenciais neste ambiente — rode o teste acima com `OPENAI_API_KEY`/`GEMINI_API_KEY`/`ANTHROPIC_API_KEY` | `LiveProviderTest` |
| Tempo máximo de resposta | ✅ cancelamento em 5 s com servidor lento | `clientEnforcesTimeout` |
| Segurança das credenciais | ✅ chave nunca no log/descrição da requisição/erro; redator cobre `Bearer`, `sk-`, `AIza`, `x-api-key`, `?key=`, MAC | `ProviderTest`, `DisplayTextTest.redactorMasksSecrets` |

Bugs encontrados pelos testes e corrigidos antes da entrega: (1) transcrição parcial atrasada podia sobrescrever a resposta
nas lentes (modo microfone do telefone); (2) o prazo da sonda de versão podia derrubar a conexão na pausa antes da
autenticação quando a resposta chegava perto de 5 s (teste de regressão `slowButInTimeRepliesStillConnect` falha sem a correção);
(3) constantes de status BLE do Android 13+ (`BluetoothStatusCodes`), apontadas pelo lint.

### Memória e assistente (0.4.0)

| Teste | Resultado | Onde |
| --- | --- | --- |
| Conversa → pessoa com nome por contexto, fala renomeada (e reindexada), compromisso com data absoluta; baixa confiança descartada; conversa inalterada não é reanalisada | ✅ | `MemoryTest.conversationBecomesPersonNamedSpeakerAndDatedCommitment` |
| Pendência repetida vira atualização (novo prazo), conclusão por evidência, descartada não volta, id inexistente ignorado | ✅ | `duplicatesMergeCompletionsCloseAndDismissedItemsStayGone` |
| Nome sem evidência ou com confiança < 0,7 ignorado; correção do usuário vence o modelo e vale para o trecho todo | ✅ | `namesNeedEvidenceAndUserCorrectionsWin` |
| Política “não guardar” apaga as falas; conversa ainda aberta não é analisada | ✅ | `privacyPoliciesAndOpenConversations` |
| Pendências por pessoa, resumo da manhã (hoje/atrasadas), busca planejada com período e citações, ficha da pessoa, revisão semanal | ✅ | `queriesPendingMorningSearchAndWeekly` |
| Intenções de voz novas sem capturar perguntas comuns | ✅ | `JournalTest.intents` |
| “Me lembra de…” cria pendência com prazo (e guarda o pedido se o modelo falhar), sem duplicar; concluir por número da lista, por palavras ou pedir para escolher | ✅ | `voiceRemindersAndCompletionByNumberOrWords`, `JournalTest.intents` |
| To-do dos óculos: um item por mensagem, só mudanças, descartada vai como feita, conclusão/reabertura vinda dos óculos, título ≤ 60 com data fixa | ✅ | `GlassesTodoTest` |
| Ajustes da tela: lê antes, preserva o campo não alterado, guarda originais e restaura; duplo toque só sobre 0/8; regra de conteúdo recusa outros valores | ✅ | `GlassesSettingsTest` |
| Notificação nativa: chave geral uma vez por conexão, cartão, estado devolvido; texto 21/1 de outros apps ignorado | ✅ | `NativeNotifierTest` |
| Espelhar notificações: só apps escolhidos, sem repetidas/seguidas; Maps: só mudança de manobra/rua, distância a cada 8 s | ✅ | `MirrorPolicyTest` |
| Tempo, fonte/posição do texto, “Hey RayNeo”, tarefa por frase livre | ✅ | `GroupOneTest`, `ProtocolTest` |
| Teleprompter: bytes iguais aos do `FileTransfer.ts` de referência; preparo → arquivo → pronto só com as duas confirmações; comandos esperam a resposta igual; recusa, sucesso prematuro e prazo nunca liberam “iniciar”; saída cancela o arquivo | ✅ | `TeleprompterTest` |
| Painel: pedido com sequência e campo 6 = 1; instalar → reler → demais cartões preservados; recusa para sem repetir; cartão alheio com o mesmo id não é tocado; `simple_mode` trocado mantendo o tipo (número ou booleano); texto ≤ 20 | ✅ | `DashboardTest` |
| Cartões de aceno: aceno = sim, balanço = não, sem resposta remove; resposta repetida ignorada; confirmação 21/35 | ✅ | `SuggestionCardsTest` |
| Efeito real nas lentes (ajustes, To-do, notificações, tempo, teleprompter, painel, acenos) com o firmware 1.0.5.5 | ⏳ hardware | roteiro “Recursos novos da 0.4.0” abaixo |
| Telas Pendências, Pessoas e Semana no Android 11 (emulador), sem erros no log | ✅ | `docs/screenshots/android11-memoria-*.jpg` |
| Qualidade real da extração com o seu modelo e conversas reais | ⏳ uso real | — |

### Diário, insights e gravação ambiente (fases 1–3)

| Item | Resultado | Evidência |
| --- | --- | --- |
| Diário em SQLite real com busca FTS4 sem acentos, retenção, exclusão | ✅ | `JournalTest` (SQLite via JDBC, o mesmo SQL do Android) |
| Insights: blocos → JSON → consolidação; cache incremental (só blocos novos); nova tentativa com JSON inválido | ✅ | `JournalTest` |
| Comandos de voz do diário; perguntas comuns **não** viram consulta ao diário | ✅ | `JournalTest.intents` |
| Fatiamento: união de falas, margens, descarte de ruído, corte na maior pausa, corte forçado com 1 s de sobreposição, só fala cobrada | ✅ | `AmbientPipelineTest` |
| Segmentos de ~5 min cortados numa pausa; bytes originais preservados; relógio por quadro | ✅ | `AmbientPipelineTest` |
| Ogg/Opus gerado pelo app aceito por decodificador independente (ffprobe/ffmpeg com verificação de CRC) | ✅ | `oggOpusIsValidForAnIndependentDecoder` |
| Deepgram: requisição pt-BR com separação de falantes, parsing, erros permanentes × temporários | ✅ formato · ✅ endpoint real (HTTP 401 com chave inválida) | `deepgramRequestAndResponse`, `liveDeepgramEndpointRejectsInvalidKey` |
| “Você” × “Pessoa A”: sensor de voz (VPU) → proximidade → indeterminado | ✅ com dados sintéticos · ⏳ hardware (G3) | `wearerFromVpuThenProximityElseUnknown` |
| Ponta a ponta: áudio → segmento → fatia → transcrição → diário (falantes e horário real) → áudio apagado | ✅ com transcritor simulado | `endToEndFromAudioToJournalWithSpeakersAndRawDeletion` |
| Fila: erro de chave pausa sem perder áudio; nova tentativa retoma | ✅ | `permanentErrorPausesQueueAndRetryResumes` |
| Sondas LifeLog/gravação: sequência de comandos, captura bruta, análise, OFF confirmado, OFF reenviado na reconexão, recusa dos óculos | ✅ contra óculos emulados · ⏳ hardware | `HardwareProbeTest` |
| Lista de envio: só comandos `life_log_guide/switch` no negócio 15/20; nada de apagar, formatar ou atualizar | ✅ | `launcherContentGateOnlyAllowsLifeLogCommands` |

## Teste B — Android

| Item | Resultado | Evidência |
| --- | --- | --- |
| Integridade do APK | ✅ ver tabela abaixo | `sha256sum`, `apksigner verify --print-certs` |
| Permissões | ✅ apenas as listadas em [PERMISSOES_E_RISCOS.md](PERMISSOES_E_RISCOS.md) | `aapt2 dump permissions` |
| Instalação (emulador Android 11 / API 30, x86_64) | ✅ `adb install` → Success | — |
| Execução: 4 telas abrem, sem crash | ✅ | capturas em `docs/screenshots/android11-*.jpg`; logcat sem `FATAL` |
| Configuração: salvar chave | ✅ armazenada só cifrada (`turboio_secrets_v1.xml` contém IV+ciphertext; o texto da chave não aparece) | `run-as … cat shared_prefs/…` |
| Chamada ao provedor de dentro do app | ⚠️ o emulador deste sandbox não tem internet direta; o app mostrou corretamente “Sem internet ou servidor do provedor inacessível” | `android11-diag-ai.jpg` |
| Fases 1–3 no Android 11 (emulador) | ✅ aba Diário abre; “resumo do meu dia” respondido pelo diário e gravado; busca FTS4 no SQLite nativo (3.28) encontra a entrada; tabelas do diário, insights e fila criadas | `sqlite3` no banco do app; logcat sem `FATAL` |
| Gravação ambiente no Android 11 (emulador) | ✅ consentimento → microfone (AudioRecord + Opus) → serviço em primeiro plano ativo → parar → segmento selado (30 s, 0 s de fala: o microfone do emulador é mudo) → 0 envios (nada cobrado) → áudio apagado → serviço encerrado | logcat `AMBIENTE`/`SVC`; `ambient_segments.state = done`; pasta `files/ambient` vazia |
| Android 16 (API 36), emulador | ⚠️ não concluído: sem KVM, o boot por emulação de CPU passou de 50 min e o watchdog reiniciou o `system_server` em ciclo; o app não chegou a ser instalado. **Teste em Android 16 real pendente** (seu Galaxy) | log do emulador |
| Compatibilidade Android 15/16 (análise) | ✅ targetSdk 35 roda no Android 16; edge-to-edge forçado desativado pelo opt-out `windowOptOutEdgeToEdgeEnforcement` (válido para apps com target 35 também no Android 16), mantendo barra de ação, navegação inferior e teclado (`adjustResize`) como no Android 8–14; APIs BLE do Android 13+ (`writeCharacteristic/writeDescriptor` com `BluetoothStatusCodes`) já usadas | `themes.xml`, `AndroidBle.kt` |
| Samsung Galaxy real | ⏳ | roteiro abaixo |

Os emuladores deste ambiente não têm Bluetooth nem aceleração de hardware; servem para instalação/UI, não para BLE.

## Teste C — RayNeo iO (hardware real)

**Status: 🟡 PARCIAL — evidência em hardware real (2026-10-16 e 2026-10-17).** Itens sem evidência
continuam não aprovados.

Ambiente: Samsung Galaxy **SM-S938B, Android 16 (API 36)**, app 0.2.0-diario, RayNeo iO com **firmware original**
(nada foi gravado nos óculos), app oficial não instalado no telefone durante o teste. Evidência: log técnico da aba
Diagnóstico e o pacote `probe-20261016-225342-lifelog.zip` enviados pelo usuário.

| # | Resultado | Evidência |
| --- | --- | --- |
| C1–C2 | ✅ descoberta, autenticação *guest*, bond do sistema e **Conectado** em ~2,3 s | `GATT pronto, MTU=517` → `versão recebida: protocolo=2` → `prova do par verificada` → `INITIALIZING -> READY` |
| C3 | ✅ os óculos responderam ao pedido de estado (6 respostas, bateria 99 %) | `Respostas de status: 6 · bateria 99%`; chaves `battery, hallStatus, micStatus, screenStatus…` |
| C4–C5 | ⏳ sem relato do texto nas lentes | — (o log mostra duas perguntas ao Gemini pela aba Testes, sem envio às lentes) |
| IA | ✅ Gemini respondendo pelo telefone (HTTP 200 em 3,3–3,9 s) | `[AI] HTTP 200 em 3868 ms` |
| C6–C7 | ⏳ voz pelo assistente dos óculos não exercitada | — |
| C8 | 🟡 uma reconexão 3 s após o primeiro READY, depois 30 min estável | `READY -> CONNECTING` às 22:19:52, READY às 22:19:54 |
| C9 | ⏳ | — |

Os campos de versão vieram no *handshake* (`0x10:1,0x11:6,0x12:14,0x13:1,0x14:15,0x15:1,0x18:15`), mas o estado
geral não traz chaves de versão, por isso “Óculos (status)” mostra “—”. Não é falha.

#### Sondas da fase 3 em hardware

| Portão | Resultado | Evidência |
| --- | --- | --- |
| **G1** LifeLog sem conta oficial | ✅ **aprovado** (3 execuções) | `life_log_switch_result value 0` → A1 (13/161) → A2/A8 enviados; OFF confirmado pelos óculos ao final de todas |
| **G2** Formato do áudio | ✅ **aprovado** | 433 quadros de 240 B, TOC config 11 = **SILK banda larga, 60 ms, mono** (não 20 ms como se supunha); 433/433 decodificam a 16 kHz; 433 × 60 ms = 26,0 s = todo o intervalo A2→OFF, sem perdas; a transcrição local do `decoded.wav` (Whisper *small*, no contêiner de análise, sem envio a terceiros) devolve a narração do usuário em português com sentido |
| **G3** Máscaras | ✅ **em escala de frase** (revisto na bateria de 17/10) | ver abaixo: `vpuMask` ≈ 0,8–1,0 nas falas do usuário e **0,0** quando outra pessoa fala; `vadMask` com recall ~0,9 e ~1 s de atraso/rabicho. Quadro a quadro não servem (precisão ~0,5) |
| **G4** Continuidade | ✅ 5 min completos com os óculos no rosto (288 s de áudio em 291,7 s), em lotes a cada ~19 s | bateria 17/10, etapa 5 |
| **G4b** `inRealtimePage:true` | ✅ **tempo real**: um quadro de 60 ms por envelope (`mode:0`), 16,8 quadros/s, maior intervalo 301 ms, nada perdido | bateria 17/10, etapa 2 |
| **G7** Ligar pelos óculos | ✅ com a marca `life_log_guide` = 1 enviada pelo app, abrir o LifeLog no menu (app `com.rayneo.liteos.ai_memory`) faz os óculos ligarem sozinhos (`life_log_switch` = 1 → A1) e o áudio chega, **sem o aviso** | bateria 17/10, etapa 3 |
| **G5** Desligamento após matar o app | ✅ ao reabrir e conectar, o OFF pendente foi reenviado e confirmado pelos óculos em 0,3 s | bateria 17/10, etapa 6 |
| **G14** Gravação pelo menu (canal 14) | ⏳ refazer: nas duas baterias foi aberto o LifeLog (`com.rayneo.liteos.ai_memory`), não o Gravador (`com.rayneo.liteos.recorder`); sem a marca `life_log_guide` os óculos mostram “Please complete LifeLog settings on your phone” e enviam `life_log_guide_launch` (duas vezes) | log 22:50:14–22:51:02; bateria 17/10, etapa 4 |

Observação do usuário: ao iniciar o LifeLog **manualmente nos óculos**, as lentes mostram “Please complete LifeLog
settings on your phone”. Iniciado por este app (`life_log_switch`), o LifeLog funciona mesmo assim (G1/G2). No teste
G14 os óculos enviaram `life_log_guide_launch` (15/21), provavelmente o pedido para o telefone abrir essa
configuração. Hipótese a testar: o `life_log_guide` que a sonda desliga ao final (valor 0) seria a marca de
“configuração concluída”.

#### Bateria de testes (2026-10-17, 09:06–09:18, app 0.2.2; repetida às 09:48–10:08 com a 0.3.0)

A repetição deu os mesmos resultados: G4b com 55,7 s de áudio contínuo (maior intervalo 333 ms), G7 com os óculos
ligando sozinhos pelo menu, G14 com o aviso de configuração, G4 com 286 s em 5 min, G5 confirmado; A1 levou 19,7 s
na etapa 1 e 4,3–4,5 s nas demais.

Pacote `turboio-testes-20261017-0918.zip`, seis etapas. A análise de áudio foi feita no contêiner de trabalho
(Whisper *small* local, sem envio a terceiros) e as cópias do áudio foram apagadas depois.

* **A1 demora e varia**: de 4 s a 22 s após o `life_log_switch` (21 s nas etapas 1 e 2). No modo em cache, o que não
  foi entregue até o OFF se perde em parte: na etapa 1 chegaram só 3,7 s dos 18,7 s; na etapa 3, 23,8 s de 30,5 s.
  Em tempo real (etapa 2) nada se perdeu. **Decisão: a gravação do diário usa tempo real.**
* **Máscaras (G3)**, numa conversa real de 5 min com outra pessoa presente: em janelas de 5 s, `vadMask`/`vpuMask` ficam
  em 0 nos silêncios e perto de 1 na fala. Num trecho em que só a outra pessoa falava, `vadMask` = 1,0 e `vpuMask` =
  0,0; nas respostas do usuário, `vpuMask` voltou a 0,8–1,0. Quadro a quadro, a concordância com a energia fica em 0,69
  (as máscaras atrasam ~1 s e têm rabicho). O 1º teste (16/10) não mostrou isso; tinha 26 s e só o usuário narrando.
* **Ligar pelos óculos (G7)**: confirmada a hipótese do `life_log_guide`. Com a marca em 1, o menu liga o LifeLog e o
  app só aceita a tarefa. Sem ela (etapa 4), aparece o aviso. As mensagens 15/24 `glass_preview` mostram a navegação
  no menu (`scence`, `app.name = com.rayneo.liteos.ai_memory`, `action` 1/2), o que explica o encerramento do 1º teste
  de 5 min: provavelmente um toque na haste abriu/fechou esse app.
* **15/3** traz `mic_status` (1 quando o LifeLog liga o microfone), `screen_status` e `brightness_change`.

#### Terceira bateria (2026-10-17, 10:21–10:32, app 0.3.1, tudo em tempo real)

| Etapa | Ligado (A1 → OFF) | Áudio recebido | Maior intervalo |
| --- | --- | --- | --- |
| G1–G3, 40 s contados do áudio (A1 em 23,8 s) | 40,2 s | 40,6 s | 320 ms |
| G4, 5 min | 300,3 s | 300,5 s | 130 ms |
| G7 (ainda em lotes nesta versão) | 30,5 s | 23,8 s | 9,2 s |
| G5 | OFF reenviado e confirmado em 0,6 s | — | — |

**Em tempo real não houve perda** em 5 min. O G7 passou a usar tempo real na 0.3.2. No G7 os óculos ligaram,
desligaram (pelo menu) e religaram o LifeLog (`life_log_switch` 1 → 0 → 1) antes do A1: a gravação do diário trata o
0 como fim e o novo A1 como nova gravação.

Consequências aplicadas na 0.2.1:
* A análise usa a duração real de cada quadro (TOC) para o VAD, a taxa esperada e a duração do áudio, e informa o
  padrão de entrega (lotes × contínua).
* Durante uma sonda, as mensagens de estado do *launcher* (15/3 e 15/24) passam a ser gravadas, com endereços
  Bluetooth mascarados, para explicar por que os óculos encerram o LifeLog.
* ~~As máscaras dos óculos não serão usadas~~ — revisto na 0.3.0: o `vpuMask` entra na atribuição de “você” por
  frase (com a proximidade como reserva); o corte de fala continua pelo VAD do próprio app.

### Roteiro para executar no seu aparelho

Ative “Copiar log” na aba Diagnóstico após cada etapa e guarde o texto (não contém chaves/endereços).

| # | Passo | Critério de aprovação |
| --- | --- | --- |
| C1 | Desvincular no app oficial, esquecer no Bluetooth, modo pareamento, **Procurar óculos** | o RayNeo aparece (de preferência “serviço RayNeo”) |
| C2 | Selecionar → aguardar | fases Conectando → Autenticando → Pareando (aceitar diálogo) → Inicializando → **Conectado**, bateria exibida |
| C3 | Diagnóstico → **Teste BLE** | “✓ Óculos responderam” |
| C4 | Testes → **Enviar texto de teste** | o texto aparece nas lentes; anotar se acentos aparecem (senão ativar “Remover acentos”) |
| C5 | Testes → pergunta “Qual é a capital do Brasil?” → **Executar** → **Transmitir** | resposta em português nas lentes, paginada |
| C6 | Voz (Android nativo): acionar o assistente nos óculos e falar | transcrição e resposta aparecem nas lentes |
| C7 | Voz (OpenAI/Google, microfone dos óculos) | contador “Áudio dos óculos: N pacotes” sobe; resposta nas lentes |
| C8 | Estabilidade: tela desligada 15 min com serviço ativo; afastar/aproximar | reconexão automática, sem travar |
| C9 | Reversão ([REVERSAO.md](REVERSAO.md)) e revinculação no app oficial | app oficial volta a funcionar |

Em caso de falha em C2, o campo “motivo” do Diagnóstico identifica a etapa (`account-bound-peer`, `version-timeout`,
`proof-mismatch`, `service-missing`, `system-pair-failed`, `status-timeout`…).

### Fase 3 — bateria de testes nos óculos (aba Diagnóstico → “Bateria de testes nos óculos”)

Toque **Começar bateria** e depois **Iniciar esta etapa** em cada passo; as instruções aparecem na tela e, durante a
captura, um texto grande diz o que fazer (ex.: “SILÊNCIO total”). Cada etapa é uma sonda limitada no tempo que desliga
o LifeLog ao final. Ao terminar, toque **Exportar todos os testes (1 arquivo)**: o ZIP traz uma pasta por etapa
(relatório, eventos, áudio bruto, WAV e o log técnico daquele momento), `resumo.txt` e o log atual. Etapas podem ser
puladas. Duração total: cerca de 10 min. Desde a 0.3.1 todas as etapas usam o modo em tempo real e os roteiros contam
o tempo a partir do primeiro áudio (os óculos levam de 4 s a 22 s para começar).

| # | Etapa | O que fazer | O que responde |
| --- | --- | --- | --- |
| 1 | **G1–G3 · LifeLog 40 s com roteiro** | seguir o texto na tela: você fala, silêncio, outra pessoa fala, alternância | formato e se `vadMask`/`vpuMask` distinguem fala e “você” |
| 2 | **G7 · ligar pelos óculos** | em até 90 s, ligar o LifeLog pelo menu dos óculos | com a marca `life_log_guide` = 1, os óculos ligam sozinhos (A1 ou `life_log_switch` = 1) e o app aceita; o relatório traz `trigger` |
| 3 | **G4 · LifeLog 5 min** | óculos no rosto, conversar com pausas | continuidade em tempo real, lacunas, por que os óculos encerram (15/3, 15/24) |
| 4 | **G5 · fechar à força** | quando aparecer “áudio: 10 s”, forçar a parada do app; reabrir e conectar | o app reenvia o desligamento sozinho; a etapa conclui após a reconexão (o progresso da bateria fica salvo) |

Na 0.4.0 entram mais duas etapas, ambas só de observação (nada é enviado além de abrir e fechar o canal):

| # | Etapa | O que fazer | O que responde |
| --- | --- | --- | --- |
| G8 | **Apps nativos** (170 s) | abrir nos óculos, ~30 s cada: Legendas, Assistente de conversa, Teleprompter, Gravador; falar em cada um | o que cada app dos óculos pede ao telefone (para atender Legendas com a sua IA no grupo 3) |
| G14 | **Gravador nativo** | abrir o **Gravador** (não o LifeLog), gravar ~20 s e parar pelos óculos | se as gravações feitas nos óculos podem entrar no diário (canal 14) |

Ordem na bateria: G1–G3, G7, G8, G14, G4, G5.

Os botões avulsos continuam disponíveis em “Testes de hardware avulsos”, e “Apagar testes salvos no telefone” remove
os áudios guardados.

Se aparecer “⚠ LifeLog pode estar LIGADO”, toque **Segurança: desligar LifeLog nos óculos**.

### Bateria de recursos (aba Diagnóstico → “Bateria de recursos”)

Desde a 0.4.0 (rev. 6) o app testa sozinho cada função nos óculos, uma etapa por vez: faz a ação, mostra o que viu
(“resultado do app”) e pergunta se funcionou nas lentes. Só a sua resposta conta como aprovação. Cada etapa grava
`resultado.txt`, `trafego.txt` (mensagens trocadas com os óculos, sem áudio, sem conteúdo de arquivos e sem texto de
notificações) e `log.txt`; “Exportar bateria de recursos” junta tudo num ZIP.

| Etapa | Função |
| --- | --- |
| F1–F3 | Texto nas lentes; tamanho e posição; pergunta à IA nas lentes |
| F4–F5 | Assistente de voz pelo gesto; “Hey RayNeo” |
| F6–F8 | Alerta nativo; notificações do celular (simulada + real); navegação do Maps (simulada + real) |
| F9–F11 | Tempo; To-do dos óculos (item de teste, marcado como feito no fim); reação ao abrir o To-do |
| F12–F13 | Teleprompter pelo celular (preparo → iniciar → pausar → continuar → velocidade → sair); teleprompter aberto pelo menu dos óculos (só observação) |
| F14–F16 | Painel: leitura; cartão (instala, 25 s, remove); tipo simples/completo (troca, 25 s, volta) |
| F17–F19 | Aceno e balanço; leitura dos ajustes da tela; gravação de 40 s pelos óculos para o diário |

#### Resultado da bateria de recursos nos óculos (2026-10-17, 15:27–15:41, rev. 6/7, Strix OS 1.0.5.5)

| Etapa | Veredito | Evidência (tráfego) | Causa e ação |
| --- | --- | --- | --- |
| F1–F3, F8 texto nas lentes | ✗ só “Original” e a hora | 19/7 aceito (código 1, abre `aiSubtitle`); o texto 19/5 `mode 3, status 0` não aparece | Formato antigo (HarmonyOS). Rev. 8: padrão = modelo do app oficial (`mode 0, status 1`), pausa de 400 ms após o aceite; F1 mostra os 3 formatos (A/B/C) |
| F4 voz pelo gesto | ✗ | nenhuma mensagem do canal 13 na etapa (só navegação do menu, 15/24) | O gesto usado não aciona o assistente pelo telefone; às 15:12 uma rodada chegou (67 pacotes) e falhou na chave do Google |
| F5 “Hey RayNeo” | ✗ abre mas não responde | nenhuma mensagem na janela da etapa | Voz configurada no Google com chave do Gemini (HTTP 401). Rev. 6+: usar Deepgram |
| F6–F7 notificação nativa | ✓ cartão aparece | 21/2 enviado; os óculos **não** respondem 21/3 | Rev. 8: “sem resposta” conta como entregue e não abre mais o texto de reserva nas lentes; apps sugeridos valem enquanto você não escolher |
| F9 tempo | ✓ | 15/19 `value 0` | — |
| F10 To-do | ✓ ida e volta | 22/2 e 22/4 | — |
| F11 abrir To-do | ✓ (percebido) | 15/24 `com.rayneo.liteos.todo` | — |
| F12 teleprompter pelo celular | ✗ | 20/2 aceito (`code 1`, com campos novos `countdown, gear, depth, size, width, leading`); 18 informação do arquivo enviada; os óculos não pedem pedaços e encerram após 30 s (20/6 `code 3`) | Formato do arquivo (porta HarmonyOS) nunca confirmado em óculos. Rev. 8 reenvia como `<id>.txt` após 8 s; definitivo exige captura do app oficial |
| F13 teleprompter pelo menu | ✗ “unknown error” | os óculos enviam 20/1 `{"action":1,"type":1,"code":1,"chunkid":0}` e não recebem resposta | Pedido de roteiro sem formato de resposta conhecido; exige captura do app oficial |
| F14–F16 painel | ✗ “não reconhecida” | 15/19 com `{"simple_mode":0,"widgets":[2],"widgets_data":{}}` (sem `widgets_v2`) | Rev. 8 lê esse formato e troca `simple_mode` mantendo o resto |
| F17 aceno | ½ cartão aparece e o aceno é aceito nos óculos | 21/33 enviado; nenhum 21/34 de volta | Resposta do gesto não chega ao telefone; exige captura do app oficial |
| F18 ajustes | leitura ok | `wakeupConfig`, `headGestures`, `crownConfig` presentes | Rev. 8 registra esses grupos para entender o acionamento da voz |
| F19 gravação | ✗ | — | Falta aceitar o consentimento (Diário → Gravação ambiente) |

### Recursos novos da 0.4.0 (conferir nas lentes)

Cada item só conta como aprovado com o efeito visto nos óculos. Exporte o log (Diagnóstico) depois.

| # | Onde | Passo | Aprovado se |
| --- | --- | --- | --- |
| N1 | Conexão → Ajustes → “Testar alerta nativo” | tocar | cartão de notificação aparece; estado “exibida” |
| N2 | Conexão → Notificações | permitir acesso, escolher WhatsApp, ligar; receber uma mensagem | cartão nos óculos com app, título e texto |
| N3 | idem, “Navegação do Google Maps” | iniciar uma rota no Maps | “200 m · Rua X” nas lentes, atualizando nas manobras |
| N4 | Conexão → Tempo | cidade, “Enviar o tempo agora” | “aceito pelos óculos” e ícone coerente na tela inicial |
| N5 | IA → tamanho/posição/linhas | mudar e “Enviar texto de teste” | o texto muda; se recusado, cai no padrão |
| N6 | Testes → Teleprompter | colar um texto, enviar, Iniciar, Pausar, Continuar, velocidade, Sair | roteiro rola nas lentes; estados confirmados |
| N7 | Conexão → Painel | Ler painel; Mostrar cartão; Remover | lista de cartões; cartão aparece/some; demais intactos |
| N8 | idem | Painel simples → conferir → Restaurar tipo | o painel muda e volta |
| N9 | Pendências → Configurações → “Confirmar… nos óculos”; criar conversa com um compromisso | acenar / balançar | pendência mantida / descartada |
| N10 | Pendências → “To-do dos óculos” | ligar; abrir o To-do nos óculos | pendências aparecem; concluir lá conclui no telefone |
| N11 | Conexão → “Hey RayNeo” | ativar; dizer “Hey RayNeo, …” | o assistente responde com a sua IA |

## APK de teste

| Campo | Valor |
| --- | --- |
| Arquivo | `TurboIO-CustomAI-0.4.0-memoria-debug.apk` (versionCode 8; instala por cima da 0.2.x, mesma assinatura e dados) |
| SHA-256 | ver `TurboIO-CustomAI-0.4.0-memoria-debug.apk.sha256` publicado junto |
| Assinatura | certificado *Android Debug* (APK de teste; não é build de loja) |
| Pacote / alvo | `org.turboio.customai`, minSdk 26, targetSdk 35 |
| Conteúdo proprietário | nenhum (sem `.so`, sem classes/recursos RayNeo) |

## Limitações conhecidas

* Conexão, autenticação *guest* e LifeLog comprovados em hardware real (Android 16); display nas lentes, voz, reversão e
  todos os recursos novos da 0.4.0 (N1–N11) ainda sem evidência (Teste C parcial).
* Gravação do diário pelos óculos (0.3.0) ainda sem uso real: falta medir bateria e estabilidade em horas (G6).
* Um cliente por vez: o app oficial precisa estar desvinculado enquanto este app é usado.
* VAD por energia no microfone dos óculos (limiar 450 RMS) pode precisar de ajuste.
* Reconhecimento pelo microfone do telefone com o app em segundo plano depende do serviço de voz do sistema.
* Sem streaming de resposta; textos longos são cortados em ~600 caracteres nas lentes (completos no telefone).
