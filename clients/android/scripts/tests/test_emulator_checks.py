"""Host orchestration contracts; these tests do not prove Android behavior."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import os
import re
import subprocess
from unittest import mock

spec = importlib.util.spec_from_file_location("emulator_checks", Path(__file__).parents[1] / "run-emulator-checks.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class EmulatorChecksTest(unittest.TestCase):
    def exercise(self, suite=0, prepare="OK (1 test)\n", verify="OK (1 test)\n", export=0, test_methods="",
                 junit='<testsuites><testsuite><testcase classname="OwnedRuntimeTest" name="realCase"/></testsuite></testsuites>',
                 diagnostic=0, diagnostic_exception=None):
        calls = []
        timeouts = []

        def run(command, cwd, timeout):
            calls.append(command)
            timeouts.append(timeout)
            if "getprop" in command:
                return 0, "1\n"
            if command == ["adb", "devices"]:
                return 0, "List of devices attached\nemulator-5556\tdevice\n"
            if ":app:connectedDebugAndroidTest" in command:
                if junit is not None:
                    report = Path(cwd) / "app/build/outputs/androidTest-results/connected/debug/TEST-fixture.xml"
                    report.parent.mkdir(parents=True, exist_ok=True)
                    report.write_text(junit, encoding="utf-8")
                return suite, "suite result\n"
            if "instrument" in command:
                return 0, prepare if runner.RESTART_CLASSES[0] in command else verify
            if "pull" in command:
                return export, "export result\n"
            if command[3:5] in [["shell", "stat"], ["shell", "ls"]]:
                if diagnostic_exception is not None:
                    raise diagnostic_exception
                return diagnostic, "owned directory metadata\n"
            return 0, "Success\n"

        with tempfile.TemporaryDirectory(dir=os.environ.get("NFB_CHECK_TMP")) as root:
            result = runner.run_checks(Path(root), "emulator-5556", run, test_methods=test_methods)
            self.evidence = {p.name: p.read_text(encoding="utf-8")
                             for p in (Path(root) / "app/build/acceptance").glob("*.txt")}
        self.command_timeouts = timeouts
        return result, calls

    def test_restart_gate_runs_in_two_invocations_with_stop_between(self):
        result, calls = self.exercise()
        self.assertEqual(0, result)
        prepare = next(i for i, c in enumerate(calls) if runner.RESTART_CLASSES[0] in c)
        stopped = next(i for i, c in enumerate(calls) if "force-stop" in c)
        verify = next(i for i, c in enumerate(calls) if runner.RESTART_CLASSES[1] in c)
        self.assertLess(prepare, stopped)
        self.assertLess(stopped, verify)
        suite = next(c for c in calls if ":app:connectedDebugAndroidTest" in c)
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.notPackage=" + runner.RESTART_PACKAGE, suite)
        self.assertFalse(any("notClass=" in arg for arg in suite))
        self.assertFalse(any("tests_regex=" in arg or "nfbTraceUi=" in arg for arg in suite))
        for command in calls:
            if command[0] == "adb" and command != ["adb", "devices"]:
                self.assertEqual(["-s", "emulator-5556"], command[1:3])
            self.assertNotIn("uninstall", command)
            self.assertNotIn("clear", command)

    def test_success_exit_cannot_hide_missing_or_nonpassing_junit(self):
        for junit in [None, '<testsuites/>', '<broken',
                      '<testsuite><testcase classname="RuntimeTest"><failure>broken</failure></testcase></testsuite>',
                      '<testsuite><testcase classname="RuntimeTest"><skipped/></testcase></testsuite>']:
            with self.subTest(junit=junit):
                status, calls = self.exercise(junit=junit)
                self.assertEqual(1, status)
                self.assertTrue(any(runner.RESTART_CLASSES[1] in c for c in calls))

    def test_restart_leak_fails_even_if_gradle_exits_zero(self):
        for name in [runner.RESTART_CLASSES[1], f"{runner.PACKAGE}.ThemeRestartVerifyTest"]:
            with self.subTest(name=name):
                status, calls = self.exercise(junit=f'<testsuite><testcase classname="{name}" name="restart"/></testsuite>')
                self.assertEqual(1, status)
                self.assertTrue(any(runner.RESTART_CLASSES[0] in c for c in calls))

    def test_isolation_guard_preserves_the_original_suite_failure(self):
        status, _ = self.exercise(suite=7, junit=None)
        self.assertEqual(7, status)

    def test_suite_failure_is_preserved_while_restart_and_export_still_run(self):
        result, calls = self.exercise(suite=7, verify="FAILURES!!!\n", export=9)
        self.assertEqual(7, result)
        self.assertTrue(any(runner.RESTART_CLASSES[1] in c for c in calls))
        self.assertEqual(1, sum("pull" in command for command in calls))

    def test_adb_zero_exit_is_not_a_passing_instrumentation_result(self):
        for result in ["FAILURES!!!\n", "INSTRUMENTATION_FAILED: process crashed\n", "OK (0 tests)\n", ""]:
            with self.subTest(result=result):
                status, calls = self.exercise(prepare=result)
                self.assertEqual(1, status)
                self.assertTrue(any(runner.RESTART_CLASSES[1] in c for c in calls))

    def test_evidence_export_failure_fails_an_otherwise_passing_run(self):
        result, _ = self.exercise(export=9)
        self.assertEqual(9, result)

    def test_failed_export_collects_only_fixed_directory_metadata_once_after_pull(self):
        cls = runner.PACKAGE + ".OwnedRuntimeTest"
        junit = f'<testsuite><testcase classname="{cls}" name="actualCase"/></testsuite>'
        for selection in ["", cls + "#actualCase"]:
            for suite in [0, 7, 124]:
                with self.subTest(selection=selection, suite=suite):
                    result, calls = self.exercise(suite=suite, export=9, test_methods=selection, junit=junit)
                    self.assertEqual(suite or 9, result)
                    pull = [i for i, command in enumerate(calls) if "pull" in command]
                    self.assertEqual(1, len(pull))
                    self.assertEqual([
                        ["adb", "-s", "emulator-5556", "shell", "stat", "--", runner.REMOTE_EVIDENCE],
                        ["adb", "-s", "emulator-5556", "shell", "ls", "-lan", "--", runner.REMOTE_EVIDENCE],
                    ], calls[pull[0] + 1:])
                    for name in ["stat", "list"]:
                        self.assertEqual("exit 0\nowned directory metadata\n", self.evidence[f"evidence-export-{name}.txt"])
                    self.assertEqual("export result\n", self.evidence["evidence-export.txt"])

    def test_export_diagnostic_failure_or_exception_cannot_replace_original_failure(self):
        for suite in [0, 7, 124]:
            for failure in [None, OSError("must not expose raw exception details")]:
                with self.subTest(suite=suite, failure=type(failure).__name__):
                    result, calls = self.exercise(suite=suite, export=9, diagnostic=23, diagnostic_exception=failure)
                    self.assertEqual(suite or 9, result)
                    self.assertEqual(1, sum("pull" in command for command in calls))
                    for name in ["stat", "list"]:
                        output = self.evidence[f"evidence-export-{name}.txt"]
                        self.assertIn("exit 23\n" if failure is None else "Diagnostic unavailable: OSError\n", output)
                        self.assertNotIn("must not expose", output)

    def test_successful_export_does_not_run_metadata_diagnostics(self):
        status, calls = self.exercise()
        self.assertEqual(0, status)
        self.assertFalse(any(command[3:5] in [["shell", "stat"], ["shell", "ls"]] for command in calls))
        self.assertNotIn("evidence-export-stat.txt", self.evidence)
        self.assertNotIn("evidence-export-list.txt", self.evidence)

    def test_export_diagnostics_share_the_original_deadline_without_extending_it(self):
        with mock.patch.object(runner.time, "monotonic", side_effect=[100, 120, 130]):
            status, _ = self.exercise(export=9)
        self.assertEqual(9, status)
        self.assertEqual([120, 100, 90], self.command_timeouts[-3:])
        with mock.patch.object(runner.time, "monotonic", side_effect=[100, 220, 221]):
            status, calls = self.exercise(export=9)
        self.assertEqual(9, status)
        self.assertEqual("pull", calls[-1][3])
        for name in ["stat", "list"]:
            self.assertIn("exit 124\nDiagnostic not attempted", self.evidence[f"evidence-export-{name}.txt"])

    def test_export_diagnostic_receipt_failure_cannot_replace_original_failure(self):
        original = Path.write_text

        def write(path, *args, **kwargs):
            if path.name in ["evidence-export-stat.txt", "evidence-export-list.txt"]:
                raise OSError("owned diagnostic storage unavailable")
            return original(path, *args, **kwargs)

        with mock.patch.object(Path, "write_text", write):
            status, calls = self.exercise(export=9)
        self.assertEqual(9, status)
        self.assertEqual(1, sum("pull" in command for command in calls))
        self.assertEqual("export result\n", self.evidence["evidence-export.txt"])

    def test_observer_timeout_does_not_start_a_second_device_job(self):
        result, calls = self.exercise(suite=124)
        self.assertEqual(124, result)
        self.assertFalse(any("install" in c or "instrument" in c or "force-stop" in c for c in calls))
        self.assertEqual("pull", calls[-1][3])

    def test_phone_and_unverified_emulator_are_rejected_before_mutations(self):
        calls = []

        def physical(command, cwd, timeout):
            calls.append(command)
            return 0, "\n"

        for serial in ["personal-phone", "emulator-5556"]:
            with self.subTest(serial=serial):
                with self.assertRaises(ValueError):
                    runner.run_checks(Path("."), serial, physical)
        self.assertEqual(1, len(calls))
        self.assertIn("getprop", calls[0])

    def test_an_additional_device_blocks_gradle_and_install(self):
        calls = []

        def run(command, cwd, timeout):
            calls.append(command)
            if "getprop" in command:
                return 0, "1\n"
            return 0, "List of devices attached\nemulator-5556\tdevice\npersonal-phone\tdevice\n"

        with self.assertRaises(ValueError):
            runner.run_checks(Path("."), "emulator-5556", run)
        self.assertEqual(2, len(calls))

    def test_method_selection_rejects_noncanonical_duplicate_and_restart_entries_before_adb(self):
        method = runner.PACKAGE + ".OwnedRuntimeTest#actualCase"
        invalid = [" ", ",", method + ",", "," + method, method + ",," + method,
                   method + "," + method, method + " ", method.replace("#", "."),
                   "OwnedRuntimeTest#actualCase", "other.package.OwnedTest#actualCase",
                   runner.PACKAGE + ".OwnedRuntimeTest", method + ";echo injected",
                   runner.PACKAGE + ".hostrestart.ThemeRestartPrepareTest#prepare",
                   runner.PACKAGE + ".ThemeRestartVerifyTest#verify"]
        for selection in invalid:
            calls = []
            with self.subTest(selection=selection), self.assertRaises(ValueError):
                runner.run_checks(Path("."), "emulator-5556", lambda *args: calls.append(args), test_methods=selection)
            self.assertEqual([], calls)
        self.assertEqual((), runner.parse_test_methods(""))
        self.assertEqual((method,), runner.parse_test_methods(method))

    def test_affected_batch_runs_exact_methods_once_without_restart_or_install(self):
        first = runner.PACKAGE + ".OwnedRuntimeTest#firstCase"
        second = runner.PACKAGE + ".nested.OwnedOtherTest#secondCase"
        junit = f'<testsuites><testsuite><testcase classname="{runner.PACKAGE}.OwnedRuntimeTest" name="firstCase"/>' + \
                f'<testcase classname="{runner.PACKAGE}.nested.OwnedOtherTest" name="secondCase"/></testsuite></testsuites>'
        status, calls = self.exercise(test_methods=first + "," + second, junit=junit)
        self.assertEqual(0, status)
        commands = [c for c in calls if ":app:connectedDebugAndroidTest" in c]
        self.assertEqual(1, len(commands))
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.tests_regex='" + runner.test_methods_regex((first, second)) + "'", commands[0])
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.nfbTraceUi=true", commands[0])
        self.assertFalse(any(arg.startswith("-Pandroid.testInstrumentationRunnerArguments.class=") for arg in commands[0]))
        self.assertFalse(any("install" in c or "instrument" in c or "force-stop" in c for c in calls))
        self.assertEqual("pull", calls[-1][3])

    def test_affected_batch_cannot_pass_missing_extra_duplicate_failed_or_skipped_methods(self):
        cls = runner.PACKAGE + ".OwnedRuntimeTest"
        first = f'<testcase classname="{cls}" name="firstCase"/>'
        second = f'<testcase classname="{cls}" name="secondCase"/>'
        selection = cls + "#firstCase," + cls + "#secondCase"
        invalid = [None, '<testsuites/>', '<broken', '<testsuite>' + first + '</testsuite>',
                   '<testsuite>' + first + second + first + '</testsuite>',
                   '<testsuite>' + first + second + f'<testcase classname="{cls}" name="extraCase"/></testsuite>',
                   '<testsuite>' + first + f'<testcase classname="{cls}" name="secondCase"><failure>broken</failure></testcase></testsuite>',
                   '<testsuite>' + first + f'<testcase classname="{cls}" name="secondCase"><error>broken</error></testcase></testsuite>',
                   '<testsuite>' + first + f'<testcase classname="{cls}" name="secondCase"><skipped/></testcase></testsuite>']
        for junit in invalid:
            with self.subTest(junit=junit):
                status, calls = self.exercise(test_methods=selection, junit=junit)
                self.assertEqual(1, status)
                self.assertFalse(any("install" in c or "instrument" in c or "force-stop" in c for c in calls))
                self.assertEqual("pull", calls[-1][3])

    def test_affected_batch_preserves_gradle_timeout_failure_and_export_failure(self):
        cls = runner.PACKAGE + ".OwnedRuntimeTest"
        selection = cls + "#actualCase"
        junit = f'<testsuite><testcase classname="{cls}" name="actualCase"/></testsuite>'
        for suite, export, expected in [(7, 9, 7), (124, 0, 124), (0, 9, 9)]:
            with self.subTest(suite=suite, export=export):
                status, calls = self.exercise(test_methods=selection, junit=junit, suite=suite, export=export)
                self.assertEqual(expected, status)
                self.assertFalse(any("install" in c or "instrument" in c or "force-stop" in c for c in calls))

    def test_affected_contract_explicitly_cannot_be_reported_as_full_suite(self):
        cls = runner.PACKAGE + ".OwnedRuntimeTest"
        with tempfile.TemporaryDirectory(dir=os.environ.get("NFB_CHECK_TMP")) as folder:
            component = Path(folder)
            report = component / "app/build/outputs/androidTest-results/connected/TEST-fixture.xml"
            report.parent.mkdir(parents=True)
            report.write_text(f'<testsuite><testcase classname="{cls}" name="actualCase"/></testsuite>')
            status, output = runner.suite_contract(component, (cls + "#actualCase",))
            self.assertEqual(0, status)
            self.assertIn("NOT a full-suite result", output)
            self.assertIn("restart gates were not rerun", output)

    def test_selected_regex_survives_the_pinned_agp_comma_key_value_codec(self):
        methods = runner.parse_test_methods(runner.PACKAGE + ".OwnedTest#first," + runner.PACKAGE + ".OtherTest#second")
        pattern = runner.test_methods_regex(methods)
        self.assertNotIn(",", pattern)
        junit = f'<testsuite><testcase classname="{runner.PACKAGE}.OwnedTest" name="first"/>' + \
                f'<testcase classname="{runner.PACKAGE}.OtherTest" name="second"/></testsuite>'
        status, calls = self.exercise(test_methods=",".join(methods), junit=junit)
        self.assertEqual(0, status)
        command = next(c for c in calls if ":app:connectedDebugAndroidTest" in c)
        prefix = "-Pandroid.testInstrumentationRunnerArguments."
        arguments = dict(arg.removeprefix(prefix).split("=", 1) for arg in command if arg.startswith(prefix))
        self.assertNotIn("class", arguments)
        self.assertEqual("'" + pattern + "'", arguments["tests_regex"])
        self.assertEqual("true", arguments["nfbTraceUi"])
        # AGP9.4.1 UtpTestUtils:98 / AndroidTestEngineConfigurer:96 encode
        # entries this way. Engine1.0.1 AndroidTestConfiguration:105-112
        # splits by comma and drops entries without '='. Exercise that boundary.
        def roundtrip(values):
            encoded = ",".join(f"{key}={value}" for key, value in values.items())
            pairs = [item.split("=", 1) for item in encoded.split(",")]
            return {pair[0].strip(): pair[1].strip() for pair in pairs if len(pair) == 2}
        self.assertEqual(arguments, roundtrip(arguments))
        # Engine1.0.1 AmInstrumentCommandBuilder forwards raw argv, and AOSP
        # adb shell joins them into a command. Execute only printf on the host
        # to prove that this second shell parse preserves the actual pattern.
        result = subprocess.run(["sh", "-c", "printf '%s' " + roundtrip(arguments)["tests_regex"]],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(pattern, result.stdout)
        # This control reproduces the actual previous loss, rather than merely
        # asserting that a new flag was added to an argv list.
        self.assertEqual(methods[0], roundtrip({"class": ",".join(methods)})["class"])

    def test_selected_regex_matches_only_complete_selected_class_and_method_identities(self):
        methods = runner.parse_test_methods(runner.PACKAGE + ".OwnedTest#first," + runner.PACKAGE + ".nested.OtherTest#second")
        pattern = runner.test_methods_regex(methods)
        self.assertTrue(pattern.startswith(r"\A(?:") and pattern.endswith(r")\z"))
        # Pinned Java Pattern \z and Python3.11 \Z both mean absolute end;
        # translate only that anchor for this host contract. The actual runner
        # uses matcher.find(), so search(), not fullmatch(), is required here.
        host_pattern = re.compile(pattern[:-2] + r"\Z")
        for method in methods:
            self.assertIsNotNone(host_pattern.search(method))
            for other in ("prefix" + method, method + "Suffix", method + "\n", method + "\r\n",
                          method.replace(".", "X", 1), method.replace("#", "Other#"), method.upper()):
                with self.subTest(other=other):
                    self.assertIsNone(host_pattern.search(other))
        self.assertIsNone(host_pattern.search(runner.PACKAGE + ".UnselectedTest#first"))
        self.assertIsNone(host_pattern.search(runner.PACKAGE + ".OwnedTest#second"))


if __name__ == "__main__":
    unittest.main()
