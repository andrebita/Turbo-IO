# Design: Diário com Insights (opção 2) e Gravação Contínua (opção 3)

Status: **aprovado (2026-10-17); fases F1–F3 implementadas e testadas na JVM; F3 aguarda execução nos óculos; F4 depende do resultado.**

## 0. Resumo

As duas funções são o mesmo produto com **duas fontes de dados**:

```
 Fontes                                   Núcleo comum                         Saídas
 ─────────────────────────────            ─────────────────────────            ────────────────────
 (2) Interações com a IA  ───────────┐                                        Tela "Diário"
     (voz e texto, já existem)       ├──▶  Diário (SQLite + busca FTS)  ──▶   Busca ("o que falei sobre X?")
 (3) Áudio ambiente ─▶ pipeline ─────┘      entradas com hora e origem         Insights (resumo do dia,
     de transcrição                                                            tarefas, decisões)
                                                                               Lentes (resumo curto)
                                                                               Exportação Markdown
```

* **Opção 2** só precisa do núcleo comum. Funciona hoje, sem hardware novo, e é a primeira entrega.
* **Opção 3** acrescenta o pipeline de áudio: captura, segmentação, transcrição e envio para o diário.
  O pipeline é **independente da fonte**. Assim ele pode ser construído e testado com o **microfone do
  telefone** enquanto os canais de áudio dos óculos são validados por sondas curtas e seguras.

Nenhuma parte altera o firmware. As fontes dos óculos usam apenas comandos do firmware original,
liberados um a um na lista de permissões de envio.

---

## 1. O que o repositório já prova (e o que não prova)

| Canal | O que se sabe | Evidência | Lacunas |
| --- | --- | --- | --- |
| **LifeLog** (negócio 15 `life_log_*` + negócio 13 tipos 161–168 “A1–A8”) | Ligar: 15/type20 `life_log_guide` e `life_log_switch` (`{"delay":2}`). Os óculos respondem `life_log_switch_result` (0 = ok; 1 = sem permissão de mic; 2 = abra as hastes; 3 = bateria baixa). A1 (161) desperta; o app responde A2 (162 `{taskId, idleTimeoutSec:30}`) e A8 (168 `{inRealtimePage:false}`). Áudio vem em A3/A4 (163/164) com `{taskId, frameCount, mode, vpuMask, vadMask}` + N×240 bytes. Saída: A5 (165) dos óculos, A6 (166 `{rc:2}`) do app | `apps/RayNeoCompanion/Sources/AlwaysOnLocalProbe.swift` (sonda iOS de 25 s, era do app 1.0.2) | Nunca rodou mais de 30 s. Formato “NÃO verificado como opus/wav”. Não se sabe se A3 = tempo real e A4 = cache offline. Semântica de `vadMask`/`vpuMask` desconhecida. Não testado no firmware 1.0.5.5 nem no modo *guest* |
| **Gravação do menu** (negócio 14) | App habilita com 14/type14 `{isAppForeground:true}`. Os óculos iniciam com type1 `{uuid,action:1}` e o app confirma (`action:2, mode:1, code:1, type:1`). Áudio em type3 `{uuid, offset}` + bytes (quadros de 240 B). Parada type4. Conclusão type6 `{completed:true}`. Tipos 8/9/11 pedem confirmação | `harmony-sdk/.../RecordingSession.ets` + 11 testes; iOS `GlassesRecordingInbox.swift` | Só o *início* pelo menu foi confirmado em hardware (HarmonyOS WB13). Início pelo telefone falhou. Gravações longas não foram validadas |
| **Formato do áudio de gravação** | Quadros Opus de **240 bytes fixos = 20 ms, estéreo** (≈ 96 kbit/s). Um canal pode ser quase silencioso; deve-se misturar os dois por igual antes do STT | `RecordingDecode.h` (16 kHz); `NativeRecording.c` (48 kHz); `docs/ARCHITECTURE.md` §4 | A taxa de saída é escolha do decodificador (o Opus aceita ambas); confirmar com áudio real |
| **Microfone do assistente** (negócio 13 `rc:1`) | Funciona em rodadas curtas | Já implementado | Não serve para captura contínua (o firmware encerra a rodada) |
| **Microfone do telefone** | API Android padrão (`AudioRecord` em serviço com tipo `microphone`) | — | Capta pior com o telefone no bolso |

**Conclusão de engenharia:** o canal certo para “dia inteiro” é provavelmente o **LifeLog**. Foi feito para isso:
VAD nos próprios óculos, lotes compactos e possível cache offline. Mas ele tem a menor evidência. Por isso a
opção 3 é desenhada como **pipeline independente da fonte + sondas de validação**, e não como uma aposta num canal só.

