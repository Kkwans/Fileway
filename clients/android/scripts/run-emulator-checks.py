#!/usr/bin/env python3
"""Run the full suite/restart gates or an explicit affected-method emulator batch.

UTP removes the target package after connected tests. Reinstall the already built
APKs before the two restart invocations; never use this runner on a personal phone.
"""
import argparse
from collections import Counter
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

PACKAGE = "io.github.kkwans.nasfilebrowser"
RESTART_PACKAGE = f"{PACKAGE}.hostrestart"
RESTART_CLASSES = [f"{RESTART_PACKAGE}.ThemeRestartPrepareTest", f"{RESTART_PACKAGE}.ThemeRestartVerifyTest"]
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
# The growing ordinary suite was still executing tests when both API jobs hit
# the previous 20-minute host limit. This aggregate budget does not change any
# device test's startup, subtitle, pixel or interaction assertion/deadline.
CONNECTED_SUITE_TIMEOUT_SECONDS = 30 * 60
METHOD_SELECTOR = re.compile(re.escape(PACKAGE) + r"(?:\.[A-Za-z_][A-Za-z0-9_]*)*\.[A-Z][A-Za-z0-9_]*#[A-Za-z_][A-Za-z0-9_]*")


def parse_test_methods(value):
    if not isinstance(value, str):
        raise ValueError("Method selection must be a canonical comma-separated string")
    if value == "":
        return ()
    methods = value.split(",")
    if any(not METHOD_SELECTOR.fullmatch(method) for method in methods):
        raise ValueError("Each method must be a canonical package.Class#method, without whitespace or empty entries")
    if len(set(methods)) != len(methods):
        raise ValueError("Duplicate method selectors are forbidden")
    if any(method.split("#", 1)[0].startswith(RESTART_PACKAGE + ".") or
           method.split("#", 1)[0] in [f"{PACKAGE}.ThemeRestartPrepareTest", f"{PACKAGE}.ThemeRestartVerifyTest"] for method in methods):
        raise ValueError("Host restart methods require the full-suite restart protocol")
    return tuple(methods)


def test_methods_regex(methods):
    # AGP 9.4.1 serializes runner arguments as comma-separated key=value;
    # android-test-engine 1.0.1 drops bare comma-separated values. Pinned
    # AndroidJUnitRunner 1.7.0 supports tests_regex on package.Class#method.
    # Canonical identifiers contain no regex metacharacters except dots.
    # Java's absolute anchors also reject a final newline with matcher.find().
    return r"\A(?:" + "|".join(method.replace(".", r"\.") for method in methods) + r")\z"


def suite_contract(component, selected_methods=()):
    scope = "Affected-method batch" if selected_methods else "Full suite"
    reports = list((component / "app/build/outputs/androidTest-results/connected").rglob("TEST-*.xml"))
    if not reports:
        return 1, f"{scope} has no JUnit report\n"
    cases = []
    try:
        for report in reports:
            cases.extend(ET.parse(report).getroot().iter("testcase"))
    except (ET.ParseError, OSError) as error:
        return 1, f"Unreadable {scope.lower()} JUnit: {type(error).__name__}\n"
    if not cases:
        return 1, f"{scope} executed no reported test cases\n"
    misplaced = [case.get("classname", "") for case in cases
                 if case.get("classname", "").startswith(RESTART_PACKAGE + ".")
                 or case.get("classname", "") in [f"{PACKAGE}.ThemeRestartPrepareTest", f"{PACKAGE}.ThemeRestartVerifyTest"]]
    if misplaced:
        return 1, "Host restart tests leaked into the ordinary suite: " + ", ".join(sorted(set(misplaced))) + "\n"
    if selected_methods:
        actual = Counter(f"{case.get('classname', '')}#{case.get('name', '')}" for case in cases)
        missing = sorted(set(selected_methods) - actual.keys())
        extra = sorted(actual.keys() - set(selected_methods))
        duplicate = sorted(method for method, count in actual.items() if count != 1)
        if missing or extra or duplicate:
            return 1, f"Affected-method batch mismatch: missing={missing}, extra={extra}, duplicate={duplicate}\n"
    failed = sum(case.find("failure") is not None or case.find("error") is not None for case in cases)
    skipped = sum(case.find("skipped") is not None for case in cases)
    if failed or skipped:
        return 1, f"{scope} JUnit reports {failed} failed and {skipped} unverified/skipped cases\n"
    if selected_methods:
        return 0, f"Affected-method batch reported exactly {len(cases)} selected methods, all passing; NOT a full-suite result; unchanged theme restart gates were not rerun\n"
    return 0, f"Full suite reported {len(cases)} cases; host restart tests are isolated\n"


def run_command(command, cwd, timeout):
    try:
        result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=timeout)
        return result.returncode, result.stdout
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        return 124, output + "\nHost deadline exceeded\n"


