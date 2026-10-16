# Third-party components

HarmonyOS (`harmony-sdk/`) is an original ArkTS/ArkUI client and protocol implementation, not a modified official app. It depends on AMap Harmony packages pinned to 11.2.0 (navigation with bundled map APIs, location, search and common) from the official ohpm registry. Those SDKs/services remain subject to their own licensing, API-key and privacy requirements; they are not relicensed under PolyForm. They are downloaded during build, not distributed here as pre-signed apps. Users supply their own provider keys and signing material.

The Opus 1.5.2 source tree under `core-probe/Vendor/opus-1.5.2` is now included in full for the Harmony native audio CMake build, in addition to the earlier iOS build dependencies. Its upstream `COPYING` and source notices remain unchanged.

V2 (`official-addon/`) adds original extension sources and local preparation/signing tools only. No new official application executable, decrypted image, IPA, vendor framework or signing material is shipped in that directory. The official app is a user-supplied interoperability target, not a component licensed by this repository. Existing V1 dependency notices below remain unchanged.

The root PolyForm Noncommercial 1.0.0 license covers original Turbo IO material the project has authority to license, not third-party components, vendor binaries, extracted interfaces or their trademarks. Previously granted MIT permissions remain unaffected; see docs/LICENSING.md. Do not replace any upstream license with the root license.

- ZIPFoundation 0.9.20 is included as local source with its upstream LICENSE and privacy resource. The copied Swift 5.9 package manifest omits upstream test-only targets/fixtures; runtime sources are unchanged. Research builds may still resolve the pinned upstream package.
- Device builds use Opus 1.5.2 and WebRTC VAD sources from py-webrtcvad 2.0.10. Retain upstream COPYING/LICENSE files when supplying these dependencies.
- RayneoNet, RayneoLog and associated vendor frameworks are build dependencies of the device integration. They are not authored or relicensed by Turbo IO. Other included frameworks (including OpenSSL, CocoaAsyncSocket, SwiftProtobuf, CocoaLumberjack and SSZipArchive) retain their original licenses.
- Recovered interface definitions and version-specific ABI adapters are research integration material. No general SDK stability or permission for arbitrary vendor-library versions is implied.
- RayNeo and other product names identify interoperability targets; this project is not an official manufacturer release.

Before publication, check the actual dependency inventory and retain each supplied component's original notices. The presence of a component in a local development environment is not recorded here as a license grant. IPA/App distribution, signing certificates, private service credentials and user data are outside this source release.

## Android custom AI (`android-custom-ai/`)

The independent Android client is original Kotlin code (a port of the `harmony-sdk/turbo_core` protocol) and contains no RayNeo application, SDK or firmware component. Gradle resolves these third-party libraries at build time; they keep their own licenses: AndroidX Core/AppCompat/Fragment/Lifecycle and Material Components for Android (Apache-2.0), kotlinx-coroutines (Apache-2.0), Concentus 1.0.2 pure-Java Opus decoder (BSD-3-Clause, derived from libopus), and, for unit tests only, JUnit 4 (EPL-1.0) and org.json (public domain). The Gradle wrapper is Apache-2.0. The test fixture `opus-reference.json` is generated with the vendored Opus 1.5.2 source (BSD-3-Clause) by `android-custom-ai/tools/opus_reference.c`.

## Experimental Strix OS 1.0.4.12 firmware artifacts

The `firmware-strix-1.0.4.12-turbophoto-r3` prerelease contains an experimental modified OTA archive, its AP image, and a locally repacked original-content baseline. Original firmware code, libraries, and resources remain copyrighted by RayNeo, Bestechnic, and their respective rights holders. These binaries are not original firmware source code and are not relicensed under the repository's PolyForm license. No official endorsement or guaranteed recovery is implied. Original research code follows the repository license. The embedded Turbo portrait was explicitly authorized for publication by its owner.

These are high-risk research specimens, not production firmware. Read `firmware-research/strix-1.0.4.12/docs/SAFETY.md` before handling them. The baseline is not a full flash backup or a validated unbrick image.

The `firmware-strix-1.0.4.12-tnv1` prerelease additionally provides the exact tested TNV1 navigation OTA archive and AP image, retaining the TDP1 display test tool. Only AP content differs from the 1.0.4.12 original payload baseline; the other 13 payloads are unchanged. These original-code/resource copyright and non-endorsement boundaries also apply. Read `firmware-research/strix-1.0.4.12/native-navigation/README.md`; rollback is not guaranteed. No personal merged/signed iOS package is included.

## TMU1 音乐 API 参考

`official-addon/music/MusicAPI.m` 的网易云 API 编码参考 Beans Music（XIaodou0416，提交 `f9881318caf774129205c3160510ef0dfa318adc`），上游 MIT 许可原文位于 `official-addon/music/Beans-MIT-LICENSE.txt`，随音乐合并包保留。不是将整个 Beans App 合并或重新许可。项目原创代码的非商业条款不改变第三方代码原有许可。音乐、封面、歌词及网易云服务权利归其权利人；本仓库不提供账号、歌曲资源、付费解锁或风控绕过。

The `firmware-strix-1.0.4.12-tmu1` prerelease distributes the exact hardware-tested music OTA archive, retaining navigation, display and animation experiments. The firmware copyright and risk boundaries above apply equally; only AP payload content and its manifest Size/MD5 change. No account data or personal signing material is included, and original vendor firmware is not licensed as original project source.