---

## 2. Opção 2 — Diário de interações com Insights

### 2.1 Modelo de dados (SQLite, armazenamento privado do app)

```
entries(id, day, ts_start, ts_end, kind, source, text, answer, provider, model, latency_ms, meta_json)
  kind   ∈ {interaction, ambient, note}
  source ∈ {voice_glasses, voice_phone, text_test, lifelog, recording14, phone_mic}
entries_fts  (FTS4 sobre text/answer)  — busca em português, nativa do Android, sem dependência
insights(day, kind, input_hash, json, text, provider, model, created_at)
  kind ∈ {daily, partial}   input_hash = hash das entradas usadas → evita gerar de novo sem mudança
```

* Toda rodada de voz e toda consulta da aba Testes vira uma `entry` (pergunta, resposta, origem,
  provedor, latência). Isso é registrado onde a resposta já é tratada hoje (`TurboApp.askAi`).
* Retenção configurável (padrão: manter tudo; opção de apagar com mais de N dias). Também dá para apagar
  um item ou um dia inteiro.

### 2.2 Geração de insights (map-reduce, saída estruturada)

Um dia pode ter de poucas linhas (só interações) a dezenas de milhares de palavras (com a opção 3). Por isso:

1. **Janelas:** agrupar as entradas do dia em janelas de ~6 mil tokens, sem cortar uma entrada no meio.
2. **Map:** para cada janela, a IA devolve **JSON** `{topicos[], tarefas[], decisoes[], pessoas[], compromissos[{quando,o_que}], citacoes[]}`.
3. **Reduce:** une os JSONs parciais no relatório do dia (o mesmo esquema + `resumo` em 3–5 frases + `destaques[3]`).
4. **Renderização:** texto em pt-BR para o telefone; versão curta (≤ 3 páginas de 90 caracteres) para as lentes.
5. **Cache:** `input_hash` garante que só se recalcula quando houver entradas novas. Os resultados parciais
   ficam guardados, então um dia que cresce só processa as janelas novas.

Se a resposta não for um JSON válido, faz-se uma nova tentativa com instrução corretiva; persistindo, guarda-se o texto livre.
O provedor e o modelo são os da aba IA, com a opção de um modelo separado e mais barato para os insights.

### 2.3 Uso por voz nas lentes

Ao detectar a intenção, o assistente responde a partir do diário em vez de perguntar direto ao modelo:

| Frase (exemplos) | Ação |
| --- | --- |
| “resumo do meu dia”, “o que eu fiz hoje” | gera ou usa o insight do dia → versão curta nas lentes |
| “quais minhas tarefas”, “o que ficou pendente” | lista `tarefas` do insight |
| “o que eu falei sobre *X* (hoje/ontem)” | busca FTS por *X* → as 20 melhores entradas → IA responde citando a hora |

A detecção usa palavras-chave em pt-BR (determinística e testável). Se nada casar, segue o fluxo normal.

### 2.4 Telas

* **Diário** (nova aba): dias → linha do tempo; busca; botão “Gerar insights”; exportar Markdown (compartilhar).
* **Configurações:** retenção, resumo automático diário (horário), modelo dos insights, enviar resumo às lentes.

Tudo isso é testável na JVM, como o resto do app.

---

## 3. Opção 3 — Pipeline de áudio ambiente

### 3.1 Visão em camadas

```
 AudioSource  ─▶  Ingestão  ─▶  Segmentos   ─▶  Análise  ─▶  Fatias de  ─▶  Fila de       ─▶  Diário
 (LifeLog |       (append-only,  selados de       (decodifica,   fala (30–60 s)  transcrição      (entries
  Gravação14 |     journal,       5 min           mixa, VAD,     codificadas     (persistente,    kind=ambient)
  Mic telefone)    timestamps)                    regiões)       Opus mono       retry, custo)        │
                                                                                                       ▼
                                                                                                   Insights
```

Cada estágio é **idempotente** e registra o estado no SQLite. Se o app morrer, retoma do último estado
confirmado. Nenhum estágio apaga a entrada do anterior antes de o seguinte confirmar a gravação.

### 3.2 Interface da fonte

