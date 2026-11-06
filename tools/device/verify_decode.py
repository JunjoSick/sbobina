"""Drive the app on the device: share each fixture clip, tap Decode, pull the WAV, compare it with
ffmpeg's decode of the same source. Usage: python verify_decode.py <clips_dir> <out_dir>"""
import base64
import math
import os
import re
import shutil
import subprocess
import sys
import time
import wave
from array import array
from pathlib import Path

# adb from PATH, else the SDK default on this machine. ANDROID_SERIAL picks the device when more
# than one transport is attached; wireless adb often lists the same phone twice.
ADB = shutil.which("adb") or r"C:\Users\gangi\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = os.environ.get("ANDROID_SERIAL")
PKG = "dev.junjo.eztranscribe"
DEVICE_FILES = f"/data/data/{PKG}/files"

# name, MIME the "sender" claims, tone frequency ffmpeg generated, seconds generated
CLIPS = [
    ("test_opus.ogg", "audio/ogg", 220.0, 6.0),
    ("test_aac.m4a", "audio/mpeg", 330.0, 4.0),
    ("test_mp3.mp3", "audio/mpeg", 440.0, 3.0),
    ("test_adts.aac", "audio/aac", 550.0, 2.0),
]

NODE = re.compile(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def adb(*args):
    result = subprocess.run([ADB, *(["-s", SERIAL] if SERIAL else []), *args], capture_output=True)
    if result.returncode != 0:
        raise RuntimeError(f"adb {args}: {result.stderr.decode(errors='replace')}")
    return result.stdout


def shell(command):
    return adb("shell", command).decode("utf-8", "replace")


def screen():
    xml = shell("uiautomator dump /sdcard/dump.xml >/dev/null 2>&1; cat /sdcard/dump.xml")
    return [(m.group(1), tuple(int(v) for v in m.group(2, 3, 4, 5))) for m in NODE.finditer(xml)]


def wait_for(any_of, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = screen()
        if any(text.startswith(want) for text, _ in nodes for want in any_of):
            return nodes
        time.sleep(0.5)
    raise TimeoutError(f"none of {any_of} appeared within {timeout} s")


def tap(nodes, text):
    for node_text, (x1, y1, x2, y2) in nodes:
        if node_text == text:
            shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            return
    raise LookupError(f"no node with text {text!r}")


# The button on a new note's card that starts each engine.
ENGINE_BUTTONS = {"whisper-q8": "Local", "cloud": "Cloud", "android-sr": "Use Android's recognizer"}


def start_transcription(engine, timeout=30):
    """Taps the card's button for [engine]; the cloud also asks for consent."""
    button = ENGINE_BUTTONS[engine]
    tap(wait_for([button], timeout), button)
    if engine == "cloud":
        tap(wait_for(["Send"], 10), "Send")


def latest_transcript():
    """The newest transcript in the app's history file (files/history.json), newest first."""
    import base64
    import json
    raw = base64.b64decode(adb("shell", f"run-as {PKG} base64 files/history.json"))
    return json.loads(raw.decode("utf-8"))[0]["text"].strip()


def field(nodes, label):
    texts = [t for t, _ in nodes]
    return texts[texts.index(label) + 1] if label in texts else None


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        rate, channels, width, frames = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        raw = w.readframes(frames)
    assert width == 2, f"{path}: expected 16-bit, got {8 * width}-bit"
    pcm = array("h", raw)
    return rate, channels, [s / 32768.0 for s in pcm]


def tone_frequency(samples, rate):
    """Rising zero crossings per second over the middle 80%."""
    mid = samples[len(samples) // 10: len(samples) - len(samples) // 10]
    crossings = sum(1 for a, b in zip(mid, mid[1:]) if a < 0 <= b)
    return crossings * rate / len(mid)


def first_sound(samples, threshold=0.01):
    return next((i for i, s in enumerate(samples) if abs(s) > threshold), len(samples))


def compare(ours, ref):
    """Best alignment lag (samples, positive = ours is late) and SNR in dB over the overlap."""
    try:
        import numpy as np
    except ImportError:
        return None
    a, b = np.asarray(ours), np.asarray(ref)
    n = 1 << (len(a) + len(b)).bit_length()
    corr = np.fft.irfft(np.fft.rfft(a, n) * np.conj(np.fft.rfft(b, n)), n)
    max_lag = 4000
    lags = np.r_[0:max_lag + 1, -max_lag:0]
    values = np.r_[corr[0:max_lag + 1], corr[n - max_lag:n]]
    lag = int(lags[int(np.argmax(values))])
    a_aligned, b_aligned = (a[lag:], b) if lag >= 0 else (a, b[-lag:])
    length = min(len(a_aligned), len(b_aligned))
    # Skip the edges, where filters and codec priming differ by design.
    edge = length // 10
    x, y = a_aligned[edge:length - edge], b_aligned[edge:length - edge]
    noise = np.sum((x - y) ** 2)
    snr = float("inf") if noise == 0 else 10 * math.log10(np.sum(y ** 2) / noise)
    return lag, snr


def main():
    clips_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for name, mime, tone, seconds in CLIPS:
        shell(f"am force-stop {PKG}")
        shell(f"am start -a android.intent.action.SEND -t {mime} "
              f"--eu android.intent.extra.STREAM file://{DEVICE_FILES}/{name} -n {PKG}/.MainActivity")
        # The decode controls sit in the Details sheet; the card leads with the transcript.
        tap(wait_for(["Details"], 20), "Details")
        tap(wait_for(["Decode to 16 kHz"], 20), "Decode to 16 kHz")
        nodes = wait_for(["Play WAV", "Decode failed"], 90)

        failure = next((t for t, _ in nodes if t.startswith("Decode failed")), None)
        if failure:
            rows.append({"clip": name, "failure": failure})
            continue

        stem = name.rsplit(".", 1)[0]
        ours_path = out_dir / f"{stem}.16k.wav"
        ours_path.write_bytes(base64.b64decode(adb("shell", f"run-as {PKG} base64 files/decoded/{stem}.16k.wav")))
        ref_path = out_dir / f"{stem}.ffmpeg16k.wav"
        # Opus must be decoded with libopus, which Android's decoder wraps. ffmpeg's built-in Opus
        # decoder differs from it on SILK-coded speech (24 dB SNR at 48 kHz on full_it), while the
        # app matches libopus to 78 dB.
        decoder = ["-c:a", "libopus"] if name.endswith((".ogg", ".opus")) else []
        subprocess.run(["ffmpeg", "-v", "error", "-y", *decoder, "-i", str(clips_dir / name),
                        "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(ref_path)], check=True)

        rate, channels, ours = read_wav(ours_path)
        ref_rate, _, ref = read_wav(ref_path)
        assert ref_rate == 16000
        rows.append({
            "clip": name,
            "screen_decoded": field(nodes, "Decoded"),
            "screen_source": field(nodes, "Decoder output"),
            "screen_peak": field(nodes, "Peak"),
            "screen_time": field(nodes, "Time"),
            "wav_rate": rate,
            "wav_channels": channels,
            "expected_s": seconds,
            "ours_s": len(ours) / rate,
            "ffmpeg_s": len(ref) / ref_rate,
            "expected_hz": tone,
            "ours_hz": tone_frequency(ours, rate),
            "ours_peak": max(abs(s) for s in ours),
            "ffmpeg_peak": max(abs(s) for s in ref),
            "ours_onset_ms": 1000 * first_sound(ours) / rate,
            "ffmpeg_onset_ms": 1000 * first_sound(ref) / ref_rate,
            "vs_ffmpeg": compare(ours, ref),
        })

    for row in rows:
        print("=" * 72)
        for key, value in row.items():
            if isinstance(value, float):
                value = f"{value:.4f}"
            elif key == "vs_ffmpeg" and value is not None:
                lag, snr = value
                value = f"lag {lag} samples ({1000 * lag / 16000:+.1f} ms), SNR {snr:.1f} dB"
            print(f"  {key:16} {value}")


if __name__ == "__main__":
    main()
