# Arquitetura — Turbo IO IA (Android independente)

```
┌──────────────── Telefone Android (app org.turboio.customai) ───────────────┐
│ UI: Conexão · IA · Testes · Diagnóstico          ConnectionService (FGS)    │
│        │                                                                     │
│ TurboApp (grafo de objetos, 1 HandlerThread "turboio-glasses")               │
│   ├─ GlassesSession ── GattLink (BluetoothGatt) ── BondManager ── Scanner   │
│   │     └─ protocol/: Frame, Tlv, VersionExchange, GuestAuthExchange,       │
│   │                   EnvelopeCodec, Commands (allow-list de negócios)      │
│   ├─ VoiceAssistant (negócio 13) ── GlassesOpusDecoder + EndPointer          │
│   │        └─ SpeechInput: OpenAI Whisper | Google STT | Android nativo     │
│   ├─ LensDisplay (negócio 19, texto paginado ≤ 90 caracteres)                │
│   └─ AiClient ── ProviderCodec: OpenAI | Gemini | Anthropic | compatível    │
│ SettingsRepository + SecretStore (Android Keystore AES-256-GCM)              │
│ DiagnosticLog (memória, redigido)                                            │
└──────────────────────────────────────┬──────────────────────────────────────┘
                     BLE GATT 0000b81d │ (firmware Strix OS original, sem OTA)
                               ┌───────┴────────┐
                               │  RayNeo iO      │
                               └────────────────┘
```

## Pacotes

| Pacote | Responsabilidade | Testado na JVM |
| --- | --- | --- |
| `protocol` | Porta 1:1 do `harmony-sdk/turbo_core` (quadro, CRC, TLV, versão, prova guest, envelope protobuf, comandos JSON) | sim, incl. vetores gerados pelo TypeScript original |
| `ble` | `GlassesSession` (máquina de estados, limites, reconexão), `GattLink`, bond, scanner, persistência | sessão completa contra óculos emulados |
| `ai` | Construção/parsing de requisições por provedor, validação HTTPS, mapeamento de erros, timeout | sim + teste ao vivo opcional |
| `stt` | Decodificador Opus, buffer PCM, *end-pointer*, WAV, transcritores, reconhecedor Android | Opus cruzado com libopus 1.5.2 |
| `voice` | Rodada de voz no negócio 13 | sequência completa com áudio Opus sintético |
| `display` | Limpeza de Markdown, paginação, sessão de texto do negócio 19 | sim |
| `security`, `diag` | Keystore, redator de segredos, log | redator na JVM |
| `journal`, `insights` | Diário (SQLite + FTS4), insights do dia, intenções de voz | SQLite real (JDBC) |
| `ambient` | Gravação ambiente: fontes (óculos LifeLog, telefone), segmentos, fatias, transcrição, falantes | pipeline completo com transcritor simulado |
| `memory` | Conversas → pessoas, pendências, conclusões; busca planejada; manhã/semana; lembretes ([MEMORIA_E_ASSISTENTE.md](MEMORIA_E_ASSISTENTE.md)) | motor e consultas com modelo simulado |
| `probe` | Sondas de hardware e bateria de testes | contra óculos emulados |
| `ui`, `service` | Telas e serviço em primeiro plano | compilação + lint |

## Sequência de conexão (idêntica ao cliente HarmonyOS validado)

```
App                                         Óculos (firmware original)
 │ GATT #1 connect, MTU 512, CCCD RX          │
 │── 0x10 TLV{0x11: versão guest} ───────────▶│
 │◀── 0x10 TLV{0x11: proto v1/v2, peer id} ───│   (sem conta? senão: "account-bound-peer" → parar)
 │ fecha GATT #1, 600 ms                      │
 │ GATT #2 connect …                          │
 │── 0x10 versão ────────────────────────────▶│
 │◀── 0x10 versão (peer id deve coincidir) ───│
 │── 0x10 TLV{0x18: rand4, SHA256(rand|phone|peer|salt)} ─▶│
 │◀── 0x10 TLV{0x19: rand4', prova do óculos} │   prova verificada antes de qualquer outra coisa
 │ createBond() (diálogo do Android)          │
 │── 15 LAUNCHER request_general_status ─────▶│
 │◀── 15 type1 {generalStatus:{battery,…}} ───│   → READY, identidade salva
```

Limites antes da autenticação (iguais à referência): 20 s de vida do link, 5 s por etapa, 1024 B TX, 8192 B RX,
sem fragmentação. Falhas transitórias com óculos já verificado → reconexão automática (2, 5, 10, 20, 30, 60 s).

## Rodada de voz (negócio 13)

```
óculos: type1 (despertar)
app:    type2 {"rc":1}             ← só no modo "microfone dos óculos"
óculos: type3 Opus 16 kHz mono …   → decodifica (Concentus) → EndPointer (silêncio 1,2 s / máx. 15 s)
app:    type2 {"rc":2}
app:    STT (Whisper / Google) ou SpeechRecognizer (microfone do telefone, parciais em type5 não-final)
app:    type5 {"text":…, "final":true}; type2 {"rc":2}
app:    IA → limpeza p/ display → blocos ≤ 24 caracteres em type32 → type12 "{}" (EOF)
app:    após 12–45 s: type2 {"rc":2}, type7 {"rc":1} (saída)
óculos: type8 = usuário fechou · type11 = oferta de nova rodada (follow-up)
```

## Exibição de texto (negócio 19, sem microfone)

