# Turbo IO Custom AI — Android app (source)

Independent Android app for **RayNeo iO** glasses on the **stock Strix OS firmware**, using **your own AI providers**.
For the full feature list, safety rules and install steps, see the [project README](../README.md).

* Package `org.turboio.customai`, version 0.4.0 (versionCode 8), minSdk 26, targetSdk 35, Kotlin.
* Protocol: Kotlin port of the fork's HarmonyOS core (`harmony-sdk/turbo_core`), validated on hardware.
* No vendor binaries or SDKs.
* No firmware changes. The official RayNeo app is not modified and stays usable.

## Source layout

All paths are under `app/src/main/java/org/turboio/customai/`.

| Package | Contents |
| --- | --- |
| `protocol` | Frames, envelopes, guest auth, outbound allow-list and content gate (`Commands.kt`), file-transfer codec (`FileTransfer.kt`) |
| `ble` | GATT link, session state machine (connect → authenticate → bond → READY), send queue with frame splitting |
| `ai` | OpenAI / Gemini / Anthropic / OpenAI-compatible clients |
| `stt`, `voice` | Glasses-mic Opus decoding, end-pointing, transcribers, the voice round (business 13) |
| `display` | Lens text sessions (business 19), pt-BR text cleanup and paging |
| `journal`, `insights` | Journal store, search, daily insights, voice intents |
| `memory` | People and pending items, extraction, queries, reminders and workers, glasses To-do sync |
| `ambient` | Ambient recording (glasses LifeLog or phone mic), Deepgram / OpenAI transcription, speaker attribution |
| `device` | Native notifications, notification mirroring policy, weather, glasses settings, teleprompter, dashboard, nod/shake cards |
| `probe` | Hardware probes and the guided test suite |
| `service`, `ui`, `security`, `diag` | Foreground and notification-listener services, screens, Keystore secrets, redacted log |

## Build and test

```sh
export ANDROID_HOME=/path/to/Android/sdk
./gradlew testDebugUnitTest lintDebug assembleDebug
```

* The 139 unit tests include:
  * the BLE session against emulated glasses;
  * protocol vectors generated from the original TypeScript;
  * the voice round with synthetic Opus;
  * teleprompter, dashboard and suggestion-card flows against simulated glasses.
* Optional live provider tests: `RUN_LIVE_PROVIDER_TESTS=1 ./gradlew testDebugUnitTest --tests '*LiveProviderTest*'`.

## Documentation (Portuguese)

| Document | Contents |
| --- | --- |
| [docs/AUDITORIA.md](docs/AUDITORIA.md) | Repository audit, stock vs. modified firmware, why an independent app |
| [docs/ARQUITETURA.md](docs/ARQUITETURA.md) | Modules, BLE/voice/display/teleprompter/dashboard sequences, security |
| [docs/INSTALACAO.md](docs/INSTALACAO.md) | Installation, pairing, API and pt-BR voice setup |
| [docs/TESTES.md](docs/TESTES.md) | Test results A/B/C, evidence, hardware checklists |
| [docs/PERMISSOES_E_RISCOS.md](docs/PERMISSOES_E_RISCOS.md) | Permissions, APK integrity, risks |
| [docs/REVERSAO.md](docs/REVERSAO.md) | Back to the official app, safe uninstall |
| [docs/DESIGN_DIARIO_E_GRAVACAO.md](docs/DESIGN_DIARIO_E_GRAVACAO.md) | Journal, insights, ambient recording, probes |
| [docs/MEMORIA_E_ASSISTENTE.md](docs/MEMORIA_E_ASSISTENTE.md) | Memory: people, pending items, proactivity, search, weekly review |
| [docs/AJUSTES_DOS_OCULOS.md](docs/AJUSTES_DOS_OCULOS.md) | Glasses To-do, display settings, dashboard, nod/shake cards |
| [docs/FEATURES_POSSIVEIS.md](docs/FEATURES_POSSIVEIS.md) | What is possible on stock firmware and what is already in the app |

License: PolyForm Noncommercial 1.0.0 (see `LICENSE` at the repository root). Third-party components: AndroidX,
Material and kotlinx-coroutines (Apache-2.0), and Concentus (BSD-3). This is an unofficial project; "RayNeo" only
identifies the device it works with.
