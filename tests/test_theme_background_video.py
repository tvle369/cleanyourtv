"""tools/theme.sh pushes a theme's background.mp4, verifies it landed intact, and cleans it up correctly.

background.mp4 is pushed and verified like wallpaper.jpg (staged with b64push.py, written through the
launcher's ThemeProvider or cp), plus one more step wallpaper.jpg does not get: an explicit size and md5
check of the file that actually landed in the launcher's folder. A theme without a background.mp4 removes
any stale one left by an earlier theme, and --reset removes it too - otherwise a video from a theme pushed
hours ago could keep playing under a theme, or a reset, that never shipped one.

These tests run the real tools/theme.sh against a stub tools/tv.py whose "TV shell" is /bin/sh on this
machine and a stub `content` command standing in for ThemeProvider, the same harness
tests/test_theme_push_ownership.py uses, so the push, the verification and the cleanup are the real
shell commands theme.sh builds, not a description of them.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
DEVICE_DIR = "/sdcard/Android/data/com.gathin.tvhome/files"

# --push copies like tv.py does; shell commands run on this machine after the device paths are rewritten.
FAKE_TV = r'''
import json
import os
import subprocess
import sys

LOG = os.environ["FAKE_LOG"]
DIR = os.environ["FAKE_DIR"]
TMP = os.environ["FAKE_STAGE"]
DEVICE_DIR = "/sdcard/Android/data/com.gathin.tvhome/files"


def local(command):
    return command.replace(DEVICE_DIR, DIR).replace("/data/local/tmp/cyt-theme", TMP)


class Device:
    max_chunk_size = 4096

    def shell(self, command, read_timeout_s=None):
        with open(LOG, "a") as f:
            f.write(json.dumps({"shell": command}) + "\n")
        return subprocess.run(["/bin/sh", "-c", local(command)],
                              capture_output=True, text=True).stdout

    def close(self):
        pass


def connect():
    return Device()


def connect_error(error):
    return None


args = sys.argv[1:]
if args and args[0] == "--push":
    src, dst = args[1], local(args[2])
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(src, "rb") as f, open(dst, "wb") as g:
        g.write(f.read())
else:
    # Matches tv.py's own plain-command path (tools/tv.py main(), the final else branch): write the
    # shell's raw output to this process's stdout, so tv() in theme.sh can capture it via $(...).
    sys.stdout.write(Device().shell(" ".join(args)))
'''

# Stands in for `content write/delete` against content://com.gathin.tvhome.theme (ThemeProvider), which
# always succeeds in this fixture: push-ownership edge cases belong to test_theme_push_ownership.py, not here.
FAKE_CONTENT = r'''#!/usr/bin/env python3
import os
import sys

args = sys.argv[1:]
action, uri = args[0], args[args.index("--uri") + 1]
path = os.path.join(os.environ["FAKE_DIR"], uri.split("com.gathin.tvhome.theme/", 1)[1])
if action == "write":
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(sys.stdin.buffer.read())
elif action == "delete":
    if os.path.isdir(path):
        for name in os.listdir(path):
            if name.lower().endswith((".jpg", ".jpeg", ".png", ".webp")):
                os.remove(os.path.join(path, name))
    elif os.path.exists(path):
        os.remove(path)
'''


class ThemeBackgroundVideoTest(unittest.TestCase):
    def setUp(self):
        self.base = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.base, ignore_errors=True)
        self.tools = self.base / "tools"
        self.tools.mkdir()
        for name in ("theme.sh", "b64push.py", "theme_restart.py"):
            shutil.copy2(TOOLS / name, self.tools / name)
        (self.tools / "tv.py").write_text(FAKE_TV)
        venv = self.tools / "venv" / "bin"
        venv.mkdir(parents=True)
        (venv / "python").symlink_to(sys.executable)
        self.bin = self.base / "bin"
        self.bin.mkdir()
        content = self.bin / "content"
        content.write_text(FAKE_CONTENT)
        content.chmod(0o755)
        # The recovery helper's real shell status checks; keeps the fixture focused on background.mp4.
        for name, body in {
            "am": 'if [ "$1" = get-current-user ]; then echo 0; fi',
            "settings": 'if [ "$3" = get ]; then echo null; fi',
            "cmd": 'echo com.gathin.tvhome/.MainActivity',
        }.items():
            command = self.bin / name
            command.write_text('#!/bin/sh\n' + body + '\n')
            command.chmod(0o755)
        self.device = self.base / "device"
        self.device.mkdir()
        self.stage = self.base / "stage"
        self.log = self.base / "log.jsonl"
        self.theme = self.base / "aurora"
        self.theme.mkdir()
        (self.theme / "config.json").write_text('{"background_color": "#101418"}\n', encoding="utf-8")

    def run_theme(self, *args, **env):
        if os.name != "posix" or not shutil.which("bash"):
            raise unittest.SkipTest("theme.sh is a POSIX script; it needs bash")
        full = {k: v for k, v in os.environ.items() if k != "TV_HOST"}
        full.update({"TV_HOST": "192.0.2.10", "FAKE_LOG": str(self.log), "FAKE_DIR": str(self.device),
                     "FAKE_STAGE": str(self.stage),
                     "PATH": f"{self.bin}:{full.get('PATH', '')}"}, **env)
        return subprocess.run(["bash", str(self.tools / "theme.sh"), *args], env=full,
                              capture_output=True, text=True, timeout=300)

    def test_a_background_video_is_pushed_and_verified(self):
        video = os.urandom(4096)
        (self.theme / "background.mp4").write_bytes(video)
        result = self.run_theme(str(self.theme))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("is on the TV", result.stdout)
        landed = self.device / "background.mp4"
        self.assertTrue(landed.is_file(), "background.mp4 did not land in the device folder")
        self.assertEqual(landed.read_bytes(), video, "the file on the TV must be byte-identical to the theme's")
        self.assertNotIn("did not verify", result.stderr, result.stderr)

    def test_a_corrupted_landing_is_caught_by_the_verification(self):
        # A push that silently truncates or corrupts the file must fail loudly, not print "is on the TV".
        # Simulated by a content stub that writes the wrong bytes, so the size/md5 check this test exists
        # for is the thing that catches it.
        video = os.urandom(4096)
        (self.theme / "background.mp4").write_bytes(video)
        truncating_content = FAKE_CONTENT.replace(
            "f.write(sys.stdin.buffer.read())", "f.write(sys.stdin.buffer.read()[:100])")
        (self.bin / "content").write_text(truncating_content)
        result = self.run_theme(str(self.theme))
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("did not verify", result.stderr, result.stderr)
        self.assertNotIn("is on the TV", result.stdout)

    def test_a_theme_without_a_background_video_removes_a_stale_one(self):
        stale = os.urandom(2048)
        (self.device / "background.mp4").write_bytes(stale)
        result = self.run_theme(str(self.theme))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.device / "background.mp4").exists(),
                         "a theme with no background.mp4 of its own must remove a previous one")

    def test_reset_removes_the_background_video(self):
        (self.device / "background.mp4").write_bytes(os.urandom(2048))
        result = self.run_theme("--reset")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual("reset", result.stdout.strip())
        self.assertFalse((self.device / "background.mp4").exists(), "--reset must remove background.mp4 too")

    def test_local_md5_matches_hashlib(self):
        # A sanity check on the test's own expectations, independent of theme.sh: the file this harness
        # writes really does hash the way theme.sh's "$PY" -c hashlib snippet would compute it.
        video = b"some bytes, not a real video"
        (self.theme / "background.mp4").write_bytes(video)
        self.assertEqual(hashlib.md5(video).hexdigest(), hashlib.md5((self.theme / "background.mp4").read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
