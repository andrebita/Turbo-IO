// Generates cross-implementation vectors by executing the hardware-validated HarmonyOS TypeScript
// protocol sources (harmony-sdk/turbo_core). The Kotlin unit tests must reproduce every byte.
// Usage: DEVECO_HOME=<dir containing sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript>
//        node android-custom-ai/tools/harmony-vectors.mjs > android-custom-ai/app/src/test/resources/harmony-vectors.json
import {createHash} from 'node:crypto';
import {loadCore} from '../../harmony-sdk/tests/load-core.mjs';
const codec = loadCore('./ProtocolCodec');
const auth = loadCore('./AuthCalculations');
const version = loadCore('./AndroidVersionExchange');
const business = loadCore('./BusinessCommands');
const launcher = loadCore('./LauncherStatus');
const voice = loadCore('./VoiceCommands');
const enc = s => new TextEncoder().encode(s);
const hex = b => Buffer.from(b).toString('hex');
const sha = {digest: b => Uint8Array.from(createHash('sha256').update(b).digest())};
const calc = new auth.AndroidAuthCalculations(sha);
const sid = '0123456789abcdef0123456789abcdef';
const random = Uint8Array.of(1,2,3,4), peerRandom = Uint8Array.of(9,8,7,6);
const phone = Uint8Array.of(0,0x11,0x22,0x33,0x44,0x55), glasses = Uint8Array.of(0xaa,0xbb,0xcc,0xdd,0xee,0xff);
const texts = ['Olá, mundo! Ação e coração.', 'Linha 1\nLinha "2" \\ fim', 'emoji 😀 ok', 'Café', 'a'.repeat(96)];
const out = {
  frames: [
    {packet:{messageNumber:0x1234,flags:0,address:null,slices:'',businessID:0x2a,payload:'0102fe'}},
    {packet:{messageNumber:0xbeef,flags:2,address:33,slices:'a0b0c0',businessID:225,payload:'00ff55'}},
    {packet:{messageNumber:7,flags:0,address:null,slices:'',businessID:13,payload:hex(business.businessEnvelope(2, enc(voice.recorder(true))))}},
  ].map(v => ({...v, encoded: hex(codec.encodeFrame({...v.packet, slices: Uint8Array.from(Buffer.from(v.packet.slices,'hex')), payload: Uint8Array.from(Buffer.from(v.packet.payload,'hex'))}))})),
  guestProof: hex(calc.guestProof(random, phone, glasses)),
  peerProof: hex(calc.guestProof(peerRandom, phone, glasses)),
  guestSessionKey: hex(calc.deriveSessionKey(random, peerRandom, phone, glasses, null)),
  authRequest: hex(codec.authRequest(random, calc.guestProof(random, phone, glasses))),
  versionRequest: hex(version.androidVersionRequest({phoneIdentifier:phone, phoneNameUTF8:enc('Turbo IO'), manufacturerUTF8:enc('samsung'), modelUTF8:enc('SM-S921B'), accountUTF8:null, publicKey:null, pairValue:null})),
  generalStatusQuery: hex(launcher.generalStatusQuery()),
  envelopes: [[2,'{"rc":1}',0],[32,'{"a":1}',1001],[300,'{"x":"yy"}',2147483647]].map(([t,j,s]) => ({type:t, json:j, sequence:s, encoded:hex(business.businessEnvelope(t, enc(j), s))})),
  displayStart: business.displayStart(sid),
  displayText: texts.map(t => ({text:t, json:business.displayText(sid, t)})),
  displayStop: business.displayStop(sid),
  transcript: texts.slice(0,4).map(t => ({text:t, json:voice.transcript(t, true)})),
  answerChunk: voice.answerChunk('Resposta curta: ação!', sid, 'Qual é a capital', 1700000000123),
  chunks: ['Olá 😀 mundo, tudo bem com você? 😀😀', 'x'.repeat(50)].map(t => ({text:t, limit:24, out:voice.voiceChunks(t, 24)})),
};
process.stdout.write(JSON.stringify(out, null, 1) + '\n');
