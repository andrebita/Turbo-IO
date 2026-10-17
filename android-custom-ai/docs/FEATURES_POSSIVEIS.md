# O que mais o app pode fazer nos RayNeo iO (firmware original)

Revisão de 2026-10-17, depois da autorização do usuário: **qualquer coisa que não altere o firmware**. Isso libera:
* os canais de arquivo (negócios 17 e 18) para conteúdo como roteiros;
* gravar ajustes nos óculos.

Continuam fora: OTA/atualização (negócio 9), flash, downgrade e qualquer firmware modificado.

Fontes: clientes deste repositório (`apps/RayNeoCompanion` iOS V1 + `core-probe`, `harmony-sdk`, `official-addon`,
`android-addon`, `rayneo-*`), `README.md`, `docs/`, `firmware-research/`. Nenhuma linha do repositório comprova algo no
Strix OS **1.0.5.5 original**: tudo abaixo precisa de teste nos óculos do usuário.

Legenda de evidência:
* **A** = funcionou em óculos reais em outro cliente com firmware original;
* **B** = formato conhecido, sem confirmação em óculos;
* **C** = formato desconhecido, precisa de observação primeiro.

## 1. Já no app

* Conexão *guest*, assistente de voz (13) com a sua IA, texto nas lentes (19).
* LifeLog em tempo real (13/161–168) para o diário, com ligar/desligar pelo menu.
* Memória (pessoas, pendências, lembretes, resumos).
* To-do nativo (22/2, mão dupla).
* Altura/distância do texto e duplo toque (15/5).
* **Na 0.4.0 (aguardando teste nos óculos):** itens 1–10, 13 e 15 da tabela abaixo — alertas como notificação nativa,
  notificações do Android e navegação do Maps, “Hey RayNeo”, tarefa por frase livre, teleprompter, cartões de aceno,
  tempo, cartão no painel, tipo de painel, fonte/posição do texto e reação ao que você abre nos óculos; e as sondas
  G8 (apps nativos) e G14 (Gravador) na bateria.

## 2. Novos recursos possíveis

| # | Recurso | Canal | Evid. | Grava ajuste nos óculos? | Esforço |
| --- | --- | --- | --- | --- | --- |
| 1 | **Alertas do app como notificação nativa** (lembretes, pendência nova, respostas longas) | 21/2 cartão; 21/18 chave geral | A | 21/18 sim (liga/desliga notificações) | P |
| 2 | **Notificações do Android nas lentes** (WhatsApp, e-mail…; filtro por app no telefone) | permissão de acesso a notificações → 21/2 | A (cartão) | não | M |
| 3 | **Palavra de ativação “Hey RayNeo”** ligada pelo app | 15/16 `set_ai_voice_wakeup` mode 1 | A (comando observado no app oficial; clientes enviam a cada conexão) | sim; **desligar: comando desconhecido** | P |
| 4 | **Tarefa por frase livre** (“preciso comprar pão amanhã”) | a IA decide (função/ferramenta) → 22/2 | — (só telefone) | não | P |
| 5 | **Teleprompter**: colar texto ou abrir .txt, iniciar/pausar/velocidade pelo celular | 20/2–8 + 17/18 (arquivo ≤ 48 KB, `roteiro.txt`, MD5; os óculos pedem cada pedaço) | B/A parcial (iOS enviou roteiros com a biblioteca do fabricante; o envio reescrito do HarmonyOS não tem registro) | não (arquivo temporário do teleprompter) | M |
| 6 | **Confirmar com aceno/balanço de cabeça** (“Tarefa: X?” → aceitar/recusar; sim/não para a IA) | 21/33 cartão, 21/34 resposta, 21/35 confirmação | B | não | M |
| 7 | **Tempo na tela inicial** (Open-Meteo, sem chave) e cartão de previsão | 15/18 `current_weather_update` / `weather_update` | A parcial (ícones a conferir na lente) | conteúdo da tela inicial | P |
| 8 | **Cartão curto no painel** (“3 pendências · 15h Marina”) e ordem dos cartões | 15/18 `dashboard_config` → `widget_install`/`dashboard_update` | A (cartão de texto, HarmonyOS) | sim (painel); lê antes e confere depois | M |
| 9 | **Tipo de painel** (simples/completo) | 15/18 `dashboard_update` com `simple_mode` trocado | B (hipótese: o campo vem na configuração; o firmware tem `applySimpleMode`) | sim; reversível trocando de volta | P |
| 10 | **Fonte, posição e linhas do texto do app** (maior/menor, topo/base) | 19/7 `config` (`font_size`, `position`, `max_lines`) | B (só o padrão foi visto funcionando) | não (por sessão) | P |
| 11 | **Ajustes de notificação** (tempo na tela 5–30 s, chamadas, apps) | 21/17 | B | sim; **sem leitura: substitui tudo** | P |
| 12 | Brilho (mais níveis), bloqueio automático, gestos de cabeça | 15/2, 15/5 | A (brilho 7/8, bloqueio 15/25); B (resto) | sim | P |
| 13 | **Navegação do Google Maps nas lentes** (“↱ 200 m · Rua X”) | notificação do Maps → 19 (ou 21/2) | A (navegação simulada vista na lente, HarmonyOS) | não | M |
| 14 | **Legendas e tradução ao vivo** (o que os outros falam, em português ou traduzido) | áudio LifeLog em tempo real → transcrição contínua → 19 | A (exibição) + nosso pipeline | não | G |
| 15 | **Reagir ao que você abre nos óculos** (abriu o To-do → sincroniza; abriu o LifeLog → grava) | 15/24 `glass_preview` (entrada) | A | não | P |

