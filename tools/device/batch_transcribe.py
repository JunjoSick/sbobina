"""Run several transcriptions back to back, so the phone never idles long enough to lock, and score
each against its ground truth.

Usage: python batch_transcribe.py <clip on device> <ground truth .txt> <out dir> <audio s> <run>...
where each <run> is an engine id with optional settings, for example:
    android-sr:speed=2   whisper-q8:threads=6   cloud
"""
import base64
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from verify_decode import DEVICE_FILES, PKG, adb, shell, latest_transcript, start_transcription, tap, wait_for  # noqa: E402
from verify_transcribe import align, normalise  # noqa: E402

EXTRA = "dev.junjo.eztranscribe."


def locked():
    return "isKeyguardShowing=true" in shell("dumpsys window | grep isKeyguardShowing ; true")


def last_field(nodes, label):
    texts = [t for t, _ in nodes]
    if label not in texts:
        return None
    index = len(texts) - 1 - texts[::-1].index(label)
    return texts[index + 1] if index + 1 < len(texts) else None


def dropped_spans(ops, ref, min_len=3):
    """Runs of consecutive reference words missing from the transcript: where audio was skipped."""
    spans, run = [], []
    for kind, index, _, _ in ops:
        if kind == "del":
            run.append(index)
        elif kind != "ins":
            if len(run) >= min_len:
                spans.append(run)
            run = []
    if len(run) >= min_len:
        spans.append(run)
    return [(s[0] + 1, s[-1] + 1, " ".join(ref[i] for i in s)) for s in spans]


def parse_run(spec):
    engine, _, rest = spec.partition(":")
    settings = dict(item.split("=", 1) for item in rest.split(",") if item)
    return engine, settings


def run_once(clip, ref, out_dir, audio_s, engine, settings):
    extras = f"--es {EXTRA}ENGINE {engine}"
    if "speed" in settings:
        extras += f" --ef {EXTRA}FEED_SPEED {settings['speed']}"
    if "threads" in settings:
        extras += f" --ei {EXTRA}THREADS {settings['threads']}"

    shell("logcat -c")
    shell(f"am force-stop {PKG}")
    shell(f"am start -a android.intent.action.SEND -t audio/ogg {extras} "
          f"--eu android.intent.extra.STREAM file://{DEVICE_FILES}/{clip} -n {PKG}/.MainActivity")
    # One tap on the engine's button: the app decodes on its own.
    start_transcription(engine)

    started = time.time()
    # Model loading, and a first-run checksum of a 900 MB model, come before transcription starts.
    # "Copy" appears with a finished transcript; the numbers come from the app's log line.
    nodes = wait_for(["Copy", "Transcription failed"], int(audio_s * 3) + 240)
    wall = time.time() - started
    failure = next((t for t, _ in nodes if t.startswith("Transcription failed")), None)
    if failure:
        return {"run": f"{engine} {settings}", "failure": failure, "wall_s": round(wall, 1)}
    done = shell("logcat -d -s Transcription:I ; true").strip().splitlines()
    summary = done[-1].split("done ", 1)[-1] if done else "(no result line in the log)"
    # whisper.cpp's own breakdown (encoder, decoder, per-run totals), when the engine is Whisper.
    timings = [line.split(": ", 1)[-1].strip() for line in shell("logcat -d -s whisper.cpp ; true").splitlines()
               if " time = " in line or "system_info" in line or "CPU :" in line]

    stem = re.sub(r"[^A-Za-z0-9._-]", "_", clip.rsplit(".", 1)[0])
    transcript = latest_transcript()
    tag = "_".join(f"{k}{v}" for k, v in settings.items())
    (out_dir / f"{stem}.{engine}{'.' + tag if tag else ''}.txt").write_text(transcript + "\n", encoding="utf-8")

    hyp = normalise(transcript)
    if ref is None:
        # A real note with no reference transcript: report it unscored, for reading side by side.
        return {"run": f"{engine} {settings}", "result": summary, "timings": timings,
                "words": len(hyp), "transcript": transcript}
    errors, ops, last = align(ref, hyp)
    return {
        "run": f"{engine} {settings}",
        "result": summary,
        "timings": timings,
        "words": len(hyp),
        "wer": errors / len(ref),
        "reaches": f"#{last + 1} of {len(ref)}",
        "dropped": dropped_spans(ops, ref),
        "transcript": transcript,
    }


def main():
    clip, truth, out_dir, audio_s = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]), float(sys.argv[4])
    runs = [parse_run(spec) for spec in sys.argv[5:]]
    out_dir.mkdir(parents=True, exist_ok=True)
    # "-" means there is no reference transcript: results are reported, not scored.
    ref = None if str(truth) == "-" else normalise(truth.read_text(encoding="utf-8"))

    results = []
    for engine, settings in runs:
        if locked():
            print(f"phone locked before the {engine} run; stopping here")
            break
        print(f"--- {clip} with {engine} {settings} ---", flush=True)
        result = run_once(clip, ref, out_dir, audio_s, engine, settings)
        results.append(result)
        for key, value in result.items():
            if key != "transcript":
                print(f"  {key:10} {value:.1%}" if key == "wer" else f"  {key:10} {value}", flush=True)

    for result in results:
        if "transcript" in result:
            print(f"\n=== transcript, {result['run']} ===\n{result['transcript']}")


if __name__ == "__main__":
    main()
