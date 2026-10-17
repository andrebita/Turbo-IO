# Memória e assistente proativo (fase F5)

Status: **aprovado pelo usuário em 2026-10-17** (as 6 propostas, mais nomes por contexto e lista de tarefas proativa).
Implementado na 0.4.0.

## 1. Problema

O diário até a 0.3 produz **um relatório por dia** e para aí. Cada dia é analisado isolado, as tarefas não persistem nem
podem ser concluídas, os compromissos ficam em texto livre, nada chega na hora certa, as pessoas são “Pessoa A” sem
memória, a busca é literal e o relatório mistura o que importa com conversa banal.

## 2. Visão em camadas

```
entradas do diário (falas, perguntas à IA)
      │  agrupa em CONVERSAS (blocos: falas seguidas, pausa < 4 min)
      ▼
extração por conversa (1 chamada ao modelo, JSON)        ← contexto: data/hora, pessoas conhecidas, pendências abertas
      │  categoria · relevância · resumo · nomes dos falantes (com evidência) · pessoas · itens novos · itens concluídos
      ▼
memória persistente (SQLite)
   blocks   people   person_blocks   items
      │
      ├── entrega proativa: nova pendência → notificação (e lentes, opcional); lembrete na hora; resumo da manhã;
      │   resumo da noite (existente); revisão semanal
      └── consulta: “minhas pendências”, “o que tenho com a Marina”, “o que falei de custos semana passada”
```

## 3. Conversas (blocos)

* Bloco = entradas do mesmo dia com intervalo < 4 min entre uma e outra; perguntas à IA entram como linhas do bloco.
* Só blocos **fechados** (última fala há ≥ 4 min, ou dia anterior) são analisados; cada bloco é analisado uma vez
  (hash do conteúdo — se crescer, é reanalisado e os itens que gerou são substituídos).
* Rótulos anônimos da Deepgram só valem dentro de uma fatia: no texto enviado ao modelo o rótulo leva a fatia
  (`Pessoa A·3`), para que duas “Pessoa A” de fatias diferentes não se confundam.

## 4. Extração por conversa

Entrada: data, dia da semana e hora atuais; até 30 pessoas conhecidas (nome, apelidos, relação); até 40 pendências
abertas (id, texto, pessoa, prazo); a transcrição `[HH:MM] Você: …` / `[HH:MM] Pessoa A·3: …` / `[HH:MM] Você
perguntou à IA: …`.

Saída (JSON):

```json
{"categoria": "trabalho|familia|pessoal|saude|financas|compras|lazer|ruido|outro",
 "relevancia": 0.0,
 "resumo": "1 a 2 frases",
 "falantes": [{"rotulo": "Pessoa A·3", "nome": "Marina", "evidencia": "Você: “Oi, Marina…”", "confianca": 0.9}],
 "pessoas": [{"nome": "Marina", "relacao": "colega de trabalho", "fatos": ["prefere reuniões cedo"]}],
 "itens_novos": [{"tipo": "tarefa|compromisso|decisao|acompanhamento", "texto": "Enviar o relatório para a Marina",
                  "responsavel": "eu|outro", "pessoa": "Marina", "quando": "2026-10-23T15:00", "quando_texto": "sexta às 15h",
                  "horario": "10:42", "citacao": "te mando o relatório até sexta", "confianca": 0.8, "mesmo_que": null}],
 "itens_concluidos": [{"id": 12, "evidencia": "já mandei o relatório"}]}
```

Regras do prompt: nunca inventar; **nome de falante só com evidência explícita** (vocativo, apresentação, “tô aqui com
a X”), senão `null`; tarefas incluem **compromissos implícitos** de quem usa (“vou…”, “pode deixar”, “te mando”) e
pedidos aceitos; nada de TV, vídeo, música ou ruído; datas relativas convertidas para data/hora absolutas com a data
informada; `mesmo_que` aponta a pendência aberta que é a mesma coisa.

Validação no app (o modelo não decide sozinho):

| Regra | Efeito |
| --- | --- |
| nome de falante com confiança < 0,7 ou sem evidência | ignorado |
| item com confiança < 0,5 ou sem texto | ignorado |
| `mesmo_que` válido, ou texto quase igual (Jaccard ≥ 0,8) a uma pendência aberta | atualiza a existente (prazo, citação) em vez de duplicar |
| `itens_concluidos` com id aberto | marca feito, guarda a evidência |
| `quando` inválido ou no passado distante | fica só o `quando_texto` |
| categoria com política “ignorar” | bloco não gera itens nem entra nos insights |
| categoria com política “não guardar” | as falas do bloco são apagadas do diário (fica só “conversa removida”) |

## 5. Pessoas

* `people(id, nome, norm, apelidos, relacao, fatos, primeira_vez, ultima_vez, conversas)`; junção por nome
  normalizado e apelidos.
* `person_blocks(person_id, block_id, papel)`: `falante` ou `mencionado`.
* Nomes inferidos atualizam as falas do diário: “Pessoa A” vira “Marina” (`meta.person`, `meta.person_method =
  contexto`). Correção manual na tela tem prioridade (`usuario`) e nunca é sobrescrita pelo modelo.
* Ficha da pessoa: relação, fatos, últimas conversas (resumos), pendências abertas com ela.

## 6. Pendências

`items(id, tipo, texto, norm, status aberta|feita|descartada, responsavel, person_id, prazo, prazo_texto, block_id,
horario_origem, citacao, confianca, criada, atualizada, concluida, evidencia_conclusao, lembrada)`.

Cada item aponta a conversa e a citação de origem (dá para conferir). O usuário marca **feita** ou **descartar**.
Descartado não volta: o texto normalizado fica bloqueado para o mesmo bloco.

## 7. Proatividade

| Gatilho | Entrega |
| --- | --- |
| conversa analisada com itens novos | 1 notificação agrupada (“2 novas pendências: …”); nas lentes, se ativado |
| prazo de um item | lembrete 15 min antes (notificação + lentes, se conectado) |
| 08:00 (configurável) | resumo da manhã: atrasadas, de hoje e principais abertas |
| 21:00 (existente) | resumo do dia, agora só com conversas relevantes |
| domingo 19:00 | revisão semanal |

A análise roda em segundo plano (WorkManager, com rede) após cada transcrição, após perguntas à IA e a cada 30 min.

## 8. Busca por sentido e período

Um passo de planejamento (modelo) transforma a pergunta em `{termos: [sinônimos…], de, ate, pessoa}`; a busca une os
termos (OU) no período e, se houver pessoa, nas conversas dela; inclui os resumos das conversas. A resposta cita data
e hora (`15/10 14:32`). Sem embeddings: funciona com qualquer provedor configurado.

## 9. Revisão semanal

Entrada: resumos diários da semana, itens criados/concluídos, pessoas com mais conversas. Saída: resumo, avanços,
travado, recorrente, pessoas, sugestões. Guardada como insight `weekly`.

## 10. Custos e privacidade

* Uma chamada por conversa (≈ 2–4 mil tokens de entrada com o contexto) + uma por busca; com um modelo “flash” isso é
  centavos por dia. O modelo dos insights pode ser outro, mais barato (já configurável).
* Só texto vai ao provedor (nunca áudio); categorias “não guardar” apagam as falas localmente.
* Tudo fica no SQLite privado do app, sem backup; “Apagar dados” remove memória, pessoas e pendências.
