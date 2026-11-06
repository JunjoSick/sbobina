# Sbobina — WhatsApp Italian Transcriber

The name comes from *sbobinare*, Italian for transcribing a recording (originally, off a tape reel).
The package stays `dev.junjo.eztranscribe`, the name it had for an hour; Android identifies an app
by its package, so changing it again would mean reinstalling as a new app.

Android app that takes a voice note from any messaging app through the share sheet and returns an
Italian transcript. Three on-device engines get benchmarked against each other; a paid cloud engine
is the fallback. Full plan: [Build Plan](https://claude.ai/artifact/35Qw9Z69JBh529LGjbNof2).

## Install

Releases are on [GitHub](https://github.com/JunjoSick/sbobina/releases), each with the APK attached.
To install and get updates, add the app in [Obtainium](https://github.com/ImranR98/Obtainium):
*Add app* → `https://github.com/JunjoSick/sbobina` → *Add*. Obtainium checks the releases and
offers each new one. Android 13 or later, arm64 only; Whisper's model (874 MB) downloads on first
use.

## Next

- Tidy up the interface.
- Audio–text sync: play the note with the words highlighted as they are spoken, and jump to a word,
  so uncertain passages can be checked by ear. whisper.cpp already reports segment and token
  timestamps, and per-token probabilities could flag the uncertain words to check.

## Status

**Local or Cloud, chosen per note; transcripts kept in a deletable history.** A shared note offers
two large buttons: *Local* (Whisper q8 on the phone, which on real WhatsApp notes is as fast as
Android's recognizer and markedly more accurate) and *Cloud*. Android's recognizer sits below them
as a smaller option, and also stands in automatically when Whisper cannot run. Transcription
carries on with the app in the background. The interface is Material 3 Expressive. Phases 5 and 6 (the bench) were skipped once the four real notes
settled the choice of engine.

| Phase | What | State |
| --- | --- | --- |
| 1 | Skeleton: share intents, received-file screen | done, verified on the Pixel |
| 2 | Decoding: Opus, AAC (MP4 and ADTS), MP3 → 16 kHz mono float PCM | done, verified on the Pixel against ffmpeg |
| 3 | System recognizer engine (`android-sr`) | done, kept; verified on the Pixel except airplane mode |
| 4 | whisper.cpp engines (q5_0, q8_0) | done, verified on the Pixel; q8 kept, q5 removed (slower here) |
| 5 | Bench mode + CSV export | not started |
| 6 | Analysis, pick the default engine | decided early on four real notes: q8 |
| 7 | Cloud engine + router | cloud engine built, untested with a real key; fallback to android-sr done |
| 8 | Polish | Expressive redesign, background transcription, per-note chooser and history done, verified on the Pixel |

## Toolchain

- JDK 21 from Android Studio: `C:\Program Files\Android\Android Studio\jbr`
- Android SDK: `C:\Users\gangi\AppData\Local\Android\Sdk` (also in `local.properties`)
- NDK 30.0.16248370 and CMake 3.31.6, for whisper.cpp (install both from Android Studio's SDK
  Manager). If SDK downloads hang at 0 bytes, check AdGuard or any other filter: it blocked
  `dl.google.com` here.
- Gradle 9.6.1 via the wrapper, AGP 9.3.1, Kotlin through AGP's built-in support
- `compileSdk = 37`, `targetSdk = 36`, `minSdk = 33` (API 33 is where the on-device
  `SpeechRecognizer` audio-source route phase 3 needs begins)
- whisper.cpp v1.9.5, vendored in `third_party/whisper.cpp`
- Models are not in the repository. The app downloads them on first use; for development they can
  be fetched into `models/` (git-ignored) and pushed to the phone. See Phase 4.

## Build and install

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:installDebug          # with the Pixel connected and USB debugging on
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. 31 JVM unit tests cover the
container sniffer, the PCM helpers, the resampler, the WAV writer, the cloud upload's multipart
body and key-to-provider detection. The first build compiles
whisper.cpp and ggml (about two minutes); native code is always built optimised, even for debug.

## Phase 1: share intake

Three intent filters make the app a share target:

- `ACTION_SEND` + `audio/*` — WhatsApp and Telegram label voice notes properly.
- `ACTION_SEND` + `application/octet-stream` — for apps that don't. The container is decided from
  the file header, not the claimed type.
- `ACTION_SEND_MULTIPLE` — batches, which the bench corpus import will use.

Each received clip is probed without decoding (`ClipProbe`) and listed with its name, duration,
sniffed container, claimed MIME type, track codec, sample rate, channel count and size. A failure
to read the file and a failure to read its audio format are reported separately, so an unreadable
share never shows up as an "unrecognised container".

`ContainerSniffer` recognises Ogg/Opus (by `OpusHead`, not just `OggS`), MP4/AAC, MP3, raw ADTS AAC,
WAV, FLAC, AMR and Matroska.

**Signed off on the Pixel, 2026-11-06.** WhatsApp and Telegram offer the app in their share sheets.
Signal offers only Save for voice notes, so Signal clips go Save → share from Files by Google, which
works. The header sniffer corrected a real mislabel on the device: MediaStore typed an `.m4a` as
`audio/mpeg`, and the app reported MP4/AAC.

## Phase 2: decoding

`AudioDecoder` runs `MediaExtractor` + `MediaCodec`, asks for float PCM (falling back to 16-bit when
a decoder declines), downmixes to mono one buffer at a time, then resamples with `Resampler`. The
sample rate comes from the decoder's output format, never the extractor's: Opus always decodes at
48 kHz, and HE-AAC reports half its real rate until SBR is decoded.

`Resampler` is a Kaiser-windowed sinc with one precomputed kernel per fractional phase (160 for
44.1 → 16 kHz, one for 48 → 16 kHz). Its cutoff (0.97 of Nyquist) and window (β = 9) follow ffmpeg's
swresample defaults, which is what Whisper's reference pipeline resamples through.

Each card's *Details* has *Decode to 16 kHz*, which writes `cache/decoded/<name>.16k.wav` (16-bit PCM
mono), and *Play WAV*. The folder is emptied at every start, since the audio is someone's voice.

**Batched decoding.** Android's software codecs run in a separate process, and every buffer handed
across costs about 0.75 ms, whatever the codec. One frame per buffer, a 3-minute note took 15–18 s
to decode. From Android 15 the decoder hands a codec that advertises `FEATURE_MultipleFrames` up
to 100 frames per buffer through `queueInputBuffers`, in MediaCodec's asynchronous mode. All three
of the Pixel's decoders support it. Older systems, codecs without the feature, and any batch that
fails fall back to the one-frame loop. Each decode logs its path and counts to
`adb logcat -s AudioDecoder:D`.

| 3-minute clip | One frame per buffer | Batched | Frames → buffers |
| --- | --- | --- | --- |
| Opus (`full_it`) | 17.1 s | 0.90 s | 9,200 → 92 |
| AAC | 17.7 s | 0.83 s | 7,926 → 80 |
| MP3 | 15.1 s | 0.60 s | 7,045 → 71 |

Resampling adds about 0.7 s, so a 3-minute note is ready for an engine in about 1.5 s.

### Verified on the Pixel against ffmpeg

Each fixture was shared to the app, decoded by tapping the real button, pulled back, and compared
with `ffmpeg -ar 16000 -ac 1` on the same source. Opus is decoded with `-c:a libopus`, the library
Android's decoder wraps; ffmpeg's built-in Opus decoder is not a fair reference, since it differs from
libopus on SILK-coded speech (24 dB SNR at 48 kHz on `full_it`, with no resampling involved). The
batched and one-frame paths give the same results:

| Clip | Tone | Pitch after decode | Alignment vs ffmpeg | SNR vs ffmpeg | Length, ours / ffmpeg |
| --- | --- | --- | --- | --- | --- |
| test_opus.ogg | 220 Hz | 219.92 Hz | 0 samples | 70.7 dB | 6.0135 / 6.0000 s |
| test_aac.m4a | 330 Hz | 330.16 Hz | 0 samples | 76.8 dB | 4.0170 / 4.0171 s |
| test_mp3.mp3 | 440 Hz | 440.00 Hz | 0 samples | 76.5 dB | 3.0000 / 3.0000 s |
| test_adts.aac | 550 Hz | 549.95 Hz | 0 samples | 76.5 dB | 2.0433 / 2.0434 s |

About 76 dB is the 16-bit rounding floor at this signal level, so the on-device output matches
ffmpeg's to within rounding. Batch boundaries were checked on the full 3-minute decodes as well, 92
of them in the Opus note: zero lag against the reference and 78.4 dB (Opus speech), 77.0 dB (AAC)
and 76.4 dB (MP3). Playback was confirmed in the audio service (a started `MediaPlayer` declared as
speech media, released on completion) and by ear.

**Known deviation:** Opus comes out 13.5 ms long. ffmpeg trims the last frame's padding using the
Ogg granule position; Android's decoder does not. It is trailing silence, harmless to
transcription, but it inflates a clip's measured duration slightly — the bench should take duration
from the source, not from the decoded length.

## Phase 3: Android's on-device recognizer (`android-sr`)

`AndroidSpeechEngine` hands the decoded clip to `SpeechRecognizer.createOnDeviceSpeechRecognizer`
through a pipe (`EXTRA_AUDIO_SOURCE`). On the Pixel the recognizer is Android System Intelligence.
Two settings decide whether a long note survives:

- **Segmented session bound to the audio source.** A plain session behaves like dictation and stops
  at the first pause. `EXTRA_SEGMENTED_SESSION = EXTRA_AUDIO_SOURCE` keeps it going, one result per
  segment, until the pipe closes.
- **Feed speed.** This is a live-audio recognizer. Fed faster than it can listen, it skips speech
  and reports no error. Unpaced (about 6× real time), a 7-second clip lost its whole middle sentence.

**Italian model.** Not installed out of the box. `prepare()` downloads it through
`triggerModelDownload` with a `ModelDownloadListener` (about 35 s on Wi-Fi). Do not use the
listener-less overload: on Android 17 the system server throws a `NullPointerException` trying to
report a download error to the absent listener, and the request is lost.

### Results on the Pixel: a 3:04 Italian note

Synthetic speech with a known transcript (see Test fixtures), Opus at 16 kbps like a WhatsApp note,
with a 3-second silence at 2:26:

| Fed at | Transcribe time | RTF | Reaches the last word | Words lost |
| --- | --- | --- | --- | --- |
| 4× | 46.3 s | 0.25 | yes, #478 of 478 | "è molto bello" |
| 3× | 61.5 s | 0.33 | yes | none |
| 2× | 92.1 s | 0.50 | yes | none |
| 1× | 184.2 s | 1.00 | yes | none |

At 1×, 2× and 3× the transcripts are word-for-word identical, with 12 differences from the script in
478 words: 8 are numbers written as digits (*nove* → 9, the door code → 4729), *backend* became
"back end", and both interjections *ah* were dropped. None is a misheard content word. Punctuation
is weaker than the words: sentence breaks follow pauses rather than grammar, and segments start with
a capital mid-sentence.

**Default feed speed: 2×.** 3× was clean here, but 4× already drops words, so 3× leaves no margin
for a hot or busy phone, where the failure would be silent. Phase 5 should bench 3× under thermal
load before raising it.

**Not verified:**

- *Airplane mode.* Wireless adb drops with Wi-Fi. The recognizer runs on the device by design, but
  this phase's done-when names airplane mode, so it needs a manual check.
- *Real voices.* The test voice is clean synthetic speech. Noise, fast talkers and Tuscan *gorgia*
  are what phase 5's corpus is for.

### While it works

One tap on *Transcribe* decodes and then transcribes. The card leads with the transcript; the
file's technical fields and the decode controls sit under *Details*. While it runs, the card
shows the stage, a progress bar, how much of the note has been heard, an estimate of the time left,
and the text recognised so far. The engine reports the share of audio fed plus the recognizer's
partial and committed results, throttled to four updates a second. At the default 2× feed, text
arrives at about twice speaking pace, faster than it can be read, so the wait is only for the first
words.

`ERROR_NO_MATCH` and `ERROR_SPEECH_TIMEOUT` mean "no more speech". Since that change, a silent note
reads *No speech recognised* instead of failing, and a note ending in a long silence keeps its
segments instead of being thrown away. Checked on the Pixel: the silent test tone finishes in 5.5 s
with *No speech recognised in this note.* The trailing-silence case has not been reproduced on a
real note.

## Phase 4: whisper.cpp (`whisper-q5`, `whisper-q8`)

`WhisperEngine` runs whisper.cpp large-v3-turbo on the CPU through a small JNI bridge
(`app/src/main/cpp/whisper_jni.c`), modelled on whisper.cpp's Android example:

- **Settings.** Italian forced, a short punctuated Italian prompt, greedy decoding with whisper.cpp's
  temperature fallback, and Silero v6.2.0 voice detection, so silence never reaches the decoder.
  Flash attention is on (whisper.cpp's default).
- **Build.** ggml is compiled for `armv8.2-a+dotprod+i8mm+fp16`, always optimised, without OpenMP,
  arm64 only, and 16 KB page-aligned.
- **While it runs.** Live text arrives per finished segment, there is a progress bar, and
  cancelling stops the native run through ggml's abort callback.
- **Text across JNI.** It crosses as UTF-8 bytes, since Whisper can split a character across
  tokens.
- **Diagnostics.** whisper.cpp's own log and its timing breakdown go to `adb logcat -s whisper.cpp`.
  Each result is one line on `adb logcat -s Transcription`.

**Models.** `ModelStore` downloads into app-private storage. It resumes interrupted downloads with
HTTP Range requests (Hugging Face's CDN cut both large downloads off mid-transfer during
development), checks free space first, and trusts a file only after its SHA-256 matches Hugging
Face's published value, checking it once and recording that. `WhisperModelCache` keeps one model
loaded between notes and frees it after 3 idle minutes.

| Model | Size | SHA-256 |
| --- | --- | --- |
| `ggml-large-v3-turbo-q5_0.bin` (removed) | 574 MB | `394221709cd5…` |
| `ggml-large-v3-turbo-q8_0.bin` | 874 MB | `317eb69c1167…` |
| `ggml-silero-v6.2.0.bin` | 0.9 MB | `2aa269b785ee…` |

### Speed on the Pixel: the 3:04 synthetic note

| Model, threads | Wall time | RTF | Encoder | Decoder | Strict WER |
| --- | --- | --- | --- | --- | --- |
| q5, 4 | ~213 s | ~1.17 | — | — | 1.9% |
| q5, 6 | 188 s | 1.02 | 130.7 s (18.7 s per window) | 24.9 s | 1.7% |
| **q8, 6** | **101 s** | **0.55** | 49.7 s (7.1 s per window) | 29.3 s | 2.1% |
| q8, 6, KleidiAI | 103 s | 0.56 | 49.3 s (8 windows) | 38.0 s | 2.7% |

- **q8 is the fast one on this chip.** ggml has repacked Arm int8 kernels for q8_0 and none for
  q5_0, so q5's encoder runs 2.6× slower despite the smaller file.
- **6 threads beat 4 by 13%.** That is one per fast core of the Tensor G5; 6 is the default.
- **KleidiAI was tried and left off.** Faster encoder windows, slower decoding, and one extra decode
  pass.

Every strict-WER difference on this note is formatting (digits for spoken numbers, "back end"),
apart from one inserted word with q8. A 7-second clip costs a full 30-second encoder window, so
short notes run at a worse RTF (3.4 for q5 on 4 threads).

### Accuracy on real notes

Four real WhatsApp voice notes (0:16, 0:16, 0:29 and 1:42; fast speech, slang, names), run through
`android-sr` at 2× and `whisper-q8` on 6 threads. They are private and not in the repository. Times
first:

| Note length | android-sr | whisper-q8 |
| --- | --- | --- |
| 0:16 | 8.5 s | 10.8 s |
| 0:16 | 8.1 s | 9.7 s |
| 0:29 | 14.6 s | 15.0 s |
| 1:42 | 51.0 s | 50.8 s |

With voice detection skipping pauses, Whisper q8 matches android-sr's speed on real notes.
Read side by side, android-sr changed the meaning in several places: negations dropped, a verb
swapped for a similar-sounding one, a name turned into another name, a whole clause replaced with
an unrelated phrase. Whisper q8 kept the meaning throughout, spelled names far better and
punctuated properly. Its errors were single words in slang and dialect. Four notes is a small
sample; phase 5's bench is the real measure.

android-sr also masked swear words by default ("c\*\*\*\*"). It now sets
`EXTRA_MASK_OFFENSIVE_WORDS = false`.

### Silence

The plan's done-when for this phase is that a clip full of silences produces no invented text. 20 s
of quiet room noise (`roomtone_20s`) and the pure 220 Hz test tone both came back empty with
whisper-q8. Voice detection found no speech, so the decoder never ran (1.6 s for the 20 s clip).

**Not measured yet:** battery drain and heat. Whisper keeps six cores busy for about half the
note's length.

## Choosing an engine, fallback and cloud

A newly shared note shows two large buttons, **Local** (Whisper q8) and **Cloud**, with *Use
Android's recognizer* below them as a smaller text button. Without a cloud key, *Cloud* opens the
key setup instead. Afterwards the card's ⋮ menu runs the note again with any engine, and a note
keeps one transcript per engine, switchable with chips. Notes are listed newest first, and a newly
shared one scrolls into view at the top.

**q5 removed.** It was slower than q8 on the Tensor G5 (see Phase 4) and took 574 MB. The app
deletes any model file it no longer uses when it starts.

**Fallback.** When Whisper q8 is chosen but cannot run, the note goes to Android's recognizer and the
transcript says why:

- *Model not downloaded yet* (first run, or after the files were deleted). The download starts in
  the background with its own progress banner, and the next note uses Whisper.
- *Whisper failed* (any error from loading or running it).

Checked on the Pixel by deleting the voice-detection model: the note came back from android-sr with
the note, and the app downloaded and verified the model again by itself.

**Cloud.** There is no provider to pick. In Settings you paste a key and tap *Find models*, and:

1. The key's prefix says whose it is: `sk-or-` OpenRouter, `gsk_` Groq, `sk-` OpenAI. A key with no
   prefix is taken to be Mistral's.
2. The app lists that provider's transcription models with the key. This also checks the key, so
   a wrong one fails here and no note is sent with it.
3. You pick a model and save. The suggested model comes first and is preselected.

When a key is saved, *Change model* lists its models again without retyping it.

| Provider | Listing | Upload | Suggested |
| --- | --- | --- | --- |
| OpenRouter | `/api/v1/models?output_modalities=transcription` (30 of its 458 models in Nov 2026); the key is checked with `/api/v1/key`, since the list is public | JSON with the WAV base64-encoded in `input_audio` | `mistralai/voxtral-mini-transcribe` |
| Groq | `/openai/v1/models`, ids containing `whisper` | multipart | `whisper-large-v3` |
| OpenAI | `/v1/models`, ids containing `transcribe` or `whisper` | multipart | `gpt-4o-transcribe` |
| Mistral | `/v1/models`, `voxtral` ids except realtime ones | multipart | `voxtral-mini-latest` |

Each upload is the decoded note as 16 kHz mono WAV, with `language=it`. That is about 6 MB for 3
minutes, or 8 MB once base64-encoded for OpenRouter. If mobile data matters, sending the original
Opus file would cut it about 17×. A search box appears when a key offers more than 8 models.

The cloud is never automatic: it runs only when chosen, and asks *Send to the cloud?* before every
note. The API key is typed in Settings, encrypted with an AES-GCM key held in the Android Keystore
(`KeyVault`), and never shown again. Checked without a key: the consent dialog, then a clear error.
**Not checked with a real key yet.**

## History

Every finished transcript goes into a history, the clock icon in the top bar. It is kept in
`files/history.json` in app-private storage: the file name, the duration, when it was made, the
engine, any fallback note and the text. The audio is not kept. Notes disappear from the main screen
when the app is closed, and their transcripts stay in the history until deleted, one at a time
(with a confirmation) or all at once. The history file is the only copy. Earlier versions also
wrote loose `files/transcripts/*.txt` copies, and the app deletes those when it starts. Backups
stay off (`allowBackup="false"`).

Checked on the Pixel: transcribe, close the app, reopen it, find the transcript in History, delete
one, delete all. After *Delete all*, `history.json` is gone.

## Background transcription

Leaving the app used to stop a transcription: Android's cached-app freezer suspended the process
within seconds. Now:

- `TranscriptionHub` holds the job and its state for the whole app, not the screen, so a run
  outlives the activity.
- `TranscriptionService` is a foreground service that exists only while something is running: a
  transcription or a model download. The hub starts it when work begins, updates its
  notification (stage and percentage), and stops it the moment the work ends. It does no work
  itself. Type `mediaProcessing` on Android 15+, `dataSync` on 14.
- If no screen of the app is showing when the work ends, it posts *Transcript ready*; tapping it
  opens the note. The notification permission is asked for on the first *Transcribe*, not at launch.
  Declining it hides the notifications but transcription still runs.

Checked on the Pixel with the 3:04 note, pressing Home right after *Transcribe*: the service ran with
the process unfrozen, the run completed, the service stopped and *Transcript ready* appeared.
**Background runs are slower:** 195 s (RTF 1.06) against 101 s in the foreground, most likely
because Android schedules background processes on slower cores.

Two traps from building this, both crashes on the Pixel:

- **Don't use `ServiceCompat.startForeground`** (androidx core 1.19) on Android 17. It took its
  Android 14 path, dropped the `mediaProcessing` type, and Android refused a foreground service of
  type "none". The service calls the platform's `startForeground` directly.
- **`rememberBottomSheetState(initial, values)`** takes the set of states the sheet may take, not the
  states to skip. It must contain `Expanded`. Passing `setOf(PartiallyExpanded)` crashed the app on
  opening Settings or Details.

## Look

Material 3 1.5.0-rc01 Expressive: `MaterialExpressiveTheme` with the expressive motion scheme and
the wallpaper's dynamic colours, a collapsing flexible top bar with the engine as subtitle,
`MaterialShapes` cookies for each note's badge, a `LoadingIndicator` while a note works, and a wavy
progress bar. WhatsApp's `PTT-YYYYMMDD-WA0001` names show as *Voice note · 6 Nov 2026*.

## Test fixtures and device scripts

`bench/fixtures/tones/` — ffmpeg sine tones used to check decoding against ffmpeg: four short ones
(Opus, M4A, MP3, ADTS) and two of 3:04 (`long_aac.m4a`, `long_mp3.mp3`) for decode timing.

`bench/fixtures/synthetic/` — Italian speech with a known transcript, written for this project and
voiced by ElevenLabs (`eleven_multilingual_v2`, voice *George*):

- `short_it` — 7.2 s.
- `full_it` — 3:04: `long_it`, then 3 s of silence, then the afterthought `ps_it`.
- `*.mp3` are the ElevenLabs masters; `*.opus.ogg` are encoded like a WhatsApp note:
  `ffmpeg -i x.mp3 -c:a libopus -b:a 16k -ar 16000 -ac 1 -application voip x.opus.ogg`.
- `*.txt` are the ground truth. `full_it.txt` is `long_it.txt` followed by `ps_it.txt`.
- `roomtone_20s.opus.ogg` — 20 s of quiet pink noise, for the "no invented text" check.

`tools/device/` — Python scripts that drive the app on a phone over adb: share a clip, tap through
the real UI, pull the result and score it. Set `ANDROID_SERIAL` when adb lists the phone twice.
Run them with `python -I -X utf8`; `-X utf8` because transcripts contain accents.

- `verify_decode.py <clips> <out>` — decodes the tone clips and compares them with ffmpeg.
- `verify_transcribe.py <clip> <truth.txt> <out> <timeout s>` — one transcription, scored.
- `batch_transcribe.py <clip> <truth.txt|-> <out> <audio s> <engine[:key=value,…]>...` —
  back-to-back runs, so the phone never idles long enough to lock. Example runs:
  `android-sr:speed=2` and `whisper-q8:threads=6`. Pass `-` instead of a truth file to report
  without scoring.
- `play_check.py` — confirms *Play WAV* really plays.
- `eleven.py <key file> quota|voices|tts …` — generates more fixtures. It reads the key from the
  file you name and never prints it.

The scripts expect the clips in the app's own storage, because the app's sandbox cannot read files
the shell writes anywhere else:

```bash
base64 -w0 bench/fixtures/synthetic/full_it.opus.ogg | adb shell "run-as dev.junjo.eztranscribe sh -c 'base64 -d > files/full_it.opus.ogg'"
```

Debug builds accept these extras on the share intent; release builds ignore them:

- `--es dev.junjo.eztranscribe.ENGINE <id>`
- `--ef dev.junjo.eztranscribe.FEED_SPEED <speed>`
- `--ei dev.junjo.eztranscribe.THREADS <n>`

Use the phone's stable mDNS serial (`adb mdns services`) rather than `ip:port`, which changes.
