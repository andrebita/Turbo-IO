# Desinstalação e reversão segura

Nada deste app altera o firmware, o app oficial ou dados de outros apps. Reverter = desfazer o pareamento e remover o app.

## Voltar a usar o app RayNeo oficial (mantendo este app instalado)

1. **Turbo IO IA → Conexão → Esquecer óculos** (apaga a identidade salva neste app e desconecta).
2. Android → **Configurações → Bluetooth** → RayNeo → **Esquecer/Desparear**.
3. Nos óculos: botão pressionado ~5 s até o LED azul piscar.
4. Abra o **app RayNeo oficial** e adicione/vincule os óculos normalmente.

## Remover o app completamente

1. (Opcional) **Diagnóstico → Apagar todos os dados deste app** — remove chaves de API, configurações e óculos salvo.
2. Execute os passos 2–4 acima, se quiser voltar ao app oficial.
3. Android → Configurações → Apps → **Turbo IO IA → Desinstalar** (ou `adb uninstall org.turboio.customai`).
   A chave AES do Keystore e as preferências são apagadas pelo sistema na desinstalação.
4. Revogue as chaves de API nos painéis dos provedores se não for mais usá-las.

## O que nunca é necessário

* Nenhum flash, OTA, downgrade ou reset de firmware.
* Não é preciso desinstalar o app oficial, limpar dados dele nem fazer root.
* Se um passo de pareamento falhar, repita o modo de pareamento; não existe procedimento de risco envolvido.
