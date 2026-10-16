# Auditoria técnica — Turbo-IO para Android / RayNeo iO

Data: 2026-10-16 · Escopo: repositório `Turbo1123/Turbo-IO` (fork `andrebita/turbo-io`, `main` em `0aefa12`).
Alvo: RayNeo iO, Strix OS **1.0.5.5 original**, Samsung Galaxy (Android), app RayNeo oficial 1.0.6.

> Restrição absoluta aplicada durante toda a auditoria e implementação: **nenhuma** alteração, atualização,
> downgrade, flash ou envio de OTA para o firmware dos óculos.

## 1. Estrutura, licença e dependências

| Área | Conteúdo | Relevância para Android + IA própria |
| --- | --- | --- |
| `android-addon/` | Extensão injetada (smali/DEX) no **APK oficial RayNeo 1.0.5 (201)**, ~3,4 k linhas Java minificadas, scripts `package.mjs`, `host-profiles.mjs`, OTA GUARD-07 | Implementação Android anterior; IA própria via hook do chat oficial |
| `harmony-sdk/` | Cliente **HarmonyOS nativo e independente** (ArkTS) + HAR `turbo_core` com o protocolo BLE completo | **Base técnica reaproveitada** (validado em hardware com firmware original) |
| `official-addon/` (+ `tmig1-edition/`) | Extensão iOS para o app oficial 1.0.4/1.0.5/**1.0.6 (218)** | Referência de 1.0.6, mas Objective-C/dylib para iOS |
| `rayneo-protocol/`, `rayneo-session*`, `rayneo-display/`, `core-probe/`, `apps/RayNeoCompanion` | SDK Swift/iOS V1 (cliente independente, usa framework do fabricante) | Documentação cruzada do protocolo |
| `firmware-research/` | Firmwares experimentais (TAP1, TMIG1 etc.) | **Excluído** — exige firmware modificado |
| `dashboard-service/`, `app-gallery/`, `skills/` | SDK de painéis/mini-apps | Exige runtime TCE1/TAP1 (firmware modificado) — excluído |

* **Licença:** PolyForm Noncommercial 1.0.0 para o material original (commits antigos permanecem MIT). O novo
  módulo `android-custom-ai/` é derivado (porta do `turbo_core`) e segue a mesma licença, com uso **não comercial**.
* **Dependências do novo app:** AndroidX/Material/kotlinx-coroutines (Apache-2.0), Concentus 1.0.2 (BSD-3, decodificador
  Opus em Java puro), `org.json` só em testes. **Nenhum componente proprietário RayNeo** (sem APK/IPA, framework ou SDK do fabricante).

## 2. Módulo Android responsável pela IA (`android-addon`)

* `TurboAddon.java` engancha o *listener* ofuscado `LH7/c$c` do app oficial (`onAsrResult`, `onNlpResult`,
  `onResponseComplete`). Quando o NLP oficial classifica a frase como `chat/chat/workflow` (não comando, não offline),
  o addon cancela a resposta oficial e injeta a resposta de um endpoint **OpenAI-compatível** (`/chat/completions`,
  padrão DeepSeek) pelo mesmo listener, que a envia ao display com o protocolo oficial.
* `ChatPolicy.java` define elegibilidade, validação de endpoint e histórico; `SecretStore.java` guarda a chave.
* O ASR continua sendo o oficial (nuvem RayNeo), com microfone dos óculos.

## 3. Como o Turbo-IO se comunica com os óculos

Duas arquiteturas existem no repositório:

1. **Extensões (Android 1.0.5 / iOS V2)** — reutilizam a conexão, a conta e o ASR do app oficial. Não falam BLE diretamente.
2. **Clientes independentes (iOS V1 / HarmonyOS)** — falam GATT diretamente. O HarmonyOS reimplementa tudo sem binários do fabricante:
   * Serviço `0000b81d-…`, TX `ea8b70d5-…` (write-without-response), RX `7db3e235-…` (notify), MTU 512.
   * Quadro `AA 55 | len | msg | flags/slices | [addr] | business | payload | CRC16-XMODEM`.
   * Handshake: troca de versão (TLV 0x11) → prova *guest* SHA-256 (TLV 0x18/0x19) → bond do sistema → consulta de estado (LAUNCHER 15).
   * Negócios usados: **13** (assistente de IA: despertar, gravação, áudio Opus, transcrição, resposta, EOF, saída),
     **15** (estado/bateria), **19** (sessão de texto temporária no display).

## 4. Fluxo pergunta → resposta → display

| Etapa | Extensão Android 1.0.5 | Cliente independente (HarmonyOS / novo app) |
| --- | --- | --- |
| Despertar | gesto dos óculos → app oficial | óculos enviam 13/type 1 |
| Áudio | ASR oficial (nuvem RayNeo) | 13/type 2 `{"rc":1}` → óculos enviam Opus 16 kHz mono em 13/type 3 |
| Transcrição | oficial | ASR próprio (Aliyun no Harmony; **Whisper/Google/Android** no novo app) → 13/type 5 |
| IA | hook substitui resposta `chat` | modelo próprio |
| Display | listener oficial | resposta em blocos ≤ 24 caracteres (13/type 32) + EOF (13/type 12) → saída (13/type 7) |
| Texto avulso | — | 19/type 7 abrir → ack 19/type 8 → 19/type 5 texto (≤ 96) → 19/type 3 fechar |

## 5. Firmware original × firmware modificado

| Funciona com **Strix OS original** | Exige firmware modificado (excluído) |
| --- | --- |
| Conexão/autenticação/bond, bateria (15) | Menus nativos novos, painéis TCE1, mini-apps TAP1 |
| Assistente de voz com IA própria (13) | Navegação nativa TNV1, música TMU1, leitura TWR1, Pomodoro |
| Microfone dos óculos (Opus via 13/type 3) | Envio de imagens/animações (R3/TDP1/ANIM60) |
| Texto temporário no display (19), legendas | Qualquer coisa que dependa de OTA |

A tabela do README confirma: “模型接入、TTS、部分字幕等手机侧功能不因此要求刷机” (integração de modelo e legendas não exigem flash).

## 6. O que seria necessário para modificar o app RayNeo 1.0.6 (Android)

* `host-profiles.mjs` aceita **somente** os SHA-256 do 1.0.4 (195) e 1.0.5 (201); para 1.0.6 seria preciso obter o APK,
  remapear **todas** as classes ofuscadas (`LH7/c$c`, `LE3/u`, `Lcom/rayneo/rayneo_venus_sdk_plugin/j`, …), revalidar o ABI
  smali e reassinar com outra chave.
* Consequências: o APK reassinado **substitui** o oficial (mesmo `applicationId`, assinatura diferente → é preciso desinstalar
  o oficial e perder dados), não pode ser redistribuído (componente proprietário), e a cada atualização oficial tudo se repete.
* O próprio repositório declara: “本轮没有发布 Android 1.0.6 APK” (não há APK Android 1.0.6).

## 7. Reaproveitamento da implementação iOS mais recente (1.0.6 / Strix 1.0.5.5)

* O TMIG1 (iOS 1.0.6 + Strix 1.0.5.5) altera **o firmware** (AP) e o gate de OTA; não é aplicável (restrição de firmware).
* Do iOS/Swift aproveitam-se as **definições de protocolo** já espelhadas no `turbo_core` (TLV, quadro, prompts de assistente
  — `AssistantAnswerPrototype`, `AssistantRecorderPrototype`, `AssistantVADPrototype`), que conferem com o HarmonyOS.

## 8. Alternativa tecnicamente superior — decisão

**Decisão: app Android independente (`android-custom-ai/`), sem modificar o APK oficial.**

| Critério | Patch do APK 1.0.6 | App independente (escolhido) |
| --- | --- | --- |
| Firmware | inalterado | inalterado |
| App oficial | substituído (reassinado) | **continua instalado**, intacto |
| Componentes proprietários | redistribuição proibida | nenhum |
| Manutenção a cada versão oficial | remapear ofuscação | independente da versão do app |
| Base validada em hardware | app 1.0.5 apenas | protocolo HarmonyOS validado (Mate X5, set/2026, antes do 1.0.5.5) |
| IA / ASR | ASR oficial obrigatório | ASR e IA totalmente próprios |

Custo da escolha: os óculos usam **um cliente por vez**. Para usar o novo app é preciso desvincular os óculos no app oficial
(o firmware recusa a autenticação *guest* enquanto há conta vinculada — o app respeita isso e **não** contorna); para voltar,
basta revincular no app oficial (ver [REVERSAO.md](REVERSAO.md)). É reversível e não toca no firmware.

Sobre autenticação: nenhuma engenharia reversa nova foi feita. O app reutiliza a implementação *guest* já pública no
repositório (HarmonyOS `GuestAuthExchange`), e **não** implementa o ramo vinculado à conta oficial (ECDH/bond key).

## 9. Incompatibilidades e riscos identificados

1. **Strix OS 1.0.5.5:** a validação do HarmonyOS (WORKBENCH 14, 2026-09-17) é anterior ao lançamento do 1.0.5.5
   (outubro/2026), portanto, muito provavelmente com firmware da linha 1.0.4.x. O TMIG1 indica mudanças internas no AP 1.0.5.5;
   não há registro de mudança no pareamento *guest*, mas a combinação **não foi testada** em 1.0.5.5. — Teste C.
2. **Acentuação pt-BR:** a fonte do display é desconhecida para acentos; há a opção “Remover acentos”.
3. **Limites de quadro:** sem fragmentação (exceto negócio 18, que não usamos); textos curtos por design (≤ 96 / ≤ 24 caracteres).
4. **Limiar de VAD** do microfone dos óculos é heurístico (energia) e pode precisar de calibração.
5. **Tráfego simultâneo:** app oficial e novo app não podem disputar a conexão.

## 10. Estratégia de implementação

1. Porta 1:1 do `turbo_core` para Kotlin com os mesmos vetores de teste (+ vetores gerados executando o TypeScript original).
2. Sessão BLE Android (GATT, MTU, bond, reconexão com backoff, serviço em primeiro plano).
3. Camada de IA multi-provedor + armazenamento de chaves no Android Keystore.
4. Voz pt-BR: microfone dos óculos (Opus → Whisper/Google) e alternativa com microfone do telefone (Android nativo).
5. UI com 4 telas, logs redigidos, testes progressivos A/B/C; Teste C só aprovado com evidência em hardware.
