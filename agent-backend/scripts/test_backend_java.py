"""运行 Java 检测回归；真实查询由 BUTVAN_TEST_JAVA 指定 Java 21。"""
import os
from pathlib import Path
import shutil
import subprocess
import unittest

PWSH = os.environ.get("BUTVAN_TEST_PWSH") or shutil.which("pwsh")
SCRIPT = Path(__file__).resolve().parents[1] / "deployment/windows/source/test-java.ps1"


class JavaProbeTest(unittest.TestCase):
    @unittest.skipUnless(PWSH, "需要 PowerShell 验证 Java 检测")
    def test_java_detection_and_legacy_exit_code_regression(self):
        result = subprocess.run([PWSH, "-NoLogo", "-NoProfile", "-File", str(SCRIPT)],
                                env=os.environ.copy(), text=True, capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Java 检测测试通过", result.stdout)


if __name__ == "__main__":
    unittest.main()
