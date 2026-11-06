"""Share a speech clip, decode it and transcribe it with android-sr through the real UI, then score
the transcript against the text the clip was generated from.
Usage: python verify_transcribe.py <clip on device> <ground truth .txt> <out dir> <timeout s>"""
import base64
import re
import sys
import time
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from verify_decode import DEVICE_FILES, PKG, adb, shell, latest_transcript, start_transcription, tap, wait_for  # noqa: E402


def normalise(text):
    text = unicodedata.normalize("NFC", text.lower()).replace("'", " ").replace("’", " ")
    return re.sub(r"[^\w\s]", " ", text).split()


def align(ref, hyp):
    """Word-level Levenshtein with backtrace: error counts, aligned pairs, last matched ref index."""
    n, m = len(ref), len(hyp)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]))
    i, j, ops = n, m, []
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i][j] == d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]):
            ops.append(("ok" if ref[i - 1] == hyp[j - 1] else "sub", i - 1, ref[i - 1], hyp[j - 1]))
            i, j = i - 1, j - 1
        elif i > 0 and d[i][j] == d[i - 1][j] + 1:
            ops.append(("del", i - 1, ref[i - 1], ""))
            i -= 1
        else:
            ops.append(("ins", i, "", hyp[j - 1]))
            j -= 1
    ops.reverse()
    matched = [op[1] for op in ops if op[0] == "ok"]
    return d[n][m], ops, (max(matched) if matched else -1)


def main():
    clip, truth_path, out_dir, timeout = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]), int(sys.argv[4])
    out_dir.mkdir(parents=True, exist_ok=True)

    shell("logcat -c")
    shell(f"am force-stop {PKG}")
    shell(f"am start -a android.intent.action.SEND -t audio/ogg "
          f"--eu android.intent.extra.STREAM file://{DEVICE_FILES}/{clip} -n {PKG}/.MainActivity")
    # One tap: the app decodes on its own before transcribing, with android-sr chosen up front.
    shell(f"am force-stop {PKG}")
    shell(f"am start -a android.intent.action.SEND -t audio/ogg --es {PKG}.ENGINE android-sr "
          f"--eu android.intent.extra.STREAM file://{DEVICE_FILES}/{clip} -n {PKG}/.MainActivity")
    start_transcription("android-sr", timeout=20)

    started = time.time()
    # "Copy" appears with a finished transcript.
    nodes = wait_for(["Copy", "Transcription failed"], timeout)
    print(f"finished after {time.time() - started:.1f} s on the wall clock")

    failure = next((t for t, _ in nodes if t.startswith("Transcription failed")), None)
    log = shell("logcat -d -v brief | grep -iE 'AndroidSpeechEngine|SpeechRecognizer|RecognitionService' ; true")
    if failure:
        print("FAILED:", failure)
        print(log[-3000:])
        return

    texts = [t for t, _ in nodes]
    for label in ("Time", "Real-time factor", "Words"):
        # The transcription section sits below the decode section, which has its own "Time".
        index = len(texts) - 1 - texts[::-1].index(label) if label in texts else None
        print(f"  {label:18} {texts[index + 1] if index is not None else None}")
    print("  engine trace:")
    for line in log.splitlines():
        if "AndroidSpeechEngine" in line and "ms " in line:
            print("    ", line.split(": ", 1)[-1][:150])

    stem = re.sub(r"[^A-Za-z0-9._-]", "_", clip.rsplit(".", 1)[0])
    transcript = latest_transcript()
    (out_dir / f"{stem}.android-sr.txt").write_text(transcript + "\n", encoding="utf-8")

    ref, hyp = normalise(truth_path.read_text(encoding="utf-8")), normalise(transcript)
    errors, ops, last = align(ref, hyp)
    counts = {kind: sum(1 for op in ops if op[0] == kind) for kind in ("sub", "del", "ins")}
    trailing_missing = len(ref) - 1 - last
    print(f"  reference words    {len(ref)}")
    print(f"  transcript words   {len(hyp)}")
    print(f"  WER (strict)       {errors / len(ref):.1%}  sub {counts['sub']}  del {counts['del']}  ins {counts['ins']}")
    print(f"  coverage           last matched reference word is #{last + 1} of {len(ref)} "
          f"({ref[last] if last >= 0 else '-'}); {trailing_missing} reference word(s) after it")
    print("  differences (ref -> transcript):")
    shown = 0
    for kind, index, r, h in ops:
        if kind != "ok" and shown < 40:
            print(f"    #{index + 1:<4} {kind}  {r or '∅':>18} -> {h or '∅'}")
            shown += 1
    print("\n  transcript as returned:\n")
    print("   ", transcript.replace("\n", "\n    "))


if __name__ == "__main__":
    main()
