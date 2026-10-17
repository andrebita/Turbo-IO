# Turbo IO IA — app Android independente para RayNeo iO (IA própria, firmware original)

App Android **independente** que conecta ao RayNeo iO pelo Bluetooth LE do **firmware Strix OS original** e usa
**seus próprios provedores de IA** (OpenAI, Google Gemini, Anthropic Claude ou qualquer API compatível com OpenAI),
com respostas curtas em **português do Brasil** exibidas nas lentes.

* **Não modifica o firmware** (sem OTA, flash ou downgrade — não há código para isso).
* **Não modifica o APK oficial**: o app RayNeo 1.0.6 continua instalado; este app tem outro pacote (`org.turboio.customai`).
* Base técnica: porta para Kotlin do protocolo do cliente HarmonyOS deste repositório (`harmony-sdk/turbo_core`),
  validado em hardware; nenhum binário/SDK do fabricante.

Por que um app independente em vez de alterar o app oficial 1.0.6: ver [docs/AUDITORIA.md](docs/AUDITORIA.md) §8.

## Documentação

| Documento | Conteúdo |
| --- | --- |
| [docs/AUDITORIA.md](docs/AUDITORIA.md) | Diagnóstico técnico do repositório, firmware original × modificado, 1.0.6, decisão |
| [docs/ARQUITETURA.md](docs/ARQUITETURA.md) | Módulos, sequências BLE/voz/display, segurança |
| [docs/INSTALACAO.md](docs/INSTALACAO.md) | Instalação, pareamento, configuração das APIs e da voz pt-BR |
| [docs/TESTES.md](docs/TESTES.md) | Resultados dos testes A/B/C, evidências, roteiro para teste em hardware |
| [docs/PERMISSOES_E_RISCOS.md](docs/PERMISSOES_E_RISCOS.md) | Permissões, integridade do APK, riscos |
| [docs/REVERSAO.md](docs/REVERSAO.md) | Voltar ao app oficial / desinstalar com segurança |
| [docs/DESIGN_DIARIO_E_GRAVACAO.md](docs/DESIGN_DIARIO_E_GRAVACAO.md) | Arquitetura do diário, insights, gravação ambiente e sondas |

## Funcionalidades

| Funcionalidade | Estado |
| --- | --- |
| Descoberta BLE, autenticação *guest*, bond do sistema, leitura de bateria/estado | implementado · **✅ comprovado em hardware real** (Galaxy S25 Ultra, Android 16, firmware original) |
| Reconexão automática com backoff, serviço em primeiro plano | implementado · 🟡 em hardware: uma reconexão logo após conectar, depois estável por 30 min |
| Provedores: OpenAI, Gemini, Anthropic, compatível com OpenAI | implementado · formatos testados + endpoints reais testados (chave inválida) |
| Configuração: provedor, modelo, endpoint, chave, instruções, tokens, timeout, contexto | implementado |
| Chaves no Android Keystore (AES-256-GCM), logs redigidos, HTTPS obrigatório | implementado |
| Resposta pt-BR curta, limpeza de Markdown/emoji, paginação, opção sem acentos | implementado · testado |
| Exibir resposta nas lentes (sessão de texto, negócio 19) | implementado · hardware pendente |
| Assistente de voz acionado nos óculos (negócio 13) com microfone **dos óculos** → Whisper ou Google STT | implementado · fluxo testado com áudio Opus sintético · hardware pendente |
| Alternativa: microfone do **telefone** com reconhecimento nativo do Android (sem chave) | implementado · hardware pendente |
| Telas: Conexão, IA, Diário, Testes, Diagnóstico | implementado · compila, lint sem erros |
| **Diário**: todas as perguntas/respostas, busca sem acentos, exportação Markdown, retenção | implementado · testado (SQLite real) |
| **Insights do dia** (resumo, destaques, tarefas, decisões, compromissos, pessoas, citações), automático às 21h, nas lentes por voz (“resumo do meu dia”, “o que falei sobre X”) | implementado · testado com modelo simulado |
| **Gravação ambiente** pelo microfone do telefone → Deepgram (separa falantes) ou OpenAI → diário, só a fala é cobrada, áudio apagado após transcrever | implementado · testado ponta a ponta com transcritor simulado e endpoint real · uso real pendente |
| **Sondas de hardware** do LifeLog e da gravação pelo menu (fase 3), com desligamento garantido e pacote de diagnóstico | implementado · **executadas nos óculos**: LifeLog liga sem conta e o áudio (Opus 60 ms, mono) é inteligível; entrega em lotes de 20 s; canal 14 sem áudio — ver [TESTES.md](docs/TESTES.md) |
| Atualização de firmware / OTA / transferência de arquivos | **excluído por design** (allow-list testada) |

### Pendente / próximas fases

* **Teste C em hardware real**: conexão e LifeLog comprovados; faltam display nas lentes, voz, estabilidade longa e reversão — roteiro em [docs/TESTES.md](docs/TESTES.md).
* **F4**: fonte “óculos (LifeLog)” para a gravação ambiente, usando o formato medido em hardware.
* Calibrar o *end-pointer* (limiar de energia) do microfone dos óculos com áudio real.
* Respostas em streaming (SSE) para reduzir a latência percebida.
* Reconhecimento contínuo no dispositivo (ex.: Whisper local) e TTS opcional.
* Painéis/mini-apps/navegação nativa: exigem firmware modificado — fora do escopo.

## Build rápido

```sh
cd android-custom-ai
export ANDROID_HOME=/caminho/Android/sdk
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Licença: PolyForm Noncommercial 1.0.0 (ver `LICENSE` na raiz). Terceiros: AndroidX/Material/kotlinx-coroutines (Apache-2.0),
Concentus (BSD-3). Projeto não oficial; “RayNeo” identifica o alvo de interoperabilidade.