```kotlin
interface AudioSource {
    val id: String                        // "lifelog", "recording14", "phone_mic"
    fun start(sink: FrameSink); fun stop()
}
interface FrameSink {                     // chamado pela fonte
    fun frames(batch: OpusBatch)          // quadros Opus crus + metadados
    fun gap(reason: String)               // descontinuidade conhecida (desconexão, perda)
}
data class OpusBatch(
    val frames: List<ByteArray>,          // 240 B cada (óculos) ou Opus próprio (telefone)
    val channels: Int, val frameMs: Int,  // 2 / 20 nos óculos (a confirmar), 1 / 20 no telefone
    val receivedAtWall: Long, val receivedAtMono: Long,
    val deviceVad: BooleanArray?,         // vadMask, se o significado for confirmado
    val wearerVoice: BooleanArray?,       // vpuMask, hipótese: voz de quem usa os óculos
    val cached: Boolean,                  // lote vindo de cache offline (hipótese A4)
)
```

O microfone do telefone codifica com o próprio Concentus (Opus mono 16 kHz, 20 ms). Assim **todo o resto do
pipeline vê o mesmo formato**, venha o áudio de onde vier.

### 3.3 Ingestão e segmentos (armazenamento)

* Arquivo **append-only** por segmento: `[u16 len][quadro]...`, mais um **journal** (`segment.json`) atualizado
  por troca atômica de arquivo (técnica do HarmonyOS). Guarda a contagem de quadros, os carimbos de tempo
  (relógio de parede e monotônico) por lote, as lacunas e as máscaras.
* **Rotação a cada 5 minutos** (ou numa lacuna maior que 2 s). Segmentos curtos limitam a perda em falha,
  permitem processar quase em tempo real e facilitam apagar por partes.
* **Hora de cada quadro:** horário de chegada do lote, menos a duração dos quadros que ainda estão no lote.
  Se os lotes de cache (A4) trouxerem carimbo de tempo próprio, ele prevalece. A sonda vai descobrir isso (§4).
* Volume: 240 B/20 ms = **12 KB/s ≈ 43 MB/h** de áudio cru dos óculos. Com 8 h de captura ≈ 350 MB/dia.
  Política padrão: **apagar o áudio cru após a transcrição confirmada** e manter só o texto. Opção de manter
  áudio por N dias. Os arquivos ficam no armazenamento privado do app (cifrado pelo Android). Opcional:
  AES-GCM por segmento com a chave do Keystore.

### 3.4 Análise: decodificação, mixagem e VAD

1. Decodificar cada quadro (Concentus) para PCM 16 kHz. Se for estéreo: **média dos dois canais** (lição
   registrada em `docs/ARCHITECTURE.md`: um canal pode vir quase mudo). Medir a energia de cada canal na sonda
   para decidir entre mixar ou usar só o canal útil.
2. **VAD em duas camadas:**
   * dos óculos (`vadMask`), se a sonda mostrar que é confiável. Economiza processamento e indica fala em silêncio relativo;
   * no telefone: energia adaptativa por quadro de 20 ms (o `EndPointer` atual, em versão contínua), com
     histerese (entra em fala com ≥ 60 ms acima do limiar; sai após 600 ms abaixo).
   * Regra: fala = união das duas fontes, com o VAD do telefone como referência até a validação.
3. Saída: **regiões de fala** `[início, fim]` com horário absoluto, já com 300 ms de margem antes e depois.

### 3.5 Fatiamento (a decisão central)

Objetivo: fatias que o STT transcreve bem, com custo e latência baixos e sem cortar palavras.

| Regra | Valor | Motivo |
| --- | --- | --- |
| Unir regiões separadas por silêncio | < 1,5 s | frases de uma mesma fala ficam juntas e dão contexto ao STT |
| Duração-alvo da fatia | 30–60 s | ~janela nativa do Whisper (30 s); menos requisições; latência de ~1 min |
| Máximo | 120 s | limita a perda num erro e o tamanho do envio |
| Ponto de corte | **o silêncio mais longo** entre 30 e 60 s | não corta palavras |
| Sem silêncio até 120 s | corte forçado com **sobreposição de 1 s** | a deduplicação textual remove a repetição na junção |
| Fala mínima | ≥ 0,8 s de fala na fatia | descarta tosse, ruído ou estalo, que custariam dinheiro e gerariam alucinação |
| Continuidade | enviar o fim do texto da fatia anterior como `prompt` | o Whisper mantém nomes e grafia entre fatias |
| Silêncio entre fatias | não é transcrito | é o grosso do dia, custo zero |

**Formato de envio:** recodificar a fatia em **Opus mono 24 kbit/s em contêiner Ogg** (Concentus + escritor Ogg
simples). São ~180 KB por minuto de fala, contra 1,9 MB em WAV e 720 KB em Opus estéreo cru. A OpenAI aceita
Ogg/Opus e o Google também (`OGG_OPUS`). Isso viabiliza dados móveis.

**Cancelamento de alucinação:** descartar transcrições típicas de silêncio (“Legendas pela comunidade…”,
“Obrigado por assistir”, repetições) quando a fatia teve pouca fala. É um filtro com lista e testes.