## 3. A pesquisar primeiro (observar sem alterar nada, depois implementar)

| Item | Ideia | Como descobrir |
| --- | --- | --- |
| **Apps nativos de Legendas e Assistente de conversa** | Atender esses apps dos óculos com a sua IA e transcrição, como o app oficial faz | Sonda: abrir cada app nos óculos e gravar o que chega ao celular |
| **Gravador nativo** (`com.rayneo.liteos.recorder`, canal 14) | Gravações feitas nos óculos entram no diário | Repetir o G14 abrindo o **Gravador** (no teste anterior foi aberto o LifeLog) |
| Teleprompter pelo menu dos óculos | Lista de roteiros que aparece nos óculos | A mesma sonda, abrindo o Teleprompter |
| Desligar a palavra de ativação; ler o estado | Para o item 3 ser reversível pelo app | Ler os ajustes antes e depois de ligar; procurar a chave do estado |
| Apagar item do To-do | O firmware tem `TodoProtocol_HandleDelete` | Formato desconhecido; só por observação |
| `sync_time` | Acertar o relógio dos óculos | Formato desconhecido |

## 4. Só no telefone (complementam)

* **Calendário do Android**: compromissos extraídos viram eventos (com confirmação); a agenda entra no resumo da manhã
  e nos lembretes.

## 5. Continua impossível sem alterar o firmware

* Apps e menus próprios nos óculos, cartões gráficos/telas desenhadas (TCE1/TAP1), mapa nativo (TNV1), música (TMU1),
  timer/pomodoro (FOCUS-04), leitor WeRead (TWR1), controle remoto pelo relógio (TGR1).
* Câmera: o iO não tem. “Encontrar óculos”: sem evidência.

## 6. Evitar mesmo com autorização

* **Desvincular** pelo SDK (`cmd.unbound`): pode apagar conteúdo dos óculos. Para trocar de app, use o procedimento de
  [REVERSAO.md](REVERSAO.md).
* Ajustes sem leitura (item 11): antes de mudar, o app avisa que os valores atuais não podem ser lidos nem restaurados
  exatamente.

## 7. Ordem sugerida

1. ✅ (no app) **Rápidos e de alto valor:** 1, 3, 4, 7, 10, 15 e a sonda dos apps nativos (seção 3).
2. ✅ (no app) **Médios:** 2, 5, 6, 8, 9, 13.
3. **Grande (próximo):** 14, ou atender o app nativo de Legendas se a sonda G8 mostrar que é viável.
4. Fora por enquanto: 11 (substitui tudo sem leitura) e 12.
