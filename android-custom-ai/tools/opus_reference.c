/* Generates the glasses-microphone Opus fixture with the repository's vendored reference libopus 1.5.2
 * (core-probe/Vendor/opus-1.5.2, the same library the HarmonyOS client used on hardware).
 * Output (stdout, JSON): packets (hex) and the libopus-decoded PCM (base64 of LE int16), 16 kHz mono.
 * Build: cmake the vendored opus, then
 *   cc -O2 -I<opus>/include tools/opus_reference.c <build>/libopus.a -lm -o opus_reference
 *   ./opus_reference > app/src/test/resources/opus-reference.json
 */
#include <opus.h>
#include <math.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>

static const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
static void b64(const unsigned char *in, size_t n) {
  for (size_t i = 0; i < n; i += 3) {
    unsigned v = in[i] << 16 | (i + 1 < n ? in[i + 1] << 8 : 0) | (i + 2 < n ? in[i + 2] : 0);
    putchar(B64[v >> 18 & 63]); putchar(B64[v >> 12 & 63]);
    putchar(i + 1 < n ? B64[v >> 6 & 63] : '='); putchar(i + 2 < n ? B64[v & 63] : '=');
  }
}

int main(void) {
  int err = 0;
  OpusEncoder *enc = opus_encoder_create(16000, 1, OPUS_APPLICATION_VOIP, &err);
  OpusDecoder *dec = opus_decoder_create(16000, 1, &err);
  if (!enc || !dec) return 1;
  opus_encoder_ctl(enc, OPUS_SET_BITRATE(24000));
  opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(5));
  /* Frame sizes the reference decoder accepts (multiples of 160 up to 1920). */
  const int sizes[] = {320, 320, 320, 160, 320, 640, 320, 960, 320, 320};
  static int16_t pcm_out[16000 * 3];
  size_t out_samples = 0;
  unsigned seed = 7392; double phase = 0;
  printf("{\"sampleRate\":16000,\"channels\":1,\"packets\":[");
  int t = 0;
  for (int f = 0; f < 60; f++) {
    int n = sizes[f % 10];
    int16_t pcm[1920];
    for (int i = 0; i < n; i++, t++) {
      double pitch = 140 + 60 * sin(2 * M_PI * t / 16000.0 * 1.5);       /* gliding voice pitch */
      phase += 2 * M_PI * pitch / 16000.0;
      double env = (t / 1600) % 4 == 3 ? 0.05 : 1.0;                         /* syllable gaps */
      double s = 0; for (int h = 1; h <= 8; h++) s += sin(h * phase) / h;
      seed = seed * 1103515245u + 12345u;
      double noise = ((seed >> 16) & 0x7fff) / 32768.0 - 0.5;
      pcm[i] = (int16_t)(env * (5000 * s + 600 * noise));
    }
    unsigned char packet[1500];
    int bytes = opus_encode(enc, pcm, n, packet, sizeof packet);
    if (bytes < 0) return 2;
    int16_t decoded[1920];
    int got = opus_decode(dec, packet, bytes, decoded, 1920, 0);
    if (got != n) return 3;
    memcpy(pcm_out + out_samples, decoded, got * 2); out_samples += got;
    printf("%s\"", f ? "," : "");
    for (int i = 0; i < bytes; i++) printf("%02x", packet[i]);
    printf("\"");
  }
  printf("],\"libopusVersion\":\"%s\",\"decodedPcmBase64\":\"", opus_get_version_string());
  b64((const unsigned char *)pcm_out, out_samples * 2);
  printf("\"}\n");
  opus_encoder_destroy(enc); opus_decoder_destroy(dec);
  return 0;
}
