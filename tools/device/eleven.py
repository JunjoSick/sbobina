"""Minimal ElevenLabs client for generating test fixtures. Reads the key from a file and never
prints it. Usage:
  python eleven.py <keyfile> quota
  python eleven.py <keyfile> voices
  python eleven.py <keyfile> tts <voice_id> <text_file> <out.mp3>
"""
import json
import sys
import urllib.error
import urllib.request

API = "https://api.elevenlabs.io/v1"
MODEL = "eleven_multilingual_v2"


def request(key, method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("xi-api-key", key)
    req.add_header("Accept", "audio/mpeg" if path.startswith("/text-to-speech") else "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:500]
        sys.exit(f"HTTP {e.code} on {method} {path}: {detail}")


def main():
    key = open(sys.argv[1], encoding="utf-8").read().strip()
    command = sys.argv[2]

    if command == "quota":
        sub = json.loads(request(key, "GET", "/user/subscription"))
        used, limit = sub.get("character_count"), sub.get("character_limit")
        print(f"tier: {sub.get('tier')}; characters used {used} of {limit}; "
              f"remaining {None if used is None or limit is None else limit - used}")

    elif command == "voices":
        voices = json.loads(request(key, "GET", "/voices")).get("voices", [])
        for v in voices:
            labels = v.get("labels") or {}
            print(f"{v.get('voice_id')}  {v.get('name')!r:32} category={v.get('category')}  labels={labels}")

    elif command == "tts":
        voice_id, text_file, out = sys.argv[3], sys.argv[4], sys.argv[5]
        text = open(text_file, encoding="utf-8").read().strip()
        audio = request(key, "POST", f"/text-to-speech/{voice_id}?output_format=mp3_44100_128", {
            "text": text,
            "model_id": MODEL,
            "voice_settings": {"stability": 0.5, "similarity_boost": 0.75},
        })
        open(out, "wb").write(audio)
        print(f"wrote {len(audio)} bytes for {len(text)} characters to {out}")

    else:
        sys.exit(f"unknown command {command}")


if __name__ == "__main__":
    main()
