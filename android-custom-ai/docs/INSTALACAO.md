# Instalação e configuração

## 1. Requisitos

* Android 8.0+ (API 26). Alvo principal: Samsung Galaxy com Android 13–15.
* Bluetooth LE ligado; internet para os provedores de IA/transcrição.
* RayNeo iO com **Strix OS original** (1.0.5.5). Nada no firmware é alterado.
* O app RayNeo oficial (1.0.6) **pode continuar instalado** — o pacote deste app é outro (`org.turboio.customai`).

## 2. Instalar o APK

1. Copie `TurboIO-CustomAI-<versão>-debug.apk` para o telefone e confira o SHA-256 publicado junto (ex.: `sha256sum` no PC
   ou um app verificador de hash).
2. Abra o arquivo e permita “Instalar apps desconhecidos” **apenas para o app que abriu o arquivo**; desative depois.
3. Ou via PC: `adb install -r TurboIO-CustomAI-<versão>-debug.apk`.

O APK de teste é assinado com a chave *debug* do build. Para builds próprios: `./gradlew assembleRelease` e assine com a
**sua** chave (`apksigner`), nunca versionada.

### Compilar do código-fonte

```sh
cd android-custom-ai
export ANDROID_HOME=/caminho/Android/sdk     # platform 35 + build-tools 35.0.0
./gradlew testDebugUnitTest lintDebug assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 3. Primeira conexão com os óculos

> O firmware dos óculos aceita um cliente por vez. A troca abaixo é reversível ([REVERSAO.md](REVERSAO.md)).

1. No **app RayNeo oficial**: desvincule os óculos da conta (Dispositivo → desvincular). *Sem isso o firmware recusa o
   pareamento “guest” e o app mostra: “Os óculos ainda estão vinculados a uma conta do app oficial”.*
2. Android → **Configurações → Bluetooth** → RayNeo → **Esquecer/Desparear**.
3. Feche o app oficial (para ele não disputar a conexão).
4. Nos óculos: mantenha o botão pressionado ~5 s até o LED azul piscar (modo de pareamento).
5. Abra **Turbo IO IA → Conexão**, aceite as permissões (Dispositivos próximos/Bluetooth, Microfone, Notificações).
6. Toque **Procurar óculos**, escolha o RayNeo (de preferência o marcado “serviço RayNeo”).
7. Acompanhe: Conectando → Autenticando → Pareando (confirme o diálogo de pareamento do Android) → Inicializando → **Conectado**
   com a bateria. Depois disso o app reconecta sozinho (“Reconectar óculos salvo” / reconexão automática).

## 4. Configurar o provedor de IA (aba **IA**)

| Provedor | Onde obter a chave | Modelo padrão | Endpoint |
| --- | --- | --- | --- |
| OpenAI | platform.openai.com → API keys | `gpt-5-mini` | fixo `https://api.openai.com/v1/chat/completions` |
| Google Gemini | aistudio.google.com → Get API key | `gemini-2.5-flash` | fixo `…/v1beta/models/<modelo>:generateContent` |
| Anthropic Claude | console.anthropic.com → API keys | `claude-opus-5-5` (use `claude-haiku-5-5` para menor latência) | fixo `https://api.anthropic.com/v1/messages` |
| Compatível com OpenAI | o do serviço (DeepSeek, Groq, OpenRouter, Mistral, servidor próprio com HTTPS) | informe | URL base ou completa terminando em `/chat/completions` |

1. Escolha o **provedor**, ajuste o **modelo** (campo livre; sugestões abaixo do campo).
2. Cole a **chave de API**. Ela é cifrada no Android Keystore; o campo volta vazio (vazio = manter a salva).
3. **Instruções de sistema**: o padrão já é pt-BR com respostas curtas e sem Markdown. “Restaurar instruções padrão” recupera.
4. **Limite de tokens** (16–8192, padrão 800), **tempo máximo** (5–180 s, padrão 30), **rodadas de contexto** (0–10).
5. **Salvar**. Em **Testes**, digite uma pergunta e toque **Executar consulta**; o status mostra provedor, modelo e latência.

