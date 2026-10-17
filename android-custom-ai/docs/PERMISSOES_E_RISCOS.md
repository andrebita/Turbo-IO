# Permissões, integridade do APK e riscos

## Permissões declaradas

| Permissão | Por quê | Quando |
| --- | --- | --- |
| `BLUETOOTH_SCAN` (`neverForLocation`) | encontrar os óculos | Android 12+ |
| `BLUETOOTH_CONNECT` | GATT, pareamento, nome do dispositivo | Android 12+ |
| `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (máx. API 30) | exigência do sistema para BLE no Android 8–11 | só Android ≤ 11 |
| `INTERNET` | provedores de IA/transcrição escolhidos pelo usuário (HTTPS) | sempre |
| `RECORD_AUDIO` | modo “microfone do telefone” (opcional) | se concedida |
| `FOREGROUND_SERVICE`, `…_CONNECTED_DEVICE`, `…_MICROPHONE` | manter a conexão/assistente com tela desligada | opcional |
| `POST_NOTIFICATIONS` | notificação do serviço | Android 13+ |
| `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` | adicionadas pelo WorkManager (resumo diário e fila de transcrição): manter a CPU acordada durante a tarefa, esperar rede e reagendar após reiniciar | automático |

Acesso a notificações (opcional): o serviço `NotificationMirrorService` exige `BIND_NOTIFICATION_LISTENER_SERVICE`,
que só o sistema pode usar. Ele só funciona se **você** ligar “Turbo IO IA” em Configurações → Notificações → Acesso a
notificações (botão “Permitir acesso” na aba Conexão). Só os apps que você escolher vão para os óculos. O texto não é
gravado nem registrado no log; o app guarda apenas o nome do pacote (para a lista de escolha).

Não há permissões de contatos, SMS, armazenamento, localização em segundo plano, acessibilidade ou instalação de
pacotes. O arquivo .txt do teleprompter é aberto pelo seletor do sistema (sem permissão de armazenamento). `<queries>` só consulta a versão de `com.rayneo.venus` (exibição) e o serviço de reconhecimento de voz.

## Integridade

* O APK é gerado a partir deste código (`./gradlew assembleDebug`), sem binários RayNeo. Componentes de terceiros: AndroidX,
  Material Components, kotlinx-coroutines (Apache-2.0) e Concentus 1.0.2 (BSD-3).
* Cada APK publicado vem com SHA-256 e a saída de `apksigner verify --print-certs` e `aapt2 dump permissions`
  (ver [TESTES.md](TESTES.md)). Confira o hash antes de instalar.
* O APK de teste é assinado com chave **debug** (não é para distribuição em loja). Gere seu próprio build assinado para uso contínuo.

## Riscos e limitações

| Risco | Mitigação |
| --- | --- |
| Firmware | Não existe código de OTA (negócio 9); allow-list de negócios testada; nenhuma etapa grava no firmware |
| Canal de arquivos (negócios 17 e 18) | Só durante uma sessão de teleprompter (trava `fileLease`); só sai “informação do arquivo”, “pedaço” e “cancelar”; o nome é o id da transferência ou um nome simples terminado em `.txt` (nunca um pacote de atualização: sem `.zip`/`.bin`/`.img`, sem pastas); o `busId` tem de ser o do canal; até 48 KB; os óculos pedem cada pedaço. O negócio 9 (atualização) continua fora de qualquer caminho de envio |
| Painel dos óculos (15/18) | Lê, muda só o pedido (o cartão `turbo_ui_io` ou `simple_mode`) e lê de novo para conferir; para na primeira divergência, sem repetir; nunca sobrescreve um cartão que não seja deste app; guarda o tipo original para “Restaurar tipo” |
| Notificações nativas (21/2, 21/18) e cartões de aceno (21/33–35) | 21/18 liga a chave geral de notificações dos óculos (ajuste persistente, reversível no app oficial); o texto de notificações de outros apps vindo dos óculos (21/1) nunca é lido; aceno/balanço só confirma ou descarta uma pendência deste app |
| “Hey RayNeo” (15/16) | Mesmo comando do app oficial; este app **não sabe desligar** (desligue pelo app oficial); aviso antes de ativar |
| Tempo (15/18) | Dados do Open-Meteo (HTTPS, sem chave, só o nome da cidade); substitui o tempo da tela inicial |
| Ajustes dos óculos (altura, distância, duplo toque) | Só valores observados no app oficial; lê antes, preserva o resto, guarda os originais e restaura; efeito na lente a confirmar |
| Pendências no To-do dos óculos | Opcional; só envia o que mudou (não sobrescreve conclusões feitas nos óculos); não há como apagar pelo Bluetooth — itens fechados são marcados como feitos |
| Disputa de conexão com o app oficial | Use um cliente por vez; procedimento de troca em [REVERSAO.md](REVERSAO.md) |
| Perguntas/áudio enviados a terceiros | Só para o provedor configurado pelo usuário, via HTTPS; na gravação ambiente, só os trechos com fala, e só após o consentimento; áudio bruto apagado após a transcrição |
| Texto das conversas analisado pela IA (memória) | Só texto, nunca áudio, ao provedor de IA configurado; uma chamada por conversa; categorias marcadas “não guardar” têm as falas apagadas; desligável em Diário → Pendências → Configurações da memória |
| Vazamento de chave | Keystore AES-GCM, sem backup, sem log, sem exibição; apagar dados/desinstalar remove |
| Compatibilidade com Strix OS 1.0.5.5 | Protocolo validado antes do 1.0.5.5; **teste em hardware pendente** (Teste C) |
| Uso dirigindo/andando | Respostas curtas; não use o display de forma que comprometa a segurança |
| Licença | PolyForm Noncommercial 1.0.0 — uso não comercial |
