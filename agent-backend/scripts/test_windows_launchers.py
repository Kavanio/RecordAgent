"""验证四个明文 BAT 独立准备文件、普通脚本执行与来源标记保留。"""
import importlib.util
import os
from pathlib import Path
import shutil
import sys
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("builder", Path(__file__).with_name("build-windows-launchers.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
PWSH = os.environ.get("BUTVAN_TEST_PWSH") or shutil.which("pwsh")


class LauncherTest(unittest.TestCase):
    def test_committed_bats_have_readable_source_and_use_normal_file_execution(self):
        for name, content in builder.generate(builder.WINDOWS / "source").items():
            with self.subTest(name=name):
                self.assertEqual((builder.WINDOWS / name).read_bytes(), content)
                self.assertNotIn(b"\n", content.replace(b"\r\n", b""))
                self.assertLess(len(builder.prepare_command(builder.WINDOWS / "source")), 8000)
                text = content.decode("utf-8")
                for forbidden in ("-EncodedCommand", "-ExecutionPolicy", "FromBase64String",
                                  "ZipArchive", "Invoke-Expression", "ScriptBlock]::Create", "Unblock-File"):
                    self.assertNotIn(forbidden.lower(), text.lower())
                self.assertIn('powershell.exe -NoLogo -NoProfile -File "%BUTVAN_LAUNCHER_PREPARE%"', text)
                for filename in builder.SOURCE_FILES:
                    self.assertEqual(text.count(f"\r\n::FILE {filename}\r\n"), 1)
                self.assertIn("::|function Start-Backend", text)
                stop = text.split("::|function Stop-Backend", 1)[1].split("::|function Start-Backend", 1)[0]
                self.assertNotIn("WindowStyle Hidden", stop)
                self.assertNotIn("::FILE AGENTS.md", text)

    def test_origin_handling_avoids_framework_file_paths_and_reports_failure_details(self):
        for name, content in builder.generate(builder.WINDOWS / "source").items():
            with self.subTest(name=name):
                text = content.decode("utf-8")
                self.assertNotIn("[IO.File]::ReadAllBytes($self+':Zone.Identifier')", text)
                self.assertNotIn("[IO.File]::ReadAllBytes($self + ':Zone.Identifier')", text)
                self.assertIn("-Stream", text)
                self.assertIn("[Console]::Error.WriteLine", text)

    @unittest.skipUnless(PWSH, "需要 PowerShell 验证明文引导")
    def test_each_bat_prepares_exact_source_without_any_sibling_files(self):
        for name in builder.ACTIONS:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix="butvan BAT '空格 ") as directory:
                root = Path(directory)
                bat = root / name
                shutil.copy2(builder.WINDOWS / name, bat)
                result = self.extract(bat)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual({p.name for p in root.iterdir()}, {name, "data"})
                runtimes = list((root / "data/launcher").iterdir())
                self.assertEqual(len(runtimes), 1)
                self.assertEqual({p.name for p in runtimes[0].iterdir()}, set(builder.SOURCE_FILES) | {"complete"})
                for filename in builder.SOURCE_FILES:
                    original = (builder.WINDOWS / "source" / filename).read_text(encoding="utf-8-sig")
                    expected = "\r\n".join(original.splitlines()) + "\r\n"
                    prefix = b"\xef\xbb\xbf" if filename.endswith(".ps1") else b""
                    self.assertEqual((runtimes[0] / filename).read_bytes(), prefix + expected.encode())

    @unittest.skipUnless(PWSH, "需要 PowerShell 验证明文引导")
    def test_corrupted_bat_does_not_prepare_or_execute_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            bat = Path(directory) / "start-backend.bat"
            content = (builder.WINDOWS / bat.name).read_bytes()
            bat.write_bytes(content.replace(b"::|param(", b"::|corrupted-param(", 1))
            result = self.extract(bat)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("[validate-runtime]", result.stderr)
            self.assertIn("BAT 文件不完整", result.stderr)
            self.assertFalse((Path(directory) / "data/launcher").exists())

    @unittest.skipUnless(PWSH, "需要 PowerShell 验证缓存与配置")
    def test_existing_configuration_is_imported_once_and_modified_cache_is_repaired(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bat = root / "start-backend.bat"
            shutil.copy2(builder.WINDOWS / bat.name, bat)
            old = root / "backend.env"
            old.write_text("test configuration")
            self.assertEqual(self.extract(bat).returncode, 0)
            imported = root / "data/backend.env"
            self.assertEqual(imported.read_text(), "test configuration")
            helper = next((root / "data/launcher").glob("*/backend.ps1"))
            stable_helper = helper.with_name("backend-core.ps1")
            modified_time = stable_helper.stat().st_mtime_ns
            helper.write_text("modified cache")
            old.write_text("changed old configuration")
            self.assertEqual(self.extract(bat).returncode, 0)
            self.assertEqual(imported.read_text(), "test configuration")
            self.assertTrue(helper.read_text(encoding="utf-8-sig").startswith("param("))
            self.assertEqual(stable_helper.stat().st_mtime_ns, modified_time)

    @unittest.skipUnless(PWSH and os.name != "nt", "在非 Windows 上模拟 Provider，实际 Windows 需按执行策略验收")
    def test_internet_origin_is_preserved_for_powershell_5_and_7_provider_calls(self):
        for legacy in (True, False):
            with self.subTest(legacy=legacy), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                bat = root / "start-backend.bat"
                shutil.copy2(builder.WINDOWS / bat.name, bat)
                zone = b"[ZoneTransfer]\r\nZoneId=3\r\nReferrerUrl=https://example.com/\r\n"
                Path(str(bat) + ":Zone.Identifier").write_bytes(zone)
                result = self.extract(bat, simulate_windows_origin=True, legacy_origin=legacy)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual((root / "data/prepare-test.ps1:Zone.Identifier").read_bytes(), zone)
                for filename in builder.SOURCE_FILES:
                    helper = next((root / "data/launcher").glob("*/" + filename))
                    self.assertEqual(Path(str(helper) + ":Zone.Identifier").read_bytes(), zone)

    @unittest.skipUnless(PWSH and os.name != "nt", "需要 PowerShell 与模拟 Provider")
    def test_absent_origin_is_allowed_but_access_denied_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            bat = Path(directory) / "start-backend.bat"
            shutil.copy2(builder.WINDOWS / bat.name, bat)
            result = self.extract(bat, simulate_windows_origin=True, legacy_origin=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertFalse((bat.parent / "data/prepare-test.ps1:Zone.Identifier").exists())
            result = self.extract(bat, simulate_windows_origin=True, deny_origin=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("[copy-origin]", result.stderr)
            self.assertIn("UnauthorizedAccessException", result.stderr)
            self.assertIn("test origin read denied", result.stderr)

    @unittest.skipUnless(PWSH, "需要 PowerShell 验证真实异常输出")
    def test_prepare_failure_reports_stage_and_original_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            bat = Path(directory) / "start-backend.bat"
            shutil.copy2(builder.WINDOWS / bat.name, bat)
            prepare = bat.parent / "data/prepare-test.ps1"
            prepare.parent.mkdir()
            prepare.write_text("existing file")
            result = self.extract(bat)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("[write-bootstrap]", result.stderr)
            self.assertIn("IOException", result.stderr)
            self.assertNotIn("WriteErrorException", result.stderr)

    @unittest.skipUnless(sys.platform == "win32", "需要真实 Windows cmd 与 powershell.exe 5.1")
    def test_windows_cmd_executes_each_bat_prepare_path(self):
        powershell = shutil.which("powershell.exe")
        if not powershell:
            self.skipTest("Windows PowerShell 不可用")
        policy = subprocess.run([powershell, "-NoProfile", "-Command", "Get-ExecutionPolicy"],
                                text=True, capture_output=True, timeout=20)
        if policy.stdout.strip() in ("Restricted", "AllSigned"):
            self.skipTest("系统策略不允许本地未签名脚本；测试遵循策略，不自动更改")
        for name, action in builder.ACTIONS.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix="butvan BAT 空格 ") as directory:
                bat = Path(directory) / name
                # 只替换操作为准备文件，完整经过 CMD、引导与普通 -File，不启动业务后端。
                content = (builder.WINDOWS / name).read_bytes().replace(
                    f'set "BUTVAN_LAUNCHER_ACTION={action}"'.encode(), b'set "BUTVAN_LAUNCHER_ACTION=Extract"')
                bat.write_bytes(content)
                result = subprocess.run(["cmd.exe", "/d", "/c", str(bat)], input="\n", capture_output=True,
                                        timeout=30, encoding="utf-8", errors="replace")
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(len(list((bat.parent / "data/launcher").glob("*/complete"))), 1)

    @staticmethod
    def extract(bat, simulate_windows_origin=False, legacy_origin=False, deny_origin=False):
        environment = os.environ.copy()
        prepare = bat.parent / "data/prepare-test.ps1"
        environment.update(BUTVAN_LAUNCHER_FILE=str(bat), BUTVAN_LAUNCHER_ACTION="Extract",
                           BUTVAN_LAUNCHER_PREPARE=str(prepare))
        prefix = ""
        if simulate_windows_origin:
            environment["OS"] = "Windows_NT"
            environment["BUTVAN_TEST_DENY_ORIGIN"] = "1" if deny_origin else "0"
            # 仅模拟 Windows Provider 的流读写接口，保留真实引导代码与字节比较。
            # macOS 不提供 NTFS ADS；该测试不能替代 Windows 5.1 实机验收。
            prefix = r'''function Get-Item {
                param($LiteralPath, $Stream, $ErrorAction)
                if ($env:BUTVAN_TEST_DENY_ORIGIN -eq '1') {
                    throw [UnauthorizedAccessException]::new('test origin read denied')
                }
                if ($Stream -ne '*') { throw 'expected stream enumeration' }
                if ([IO.File]::Exists($LiteralPath + ':Zone.Identifier')) {
                    [pscustomobject]@{ Stream = 'Zone.Identifier' }
                } else { [pscustomobject]@{ Stream = ':$DATA' } }
            }
            function Get-Content {
                param($LiteralPath, $Stream, $ReadCount, $Encoding, [switch]$AsByteStream, $ErrorAction)
                if ($Stream -ne 'Zone.Identifier' -or $ReadCount -ne 0) { throw 'invalid stream read' }
                if ($PSVersionTable.PSVersion.Major -lt 6 -and $Encoding -ne 'Byte') { throw '5.1 needs Byte' }
                if ($PSVersionTable.PSVersion.Major -ge 6 -and -not $AsByteStream) { throw '7 needs AsByteStream' }
                return ,([IO.File]::ReadAllBytes($LiteralPath + ':Zone.Identifier'))
            }
            function Set-Content {
                param($LiteralPath, $Stream, $Value, $Encoding, [switch]$AsByteStream, $ErrorAction)
                if ($Stream -ne 'Zone.Identifier') { throw 'invalid stream write' }
                if ($PSVersionTable.PSVersion.Major -lt 6 -and $Encoding -ne 'Byte') { throw '5.1 needs Byte' }
                if ($PSVersionTable.PSVersion.Major -ge 6 -and -not $AsByteStream) { throw '7 needs AsByteStream' }
                [IO.File]::WriteAllBytes($LiteralPath + ':Zone.Identifier', [byte[]]$Value)
            }; '''
            if legacy_origin:
                prefix += "$PSVersionTable.PSVersion=[version]'5.1'; "
        command = builder.prepare_command(builder.WINDOWS / "source").split('-Command "', 1)[1][:-1]
        try:
            result = subprocess.run([PWSH, "-NoLogo", "-NoProfile", "-Command", prefix + command],
                                    env=environment, text=True, capture_output=True, timeout=20)
            if result.returncode:
                return result
            arguments = (["-Command", prefix + "& $env:BUTVAN_LAUNCHER_PREPARE"] if simulate_windows_origin
                         else ["-File", str(prepare)])
            return subprocess.run([PWSH, "-NoLogo", "-NoProfile", *arguments],
                                  env=environment, text=True, capture_output=True, timeout=20)
        finally:
            prepare.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