### 3.6 Transcrição (fila persistente)

* Fila no SQLite: `chunk(id, segment, start, end, path, state, attempts, provider, error)`. Executada por
  **WorkManager** com restrição de rede (opção “só Wi-Fi”). Novas tentativas com espera crescente. Em erro
  definitivo (4xx de chave/cota), pausa e avisa; não fica tentando sem parar.
* Provedores plugáveis (a interface já existe): **OpenAI `gpt-4o-mini-transcribe`** como padrão (mais barato e
  melhor em pt-BR); `whisper-1` se quisermos carimbos de tempo por frase; Google STT; no futuro, um modelo no
  próprio aparelho (Whisper/Vosk), que exige NDK e mais bateria.
* Resultado → `entries(kind=ambient, ts_start/ts_end da fatia, text, meta={wearer_ratio, provider})`.
  Se a hipótese do `vpuMask` se confirmar, cada fatia ganha a marca **“você” / “outras pessoas”**, o que
  enriquece muito os insights (“você se comprometeu a…”).

**Custo estimado** (preços de referência de 2025; confirme a tabela atual do provedor):

| Fala transcrita/dia | gpt-4o-mini-transcribe (~US$ 0,003/min) | whisper-1 (~US$ 0,006/min) | Dados móveis (Opus 24k) |
| --- | --- | --- | --- |
| 1 h | ~US$ 0,18 | ~US$ 0,36 | ~11 MB |
| 3 h | ~US$ 0,54 | ~US$ 1,08 | ~32 MB |

Os insights do dia custam centavos com um modelo “mini”.

### 3.6.1 Decisões aprovadas (2026-10-17)

* Transcrição padrão do áudio ambiente: **Deepgram Nova-3** (`language=pt-BR`, `diarize=true`, `utterances=true`,
  `smart_format=true`, `punctuate=true`). Alternativa: OpenAI. Motivos: separação de falantes inclusa,
  horário por palavra e crédito inicial. Por minuto, a OpenAI mini é um pouco mais barata.
* Áudio cru apagado após a transcrição; dados móveis permitidos; todas as seções de insights; resumo diário às 21:00.

### 3.6.2 Separação de falantes (Deepgram)

A Deepgram devolve rótulos **anônimos** (`speaker` 0, 1, …) por palavra, válidos **só dentro de um envio**.

1. **Fatias maiores para a Deepgram:** até ~5 min por envio (cortadas no maior silêncio; mínimo de 0,8 s de fala),
   em vez de 30–60 s, que eram pensados para a janela de 30 s do Whisper. Menos cortes dão rótulos
   consistentes por mais tempo e melhor separação de falantes.
2. **Quem é “Você”**, em ordem de confiança:
   a. `vpuMask` dos óculos (hipótese: sensor da voz de quem usa; validar no G3). Sobrepor o horário das
      palavras aos quadros de 20 ms; o falante com maior fração de palavras sobre VPU ativo é “Você”, se ≥ 60%.
   b. Proximidade: RMS médio por falante, calculado a partir do horário das palavras; o falante dominante e
      constante é “Você”. Serve de reserva e de conferência para o item (a).
   c. Microfone do telefone: sem atribuição (“Pessoa 1/2”).
   d. Caso ambíguo: `speaker = unknown`. Nunca chutar.
3. Demais falantes: “Pessoa A/B” **por trecho**. A ligação entre trechos (assinaturas de voz no aparelho) fica para depois.
4. Diário: uma entrada por fala (palavras seguidas do mesmo falante), `meta.speaker ∈ {wearer, other, unknown}`,
   `meta.label`, `meta.confidence`. Os insights já interpretam `wearer` como “Você”.

### 3.7 Operação no Android 16 / Samsung

* A captura dos óculos roda no serviço `connectedDevice` que já existe. Não precisa de permissão de microfone
  do telefone, e esse tipo não tem limite diário de 6 h (o tipo `dataSync` tem, e não será usado).
* A fonte “microfone do telefone” exige o tipo `microphone`, iniciado com o app em primeiro plano.
* **Samsung (One UI)** costuma colocar apps em “suspensão profunda”. O app vai detectar isso e guiar o usuário
  a marcar a bateria como **“Sem restrições”**. Sem isso não há captura de dia inteiro confiável.
* Um indicador permanente na notificação (“Gravação ambiente ativa — Pausar”) e um atalho para pausar.

### 3.8 Segurança operacional do LifeLog (antes de tudo)

O LifeLog **liga um modo nos óculos**. Se o app morrer, os óculos podem continuar gravando no cache. Regras:

