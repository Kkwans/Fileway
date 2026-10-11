#!/usr/bin/env python3
"""Exercise the pinned emulator runner's independent `sh -c` lines.

Only shell ordering/status/export is tested here, never Android playback.
"""
import os
import json
from pathlib import Path
import subprocess
import tempfile


def commands():
    workflow = Path(__file__).resolve().parents[3] / ".github/workflows/android.yml"
    block = workflow.read_text().split("          script: |\n", 1)[1].split("      - uses:", 1)[0]
    return [line[12:] for line in block.splitlines() if line.strip()]


def check(api, target, page_size, test_status, export_status, expected, should_test=True, test_methods=""):
    with tempfile.TemporaryDirectory(prefix="nfb-emulator-script-", dir=os.environ.get("NFB_CHECK_TMP")) as folder:
        root = Path(folder)
        component = root / "clients/android"
        component.mkdir(parents=True)
        (component / "scripts").mkdir()
        (component / "scripts/run-emulator-checks.py").write_text('''import os, json, sys
from pathlib import Path
Path("received-argv.json").write_text(json.dumps(sys.argv[1:]))
Path("test-called").touch()
Path("export-called").touch()
raise SystemExit(int(os.environ["NFB_FAKE_TEST_STATUS"]) or int(os.environ["NFB_FAKE_EXPORT_STATUS"]))
''')
        (root / "adb").write_text('''#!/bin/sh
case "$1" in
  shell) printf '%s\n' "$NFB_FAKE_PAGESIZE" ;;
  get-serialno) printf '%s\n' emulator-5556 ;;
  *) exit 92 ;;
esac
''')
        (root / "adb").chmod(0o700)
        env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                   NFB_FAKE_TEST_STATUS=str(test_status), NFB_FAKE_EXPORT_STATUS=str(export_status), NFB_FAKE_PAGESIZE=str(page_size),
                   NFB_TEST_METHODS=test_methods)
        status = 0
        for line in commands():
            line = line.replace("${{ matrix.api }}", api).replace("${{ matrix.target }}", target)
            status = subprocess.run(["sh", "-c", line], cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode
            if status:
                break
        assert status == expected, (api, page_size, test_status, export_status, status, expected)
        assert (component / "test-called").exists() == should_test
        assert (component / "export-called").exists() == should_test
        if should_test:
            assert json.loads((component / "received-argv.json").read_text()) == ["--serial", "emulator-5556", "--test-methods", test_methods]
        assert not (component / "injected-owned").exists()


if __name__ == "__main__":
    for api, target, pages in [("35", "google_apis", 4096), ("37.0", "google_apis_ps16k", 16384)]:
        for tests, export, expected in [(0, 0, 0), (7, 0, 7), (0, 1, 1), (7, 1, 7)]:
            check(api, target, pages, tests, export, expected)
    check("37.0", "google_apis_ps16k", 4096, 0, 0, 1, should_test=False)
    check("35", "google_apis", 4096, 0, 0, 0,
          test_methods="io.github.kkwans.nasfilebrowser.OwnedTest#first,io.github.kkwans.nasfilebrowser.OtherTest#second")
    # Shell contract only: malformed input still arrives as one literal argv
    # value, then the real runner's independent canonical validation rejects it.
    check("35", "google_apis", 4096, 0, 0, 0, test_methods="$(touch injected-owned);`touch injected-owned`")
    print("11 emulator-script ordering, export, status, page-size and safe-selector-argv checks passed")