Nos formatos “sessão de legendas” (rev. 14), depois do `type8` o app envia `type1` (início pelo celular, áudio do celular),
espera o `type2` dos óculos (código 1, 10 s) e passa a usar o `sid` que os óculos devolverem; a sessão termina com `type3` `reason_code 2`
(a prévia, com 10). Tamanho/posição fora do padrão vão antes num `type7` persistente (`scope global`, `is_display false`), que não abre a prévia. `type7` abrir sessão temporária (`sid` aleatório) → aguarda `type8` com `code` 1/2 (10 s) → páginas `type5` (≤ 90 caracteres,
intervalo configurável) → `type3` encerrar. `type3`/`type4` vindos dos óculos interrompem.

## Teleprompter (negócios 20 e 17/18)

20/2 preparar (`did` UUID, total, checksum FNV-1a) → óculos 20/2 código 1 → informação do arquivo (MD5) → óculos pedem
pedaços (tipo 3 → tipo 4) → tipo 5 sucesso + 20/2 código 7 → pronto → 20/3 iniciar, 20/4 pausar, 20/5 continuar, 20/7
velocidade (cada um espera a confirmação igual) → 20/6 sair (cancela o arquivo antes). Prazos: 30 s preparo, 2 min
transferência, 10 s comando. Um 20/6 dos óculos com outro código (ex.: 3) encerra a sessão. O 20/2 leva as configurações de
apresentação (`countdown`, `scroll`, `speed`, `gear` 1–3, `depth` 1–3, `size` 18/20/24) e não leva checksum; o checksum
FNV-1a vai no 20/3; o 20/7 reenvia todas as configurações. Aberto pelo menu, os óculos pedem a lista de roteiros
(20/1 tipo 1) e recebem a lista vazia do app oficial.

**Endereço do arquivo (rev. 12).** A análise do app oficial 1.0.7 mostrou que a transferência de arquivos tem dois canais:
17 (“SmallCore”) e 18 (“LargeCore”); o `busId` da informação repete o número do canal, e o envio exposto do app oficial
usa **17**, com id `task_<contador>_<milissegundos>` e o nome simples do arquivo. Até a rev. 11 usávamos 18 com o id da
sessão como nome, e os óculos 1.0.5.5 ignoraram. Agora a informação é anunciada em ordem, cada forma só se a anterior
ficar 6 s sem resposta: (1) 17 · `task_…` · `roteiro.txt`; (2) 17 · id da sessão · `roteiro.txt`; (3) 18 · `task_…` ·
`roteiro.txt`. A primeira forma que os óculos responderem é a usada; respostas atrasadas das outras são ignoradas e a
saída cancela só a forma em uso. Os pedidos de pedaço podem ter até 1 MB (o receptor oficial pede 51 200 bytes, sempre
com `chunkId` 0); a resposta é cortada no fim do arquivo. Os quadros de arquivo vão com número de mensagem 0, como no
app oficial. O que liga o arquivo ao `did` do 20/2 continua desconhecido (fica no código Flutter que faltou no APK).

## Painel (15/18 com sequência)

Pedidos com `sequence` aleatória e campo 6 = 1 (como o app oficial); resposta 15/19 com a mesma sequência. Fluxo:
ler `dashboard_config` → `widget_install`/`widget_uninstall` ou `dashboard_update` → ler de novo e comparar.

## Segurança

* **Sem OTA:** o negócio 9 (MARS_FOTA) não existe no código de envio. A allow-list `Business.OUTBOUND` permite:
  13/{2,5,7,12,32,162,166,168}, 14/{1,4,8,9,11,14}, 15/{1,4,5,16,18,20}, 19/{3,5,7}, 20/{1–8}, 21/{2,18,33,35} e 22/{2}.
  Há ainda uma segunda trava no conteúdo (`contentAllowed`):
  * 15/20 só aceita os comandos do LifeLog; 15/4 só a leitura de ajustes; 15/16 só os comandos exatos de ligar/desligar “Hey RayNeo”;
  * 15/5 só aceita `display_config`, `crown_config`, `wakeup_config` e `sync_time`, com as chaves e faixas do app oficial 1.0.7;
  * 15/18 só aceita tempo, leitura do painel, instalar/remover cartão `turbo_ui_*` e atualização do painel com `widgets_v2`;
  * 21 só aceita cartão de notificação deste app, a chave geral e cartões de aceno com limites de tamanho;
  * 20 exige id de sessão UUID; 20/1 só a lista vazia de roteiros; 22/2 só aceita uma pendência por mensagem.
* **Negócios 17 e 18 (arquivo):** não usam envelopes e só passam por `GlassesSession.sendFile`, aberto apenas durante
  uma sessão de teleprompter (`fileLease`) e filtrado por `FileTransfer.outboundProblem`: só os canais 17 e 18; tipos 1,
  4 e 6; o `busId` anunciado tem de ser o do canal; nome = id da transferência ou `<letras/números>.txt`; ≤ 48 KB.
  Mensagens maiores que uma escrita BLE vão como um quadro dividido em escritas seguidas.

  Testes automatizados comprovam as recusas.
* **Chaves de API:** cifradas no Android Keystore; nunca em código-fonte, `BuildConfig`, log, backup (`allowBackup=false`,
  regras de extração vazias) ou APK. Campo de chave em branco mantém a salva; a UI só mostra `••••xy`.
* **Rede:** somente HTTPS (`network_security_config` bloqueia texto claro); endpoints sem credenciais/parâmetros embutidos;
  provedores fixos usam sempre o endpoint oficial; sem redirecionamentos automáticos.
* **Logs:** `DiagnosticLog` redige `Bearer`, `sk-…`, `AIza…`, `x-api-key`, `?key=`, endereços MAC; registra apenas
  metadados (business/type/tamanhos/HTTP/latência), nunca conteúdo de áudio ou chaves.
* **Identidade:** identificador de protocolo de 6 bytes aleatório gerado pelo app (não é o MAC do telefone).
