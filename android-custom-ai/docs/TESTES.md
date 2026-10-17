# Resultados dos testes e limitações

Data: 2026-10-16 · Versão do app: 0.1.0-mvp (versionCode 1).

Legenda: ✅ executado e aprovado · ⚠️ executado com ressalva · ⏳ pendente (depende de hardware) · ❌ reprovado.

## Teste A — Ambiente de desenvolvimento

| Item | Resultado | Evidência / como reproduzir |
| --- | --- | --- |
| Compilação (AGP 8.13.2, Kotlin 2.2.21, compileSdk/targetSdk 35, minSdk 26) | ✅ | `./gradlew assembleDebug` |
| Lint Android | ✅ 0 erros (avisos cosméticos: i18n/KTX/versões) | `./gradlew lintDebug` |
| Testes unitários | ✅ **87 testes** executados, 0 falhas (+ 4 testes de rede ao vivo, opcionais) | `./gradlew testDebugUnitTest` |
| Protocolo = referência HarmonyOS | ✅ vetores de ouro + vetores gerados executando o TypeScript original (`tools/harmony-vectors.mjs`) | `HarmonyCrossTest`, `ProtocolTest` |
| Referência HarmonyOS em si | ✅ 48 testes do `harmony-sdk` (protocolo, autenticação, voz) passam neste ambiente | `node --test harmony-sdk/tests/{protocol-codec,auth-calculations,guest-exchange,business-commands,version-exchange,voice-session,launcher-status}.test.mjs` |
| Sessão BLE completa contra óculos emulados | ✅ handshake em 2 links, bond após prova, status/bateria, conta vinculada recusada, prova falsa recusada, troca de identidade bloqueada, reconexão automática, timeout | `GlassesSessionTest` |
| Sem OTA | ✅ negócios 9 e 18 recusados; nenhuma string de OTA/firmware no DEX | `outboundPolicyAndDelivery`; `strings classes*.dex \| grep -ci fota` = 0 |
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

**Status: ⏳ NÃO executado — não aprovado.** Não há óculos neste ambiente. Nenhuma afirmação de funcionamento nas lentes é
feita sem evidência em hardware.

Relato do usuário em 2026-10-17: “O app funciona.” Ainda não há log nem detalhamento por item. O Teste C continua
sem aprovação formal até chegar o pacote de diagnóstico, ou o log da aba Diagnóstico, cobrindo os itens C1–C9 abaixo.

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

### Fase 3 — sondas nos óculos (aba Diagnóstico → “Testes de hardware”)

Execute nesta ordem, com os óculos conectados e o app aberto. Depois de cada teste, toque **Exportar último teste
(com áudio)** e me envie o ZIP. Ele contém o relatório, os eventos, o áudio bruto e um WAV decodificado para você ouvir.

| Portão | Botão | O que fazer durante o teste | O que o relatório responde |
| --- | --- | --- | --- |
| G1–G3 | **LifeLog 30 s** | 10 s falando “teste um, dois, três…”, 10 s em silêncio, 10 s com outra pessoa falando | os óculos aceitam o LifeLog sem conta? O áudio é Opus decodificável? O `vadMask` acompanha a fala? O `vpuMask` marca só a sua voz? |
| G5 | — | durante um teste, feche o app à força; reabra e reconecte | o app reenvia o desligamento sozinho e mostra “LifeLog desligado (confirmado)” |
| G4 | **LifeLog 5 min** | converse normalmente, com pausas | cadência de quadros (~50/s?), lacunas, lotes em tempo real × cache |
| G14 | **Gravação pelo menu** | inicie uma gravação pelo menu dos óculos, fale 1 min e pare | gravação completa, cobertura contínua, decodificação |

Se aparecer “⚠ LifeLog pode estar LIGADO”, toque **Segurança: desligar LifeLog nos óculos**.

## APK de teste

| Campo | Valor |
| --- | --- |
| Arquivo | `TurboIO-CustomAI-0.1.0-mvp-debug.apk` |
| SHA-256 | ver `TurboIO-CustomAI-0.1.0-mvp-debug.apk.sha256` publicado junto |
| Assinatura | certificado *Android Debug* (APK de teste; não é build de loja) |
| Pacote / alvo | `org.turboio.customai`, minSdk 26, targetSdk 35 |
| Conteúdo proprietário | nenhum (sem `.so`, sem classes/recursos RayNeo) |

## Limitações conhecidas

* Compatibilidade do handshake com **Strix OS 1.0.5.5** não comprovada (Teste C).
* Um cliente por vez: o app oficial precisa estar desvinculado enquanto este app é usado.
* VAD por energia no microfone dos óculos (limiar 450 RMS) pode precisar de ajuste.
* Reconhecimento pelo microfone do telefone com o app em segundo plano depende do serviço de voz do sistema.
* Sem streaming de resposta; textos longos são cortados em ~600 caracteres nas lentes (completos no telefone).
