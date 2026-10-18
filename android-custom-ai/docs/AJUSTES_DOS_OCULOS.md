# Pendências no To-do dos óculos e ajustes da tela

Status: implementado na 0.4.0 (rev. 3), **sem teste neste app em hardware**. Todos os comandos abaixo já são usados
pelo app oficial e foram mapeados pelos clientes deste repositório (`apps/RayNeoCompanion`, `harmony-sdk`); nenhum
grava firmware.

## 1. Pendências no app To-do nativo dos óculos (negócio 22)

* Envio: negócio 22, tipo 2, **um item por mensagem**:
  `{"eventType":1,"eventID":<id>,"createTime":<s>,"title":"…","isImportant":<bool>,"status":0|1,"lastModifiedTime":<s>}`
  (`CompanionDeviceFeatures.swift` `sendTodo`; `harmony-sdk/entry/src/main/ets/LocalWorkspace.ets`). Evidência:
  `harmony-sdk/VALIDATION.md`: “待办下发 — 用户确认镜片通过” (o usuário confirmou na lente; versão do firmware não
  registrada).
* O app envia até 30 pendências abertas, cada uma com um `eventID` aleatório guardado no banco. O título tem no
  máximo 60 caracteres, com o prazo em data fixa (`· 19/10 10:00`). O To-do nativo não recebe prazo, e a data fixa
  evita reenvio diário. Prazo de hoje ou atrasado marca `isImportant`.
* **Só mudanças são reenviadas.** Assim uma pendência concluída nos óculos nunca é sobrescrita por um estado antigo
  (aviso do `docs/CONFIGURATION.md` do repositório).
* Concluir ou reabrir nos óculos (tipos 4 e 10, vindos dos óculos) conclui ou reabre no app. IDs desconhecidos são
  ignorados. O tipo 14 (`successCount`) só vai para o log.
* **Não existe apagar pelo Bluetooth** (nenhum cliente conhece o formato). Concluídas e descartadas no telefone são
  enviadas como feitas.
* Opcional, desligado por padrão: Diário → Pendências → Configurações da memória.
* Regra de envio (`Business.contentAllowed`): só 22/2 com `eventType` 1, `eventID` > 0, título de 1 a 60 caracteres e
  status 0/1. Lote (6), agenda, apagar e binário ficam recusados.

## 2. Ajustes da tela (negócio 15)

Formato e faixas conferidos no código do app oficial 1.0.7 (`glasses_commands.dart`, rev. 13). Todos usam
`{"cmd":…,"payload":{"value":…,"mode":…,"data":"<JSON em texto>"}}`.

| Ação | Comando | Valores aceitos |
| --- | --- | --- |
| Ler | 15/4 `request_general_settings` | — |
| Altura do texto e profundidade | 15/5 `display_config`, `data = {"height":h,"distance":d}` | altura 1–6, profundidade 1–3 (faixas do app oficial) |
| Olhar para cima acende a tela, ângulo, coroa | 15/5 `wakeup_config`, `data = {"headup_switch":0\|1,"headup_degree":g,"crown_switch":c}` | ângulo 0–90°; `crown_switch` (acender girando a coroa) 0 desligado, 3 marble, 2 poke — as opções da tela oficial (1 só é lido). A leitura vem em camelCase (`wakeupConfig.headupSwitch`), a escrita em snake_case: até a rev. 12 o app escrevia em camelCase, o que provavelmente não funcionava |
| Gestos de cabeça | 15/5 `head_gestures`, `payload = {"value":1\|0,"mode":<lido>,"data":""}` | liga/desliga |
| Botão (coroa) | 15/5 `crown_config`, `payload.value = sentido` (0 padrão, 1 natural), `data = {"double":a,"longPress":b}` | ações: 0 perguntar à IA, 1 painel, 2 foco, 3 gravador, 4 legendas, 5 teleprompter, 7 nenhuma, 8 To-do, 9 dicas ao vivo. O 6 (diário) existe no app oficial, mas não está confirmado no iO: só é mantido se já estiver configurado. Até a rev. 12 o sentido ia dentro de `data` |
| “Hey RayNeo” | 15/16 `set_ai_voice_wakeup`, `mode` 1 liga, 0 desliga | — |
| Hora dos óculos | 15/5 `sync_time`, `data = {"utc":segundos,"offset":segundos,"timezone":"America/Sao_Paulo"}` | enviado a cada conexão |

* Cada gravação lê os valores atuais antes e manda todos os campos do grupo: o que não muda vai exatamente como foi
  lido. Depois lê de novo para confirmar; se os óculos não aplicarem a altura/profundidade, os valores anteriores voltam.
* Os primeiros valores lidos ficam guardados. **Restaurar ajustes originais** volta a eles (tela, botão e “olhar para cima”).
* Desligar “duplo toque abre o To-do” volta à ação lida originalmente (ou “nenhuma”).
* Conhecidos no app oficial, mas ainda **não usados** aqui: `brightness_change` (15/2), `privacy_config`, `auto_lock_time`,
  `language_change`, `system_unints_config` e os ajustes do assistente (`set_ai_*`).
* Qualquer outro comando, valor ou campo é recusado pela regra de envio e coberto por testes.

## 3. Painel dos óculos (negócio 15/18) — experimental

Conexão → “Painel dos óculos”. Toda mudança segue: **ler → mudar só o pedido → ler de novo e comparar**. Se algo não
confere, o app para e avisa (não repete).

| Ação | Comando | O que muda |
| --- | --- | --- |
| Ler painel | `dashboard_config` | nada; mostra os cartões e o tipo |
| Mostrar cartão | `widget_install` (cartão de texto `turbo_ui_io`, até 20 caracteres) | só o cartão deste app |
| Remover cartão | `widget_uninstall` | só o cartão deste app |
| Painel simples / completo | `dashboard_update` com a configuração lida e só `simple_mode` trocado | o tipo do painel |
| Restaurar tipo | idem, com o valor lido antes da primeira mudança | volta ao original |

* O cartão mostra, por exemplo, “3 pend · 15:00 Ligar” e pode ser atualizado sozinho quando as pendências mudam (no
  máximo a cada 2 minutos, só se o texto mudar).
* Se já houver no painel um cartão com o mesmo id que não seja deste app, nada é alterado.
* Evidência: cartão de texto visto na lente com o cliente HarmonyOS; fluxo ler → mudar → reler do add-on do app
  oficial. A troca de tipo é **hipótese**: o firmware tem `applySimpleMode` e a configuração traz `simple_mode`, mas
  nenhum cliente trocou esse campo. Se os óculos aceitarem e a releitura não mudar, o app avisa.

## 4. Cartões de aceno (negócio 21/33–35) — experimental

Diário → Pendências → Configurações: “Confirmar pendências novas nos óculos”. Para cada pendência achada nas conversas
(até 3 por vez), os óculos mostram “Anotar pendência?”. **Acenar** mantém; **balançar** descarta; sem resposta em 30 s,
a pendência fica. O cartão é removido depois, e a resposta é confirmada aos óculos (21/35). Formato da página de teste
do cliente iOS; não confirmado nos seus óculos.

## 5. O que não dá

* **Ordem dos cartões do painel**: possível com `dashboard_update`, mas fica fora para não mexer no que é seu.
* **Desligar “Hey RayNeo”** pelo app: o comando de desligar não é conhecido. Use o app oficial.
