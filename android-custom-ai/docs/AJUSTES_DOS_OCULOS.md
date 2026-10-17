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

## 3. O que não dá

* **Tipo de painel (dashboard)**: há leitura (`dashboard_config`) e símbolos no firmware (`applySimpleMode`,
  `processSetDashboardMode`), mas o comando de troca não aparece em nenhum cliente. Para trocar, use o app oficial.
* **Cartões próprios no painel** (`widget_install`): exigem regravar a configuração inteira do painel ou têm formato
  validado só em pesquisa. Ficam fora por segurança. O widget **To-do** nativo, se estiver no seu painel, mostra as
  pendências enviadas no item 1.