1. Antes de ligar, gravar em disco “**parada pendente**”. Só limpar após receber `life_log_switch` = 0 dos óculos.
2. Na reconexão, se houver parada pendente, **reenviar o desligamento primeiro**, antes de qualquer outra coisa.
3. Botão “Desligar LifeLog nos óculos” sempre visível enquanto houver estado pendente.
4. Liberar na lista de envio **apenas** 15/type20 (só `life_log_guide`/`life_log_switch`) e 13/{162,166,168}.
   Nenhum comando que apague o cache dos óculos.
5. Não interferir no app oficial: o modo só existe enquanto este app for o cliente vinculado.

### 3.9 Ética e LGPD

Gravação ambiente capta terceiros. Por isso:
* consentimento explícito na primeira ativação, com texto sobre a gravação de outras pessoas;
* tudo local por padrão; o envio a um provedor é opcional e explícito, por provedor;
* apagar áudio cru após a transcrição como padrão;
* pausa rápida e indicador sempre visível.

---

## 4. Plano de validação em hardware (sondas antes do produto)

Cada sonda é **limitada no tempo**, grava apenas os envelopes brutos e um manifesto (sem interpretar) e se
desliga sozinha. Você roda no seu aparelho e me envia o pacote de diagnóstico exportado; eu analiso e decido.

| Portão | Teste | Critério |
| --- | --- | --- |
| **G1** LifeLog liga em modo *guest* no 1.0.5.5 | sonda de 30 s | `life_log_switch_result` = 0 e chega A1 |
| **G2** Formato do áudio | decodificar quadros A3/A4 a 16 e 48 kHz; ouvir; transcrever | Opus válido (TOC = 20 ms), fala inteligível, STT coerente |
| **G3** Semântica das máscaras | falar / ficar em silêncio / outra pessoa falar, com roteiro | `vadMask` acompanha a fala; `vpuMask` marca só a sua voz |
| **G4** Continuidade | 5 min falando com pausas | ~50 quadros/s na fala; lacunas medidas; A3 × A4 entendidos |
| **G5** Desligamento | parar, matar o app e reconectar | OFF confirmado nos três casos |
| **G6** Resistência | 30 min, depois 2 h, depois 8 h | queda de bateria dos óculos e do telefone por hora; estabilidade BLE |
| **G14** Canal 14 (alternativa) | gravação de 2 min pelo menu dos óculos | arquivo completo, cobertura contínua, transcrição ok |

Decisão: se G1–G5 passarem, a fonte principal é o **LifeLog**. Se falharem, usa-se o **canal 14** (gravações
iniciadas por você no menu dos óculos, ótimo para reuniões). O microfone do telefone fica sempre disponível
como alternativa.

---

## 5. Plano de entrega

| Fase | Conteúdo | Depende de hardware? |
| --- | --- | --- |
| **F1** | Opção 2 completa: diário, registro das interações, busca, exportação, insights map-reduce, comandos de voz, aba Diário | não (validação final nas lentes) |
| **F2** | Pipeline de áudio completo com a fonte **microfone do telefone**: segmentos, VAD, fatiamento, Ogg/Opus, fila de transcrição, entradas `ambient` nos insights | não (testável no telefone) |
| **F3** | Sondas de diagnóstico LifeLog e canal 14 (G1–G5, G14) com exportação de pacote | **sim — você executa** |
| **F4** | Fonte de produção escolhida (LifeLog ou 14), conectada ao pipeline da F2; testes G6 | **sim** |

Testes automatizados previstos: fatiamento com regiões sintéticas (bordas, corte forçado, sobreposição),
deduplicação na junção, filtro de alucinação, Ogg/Opus validado contra a libopus de referência, retomada da fila
após falha, map-reduce com um provedor simulado, e a máquina de estados do LifeLog (incluindo a parada pendente)
contra óculos emulados, como já é feito hoje para a conexão.

## 6. Decisões que preciso de você

1. **Transcrição padrão:** `gpt-4o-mini-transcribe` (mais barato) ou `whisper-1` (com horário por frase)?
2. **Áudio cru:** apagar após transcrever (padrão proposto) ou manter por N dias?
3. **Rede:** permitir dados móveis (≈ 11 MB por hora de fala) ou só Wi-Fi?
4. **Insights:** as seções propostas (resumo, destaques, tarefas, decisões, compromissos, pessoas) servem?
   Quer resumo automático diário? Em que horário?
5. **Ordem:** F1 → F2 → F3 (sondas) → F4, como proposto? Ou prefere as sondas (F3) logo depois da F1, para
   validar os óculos mais cedo?
