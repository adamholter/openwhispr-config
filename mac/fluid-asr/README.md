# Local Parakeet on the Neural Engine

A small HTTP service that keeps NVIDIA Parakeet Unified EN 0.6B loaded through [FluidAudio](https://github.com/FluidInference/FluidAudio) (CoreML, Apple Neural Engine).

- `GET /health` → `{"ready":true}`
- `POST /transcribe`, body raw float32 16 kHz mono → `{"text","duration","elapsedMs"}`

For recordings of 30 seconds or more, Silero speech detection runs first and only the speech is decoded, which stops Parakeet from inventing words during long pauses. Shorter clips are decoded exactly as recorded. It only accepts local requests (no browser Origin, Host must be localhost) and validates request sizes. A short clip that comes back empty is retried once with silence padding. Every clip gets 1 s of silence added at the end (`OWASR_TRAIL` to change it), because Unified drops the last word when audio ends mid-speech.

```sh
swift build -c release
.build/release/OWFluidASR serve 6017      # models download on first run
```

Run it at login with a LaunchAgent (KeepAlive). The patched app tries this service first and falls back to its bundled CPU engine.

Unified replaced TDT v3 in November: on my recordings word errors went from 8.0% to 6.4% at the same speed. The earlier TDT v3 build was about 80x faster than real time (35 minutes of audio in ~13 s, vs ~257 s on the CPU build), and the fewest word errors of the engines I compared, including Whisper large-v3-turbo.
