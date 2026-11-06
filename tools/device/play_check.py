"""Confirm Play WAV really plays: the button flips to Stop, the audio service lists a started
player for the app's uid, and completion flips the button back."""
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from verify_decode import DEVICE_FILES, PKG, shell, tap, wait_for  # noqa: E402

shell(f"am force-stop {PKG}")
shell(f"am start -a android.intent.action.SEND -t audio/ogg "
      f"--eu android.intent.extra.STREAM file://{DEVICE_FILES}/test_opus.ogg -n {PKG}/.MainActivity")
tap(wait_for(["Decode to 16 kHz"], 20), "Decode to 16 kHz")
tap(wait_for(["Play WAV"], 90), "Play WAV")

nodes = wait_for(["Stop"], 10)
print("button after tapping Play:", "Stop" if any(t == "Stop" for t, _ in nodes) else "unchanged")

uid = re.search(r"uid:(\d+)", shell(f"pm list packages -U {PKG}")).group(1)
players = shell("dumpsys audio | grep 'u/pid:' ; true")
ours = [line.strip() for line in players.splitlines() if f"u/pid:{uid}/" in line]
print(f"app uid {uid}; audio service players for it:")
for line in ours:
    print("   ", line[:160])

started = time.time()
wait_for(["Play WAV"], 15)
print(f"button back to Play WAV after {time.time() - started:.1f} s (clip is 6.0 s)")
