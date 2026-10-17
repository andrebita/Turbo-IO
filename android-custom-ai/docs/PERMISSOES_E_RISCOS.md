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

Não há permissões de contatos, SMS, armazenamento, localização em segundo plano, acessibilidade, instalação de pacotes
ou leitura de notificações. `<queries>` só consulta a versão de `com.rayneo.venus` (exibição) e o serviço de reconhecimento de voz.

## Integridade

* O APK é gerado a partir deste código (`./gradlew assembleDebug`), sem binários RayNeo. Componentes de terceiros: AndroidX,
  Material Components, kotlinx-coroutines (Apache-2.0) e Concentus 1.0.2 (BSD-3).
* Cada APK publicado vem com SHA-256 e a saída de `apksigner verify --print-certs` e `aapt2 dump permissions`
  (ver [TESTES.md](TESTES.md)). Confira o hash antes de instalar.
* O APK de teste é assinado com chave **debug** (não é para distribuição em loja). Gere seu próprio build assinado para uso contínuo.

## Riscos e limitações

| Risco | Mitigação |
| --- | --- |
| Firmware | Não existe código de OTA/arquivos; allow-list de negócios testada; nenhuma etapa grava no firmware |
| Disputa de conexão com o app oficial | Use um cliente por vez; procedimento de troca em [REVERSAO.md](REVERSAO.md) |
| Perguntas/áudio enviados a terceiros | Só para o provedor configurado pelo usuário, via HTTPS; na gravação ambiente, só os trechos com fala, e só após o consentimento; áudio bruto apagado após a transcrição |
| Texto das conversas analisado pela IA (memória) | Só texto, nunca áudio, ao provedor de IA configurado; uma chamada por conversa; categorias marcadas “não guardar” têm as falas apagadas; desligável em Diário → Pendências → Configurações da memória |
| Vazamento de chave | Keystore AES-GCM, sem backup, sem log, sem exibição; apagar dados/desinstalar remove |
| Compatibilidade com Strix OS 1.0.5.5 | Protocolo validado antes do 1.0.5.5; **teste em hardware pendente** (Teste C) |
| Uso dirigindo/andando | Respostas curtas; não use o display de forma que comprometa a segurança |
| Licença | PolyForm Noncommercial 1.0.0 — uso não comercial |
