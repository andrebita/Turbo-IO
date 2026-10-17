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

| Ação | Comando | Valores aceitos |
| --- | --- | --- |
| Ler | 15/4 `request_general_settings` | — |
| Altura do texto e distância | 15/5 `display_config`, `data = {"distance":d,"height":h}` | altura 1, 3 ou 5; distância 1 ou 2 |
| Duplo toque abre o To-do | 15/5 `crown_config`, `data = {"double":8\|0,"longPress":<lido>}` | duplo 0 ou 8 |

* Cada gravação lê os valores atuais antes e manda os dois campos: o que não muda vai exatamente como foi lido. Depois
  lê de novo para confirmar.
* Os primeiros valores lidos ficam guardados. **Restaurar ajustes originais** volta a eles.
* Duplo toque: só quando a ação atual é 0 (nenhuma) ou 8 (To-do). Se for outra função, o app recusa, porque não
  conseguiria restaurá-la.
* Efeito físico na lente **não confirmado** por nenhum cliente (o iOS marca “提交不等于眼镜物理效果已通过”). Confira
  nas lentes.
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
