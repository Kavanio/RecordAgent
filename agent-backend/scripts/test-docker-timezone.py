#!/usr/bin/env python3
"""隔离验证容器入口的时区默认值与覆盖，不启动业务后端或读写用户数据。"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]


class DockerTimezoneTest(unittest.TestCase):
    """保留真实入口参数与 JVM，仅替换容器专属的 CA 入口和应用 JAR 路径。"""

    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix="butvan-docker-timezone-")
        cls.addClassCleanup(cls.workspace.cleanup)
        cls.root = Path(cls.workspace.name)
        source = cls.root / "TimezoneProbe.java"
        source.write_text('''
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimezoneProbe {
    public static void main(String[] args) {
        ZoneId zone = ZoneId.systemDefault();
        System.out.println(System.getenv("TZ"));
        System.out.println(zone);
        System.out.println(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.parse("2026-10-28T17:00:00Z").atZone(zone)));
        System.out.println(String.join("|", args));
    }
}
''', encoding="utf-8")
        subprocess.run(["javac", "--release", "21", str(source)], check=True)
        jar = cls.root / "probe.jar"
        subprocess.run([
            "jar", "--create", "--file", str(jar), "--main-class", "TimezoneProbe",
            "-C", str(cls.root), "TimezoneProbe.class",
        ], check=True)
        ca = cls.root / "ca-entrypoint.sh"
        ca.write_text('#!/bin/sh\nexec "$@"\n', encoding="utf-8")
        ca.chmod(0o755)
        entrypoint = (BACKEND / "docker-entrypoint.sh").read_text(encoding="utf-8")
        # 只替换容器文件路径，时区逻辑、参数顺序、exec 与透传行为均运行仓库原实现。
        if entrypoint.count("/__cacert_entrypoint.sh") != 1 or entrypoint.count("/app/app.jar") != 1:
            raise AssertionError("容器入口路径已变化，需要同步测试夹具")
        cls.entrypoint = cls.root / "entrypoint.sh"
        cls.entrypoint.write_text(
            entrypoint.replace("/__cacert_entrypoint.sh", '"' + str(ca) + '"')
            .replace("/app/app.jar", '"' + str(jar) + '"'), encoding="utf-8",
        )

    def test_image_default(self):
        """Docker exec 启动的命令也应获得镜像层的北京时间默认值。"""
        dockerfile = (BACKEND / "Dockerfile").read_text(encoding="utf-8")
        self.assertIn("ENV TZ=Asia/Shanghai", dockerfile.splitlines())

    def test_shell_syntax(self):
        subprocess.run(["sh", "-n", str(BACKEND / "docker-entrypoint.sh")], check=True)

    def test_entrypoint_timezone_and_arguments(self):
        """验证未设置、空值、显式默认值及两种覆盖，并检查同一时刻的跨日结果。"""
        cases = [
            (None, "Asia/Shanghai", "2026-10-29T01:00:00+08:00"),
            ("", "Asia/Shanghai", "2026-10-29T01:00:00+08:00"),
            ("Asia/Shanghai", "Asia/Shanghai", "2026-10-29T01:00:00+08:00"),
            ("UTC", "UTC", "2026-10-28T17:00:00Z"),
            ("America/New_York", "America/New_York", "2026-10-28T13:00:00-04:00"),
        ]
        for configured, expected_zone, expected_time in cases:
            with self.subTest(TZ=configured):
                env = os.environ.copy()
                # 隔离开发机 JVM 配置，防止外部选项污染这组入口契约测试。
                for name in ("TZ", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
                    env.pop(name, None)
                env["HOME"] = str(self.root / "isolated-home")
                if configured is not None:
                    env["TZ"] = configured
                result = subprocess.run([
                    "sh", str(self.entrypoint), "--server.port=9090", "argument with spaces",
                ], env=env, check=True, text=True, capture_output=True)
                self.assertEqual(result.stdout.splitlines(), [
                    expected_zone, expected_zone, expected_time,
                    "--server.port=9090|argument with spaces",
                ])
                self.assertTrue((Path(env["HOME"]) / ".butvan-agent/tmp").is_dir())


if __name__ == "__main__":
    unittest.main(verbosity=2)
