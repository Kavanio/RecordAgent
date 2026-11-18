"""验证四个明文 BAT 独立准备文件、普通脚本执行与来源标记保留。"""
import importlib.util
import os
from pathlib import Path
import shutil
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
                self.assertLess(len(builder.PREPARE_COMMAND), 8000)
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

    @unittest.skipUnless(PWSH and os.name != "nt", "在非 Windows 上模拟 ADS，实际 Windows 需按执行策略验收")
    def test_internet_origin_is_propagated_to_prepare_and_runtime_scripts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bat = root / "start-backend.bat"
            shutil.copy2(builder.WINDOWS / bat.name, bat)
            zone = b"[ZoneTransfer]\r\nZoneId=3\r\n"
            Path(str(bat) + ":Zone.Identifier").write_bytes(zone)
            result = self.extract(bat, simulate_windows_origin=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual((root / "data/prepare-test.ps1:Zone.Identifier").read_bytes(), zone)
            for filename in builder.SOURCE_FILES:
                helper = next((root / "data/launcher").glob("*/" + filename))
                self.assertEqual(Path(str(helper) + ":Zone.Identifier").read_bytes(), zone)

    @staticmethod
    def extract(bat, simulate_windows_origin=False):
        environment = os.environ.copy()
        prepare = bat.parent / "data/prepare-test.ps1"
        environment.update(BUTVAN_LAUNCHER_FILE=str(bat), BUTVAN_LAUNCHER_ACTION="Extract",
                           BUTVAN_LAUNCHER_PREPARE=str(prepare))
        if simulate_windows_origin:
            environment["OS"] = "Windows_NT"
        # 执行 BAT 中相同的明文提取命令，再用普通 -File 执行；不使用编码或策略覆盖。
        command = builder.PREPARE_COMMAND.split('-Command "', 1)[1][:-1]
        try:
            result = subprocess.run([PWSH, "-NoLogo", "-NoProfile", "-Command", command],
                                    env=environment, text=True, capture_output=True, timeout=20)
            if result.returncode:
                return result
            return subprocess.run([PWSH, "-NoLogo", "-NoProfile", "-File", str(prepare)],
                                  env=environment, text=True, capture_output=True, timeout=20)
        finally:
            prepare.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