def run_checks(component, serial, run=run_command, test_methods=""):
    selected_methods = parse_test_methods(test_methods)
    if not re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("This runner requires an explicitly selected disposable emulator")
    adb = ["adb", "-s", serial]
    status, output = run(adb + ["shell", "getprop", "ro.kernel.qemu"], component, 30)
    if status or output.strip() != "1":
        raise ValueError("Refusing to install or run tests on a non-emulator target")
    status, output = run(["adb", "devices"], component, 30)
    devices = [line.split() for line in output.splitlines()[1:] if line.strip()]
    if status or devices != [[serial, "device"]]:
        raise ValueError("Connected tests require this emulator to be the only attached device")

    evidence = component / "app/build/acceptance"
    evidence.mkdir(parents=True, exist_ok=True)
    selection = "Affected-method batch (NOT full suite); unchanged theme restart gates are not run\n" if selected_methods else "Full suite and host-managed theme restart gates\n"
    if selected_methods:
        selection += "Selected methods:\n" + "\n".join(selected_methods) + "\n"
    (evidence / "selection-contract.txt").write_text(selection, encoding="utf-8")
    print(selection, end="", flush=True)
    first_failure = 0

    def check(name, command, timeout=120, instrumentation=False):
        nonlocal first_failure
        status, output = run(command, component, timeout)
        if instrumentation and (not re.search(r"(?m)^OK \(1 test\)\s*$", output)
                                or re.search(r"(?m)^(FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED)", output)):
            status = status or 1
        (evidence / f"{name}.txt").write_text(output, encoding="utf-8")
        print(f"{name}: exit {status}", flush=True)
        if status and not first_failure:
            first_failure = status
        return status

    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    command = [wrapper, ":app:connectedDebugAndroidTest", "--stacktrace",
          # AGP/UTP discovery did not preserve the second comma-separated notClass
          # exclusion. A dedicated package is one unambiguous argument value.
          "-Pandroid.testInstrumentationRunnerArguments.notPackage=" + RESTART_PACKAGE]
    if selected_methods:
        # The engine passes this value to adb shell without shell quoting.
        # A generated canonical regex has no quotes; protect its backslashes,
        # parentheses and alternation through the device shell's second parse.
        command.extend(["-Pandroid.testInstrumentationRunnerArguments.tests_regex='" + test_methods_regex(selected_methods) + "'",
                        "-Pandroid.testInstrumentationRunnerArguments.nfbTraceUi=true"])
    suite_status = check("affected-method-batch" if selected_methods else "connected-suite", command, CONNECTED_SUITE_TIMEOUT_SECONDS)
    if suite_status == 124:
        # Losing the Gradle observer does not prove device instrumentation ended.
        # Preserve evidence without racing another install/test against it.
        check("evidence-export", adb + ["pull", "/sdcard/Download/nfb-client-acceptance", str(evidence)])
        return first_failure

    contract_status, contract_output = suite_contract(component, selected_methods)
    (evidence / ("affected-method-contract.txt" if selected_methods else "suite-contract.txt")).write_text(contract_output, encoding="utf-8")
    print(f"suite-contract: exit {contract_status}", flush=True)
    if contract_status and not first_failure:
        first_failure = contract_status

    if selected_methods:
        # This batch proves only its exact selected methods. Do not repeat
        # unchanged host-managed restart tests or reinstall another package.
        check("evidence-export", adb + ["pull", "/sdcard/Download/nfb-client-acceptance", str(evidence)])
        return first_failure

    # No fresh install or data clearing: the restart gate must retain the saved
    # theme in app-private storage across an actual process stop.
    installed = True
    for name, apk in [("target-install", "app/build/outputs/apk/debug/app-debug.apk"),
                      ("tests-install", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")]:
        if check(name, adb + ["install", "-r", apk]):
            installed = False
            break
    if installed:
        check("theme-restart-prepare", adb + ["shell", "am", "instrument", "-w", "-r",
              "-e", "class", RESTART_CLASSES[0], RUNNER], instrumentation=True)
        stopped = check("theme-force-stop", adb + ["shell", "am", "force-stop", PACKAGE])
        # The verifier also restores the original preference after a failed gate.
        # Run it after preparation failure so partial setup can recover its state.
        if not stopped:
            check("theme-restart-verify", adb + ["shell", "am", "instrument", "-w", "-r",
                  "-e", "class", RESTART_CLASSES[1], RUNNER], instrumentation=True)

    check("evidence-export", adb + ["pull", "/sdcard/Download/nfb-client-acceptance", str(evidence)])
    return first_failure


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--test-methods", default="", help="Canonical comma-separated package.Class#method selection; empty keeps the full suite")
    args = parser.parse_args()
    raise SystemExit(run_checks(Path(__file__).resolve().parents[1], args.serial, test_methods=args.test_methods))
