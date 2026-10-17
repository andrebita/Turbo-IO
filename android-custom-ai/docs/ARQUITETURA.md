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

`type7` abrir sessão temporária (`sid` aleatório) → aguarda `type8` com `code` 1/2 (10 s) → páginas `type5` (≤ 90 caracteres,
intervalo configurável) → `type3` encerrar. `type3`/`type4` vindos dos óculos interrompem.

## Segurança

* **Sem OTA:** negócios 9 (MARS_FOTA) e 18 (transferência de arquivos) não existem no código de envio; a allow-list
  `Business.OUTBOUND` só permite 13/{2,5,7,12,32}, 15/{1}, 19/{3,5,7}. Teste automatizado comprova a recusa.
* **Chaves de API:** cifradas no Android Keystore; nunca em código-fonte, `BuildConfig`, log, backup (`allowBackup=false`,
  regras de extração vazias) ou APK. Campo de chave em branco mantém a salva; a UI só mostra `••••xy`.
* **Rede:** somente HTTPS (`network_security_config` bloqueia texto claro); endpoints sem credenciais/parâmetros embutidos;
  provedores fixos usam sempre o endpoint oficial; sem redirecionamentos automáticos.
* **Logs:** `DiagnosticLog` redige `Bearer`, `sk-…`, `AIza…`, `x-api-key`, `?key=`, endereços MAC; registra apenas
  metadados (business/type/tamanhos/HTTP/latência), nunca conteúdo de áudio ou chaves.
* **Identidade:** identificador de protocolo de 6 bytes aleatório gerado pelo app (não é o MAC do telefone).