Pedidos com “detalhe”, “explique melhor” ou “resposta completa” elevam automaticamente o limite de tokens (≥ 1500) e o
prompt permite resposta mais longa; o display pagina o texto.

## 5. Reconhecimento de voz pt-BR (aba **IA → Reconhecimento de voz**)

| Motor | Microfone | Chave | Observações |
| --- | --- | --- | --- |
| Android nativo (padrão) | telefone | nenhuma | `SpeechRecognizer` pt-BR; parciais aparecem nas lentes |
| OpenAI (Whisper / `gpt-4o-mini-transcribe`) | **óculos** | OpenAI (vazio = usa a chave OpenAI da IA) | áudio Opus dos óculos → WAV 16 kHz → `/v1/audio/transcriptions`, `language=pt` |
| Google Cloud Speech-to-Text | **óculos** | chave de API do **Google Cloud** com a API Speech-to-Text ativada (a chave do Gemini/AI Studio **não** serve: dá “API keys are not supported”) | `speech:recognize`, `pt-BR`, até ~60 s |
| Deepgram (recomendado se você já usa a Deepgram no ambiente) | **óculos** | a mesma chave Deepgram da transcrição do ambiente | Nova-3 `pt-BR`, áudio enviado como WAV |

Uso: com os óculos conectados, acione o assistente nos óculos (gesto/botão de IA do firmware original), fale, aguarde a
resposta nas lentes. Diga “encerrar” / “pode fechar” para fechar. A aba **Testes** mostra o estado da rodada.

## 6. Exibição e preferências

* **Remover acentos no display:** ative se as lentes mostrarem quadrados no lugar de “ã, ç, é”.
* **Manter conexão com a tela desligada:** inicia um serviço em primeiro plano (notificação “Turbo IO conectado”).

## 7. Memória e pendências (aba **Diário**)

A aba Diário tem quatro seções: **Dia** (linha do tempo e insights), **Pendências**, **Pessoas** e **Semana**.

* Depois de cada conversa (≈ 5 min de silêncio), o app analisa o que foi dito com o provedor de IA configurado e cria
  pendências (tarefas, compromissos, decisões, coisas a acompanhar) com prazo, pessoa e a frase de origem. Ele também
  marca como feita uma pendência quando a conversa mostra isso (“já mandei o relatório”).
* Nomes: “Pessoa A” vira um nome quando a conversa deixa claro quem é (“oi, Marina”). Para corrigir ou indicar, toque
  longo na fala na linha do tempo → **Quem está falando?**. A sua escolha vale para todas as falas daquele trecho.
* Proatividade (notificações, e lentes quando conectados): pendência nova, lembrete 15 min antes do prazo, resumo da
  manhã às 08:00, revisão no domingo às 19:00. Tudo configurável em Pendências → Configurações da memória.
* Privacidade por tipo de conversa (trabalho, família, saúde…): **Incluir**, **Ignorar** (fica no diário, fora de
  pendências e resumos) ou **Não guardar** (as falas são apagadas depois de classificadas).
* Por voz ou em Semana → Perguntar: “minhas pendências”, “o que tenho pendente com a Marina”, “o que conversei com o
  João”, “o que falei de custos semana passada”, “resumo da semana”.
* Pela voz, direto nos óculos: “me lembra de ligar pro dentista segunda às 10h” / “anota: comprar pão” (cria na hora,
  com lembrete); “minhas pendências” lê a lista numerada nas lentes; “marca a 2 como feita” ou “já mandei o relatório”
  conclui.
* Nas lentes sob demanda: Pendências → **Mostrar nas lentes**. Nas notificações: **Feita**, **Descartar** e **Adiar 1 h**,
  sem abrir o app.
* **No To-do nativo dos óculos** (experimental, desligado por padrão): Pendências → Configurações da memória → “Enviar
  pendências ao app To-do dos óculos”. As abertas aparecem no To-do dos óculos (e no widget To-do do painel, se ele
  estiver lá); concluir nos óculos conclui no app. Detalhes e limites em [AJUSTES_DOS_OCULOS.md](AJUSTES_DOS_OCULOS.md).
* **Altura do texto, distância e duplo toque abre To-do**: aba Conexão → “Ajustes da tela dos óculos”. O tipo de
  painel não pode ser trocado por este app.
