#!/usr/bin/env python3
"""发布独立 JAR 与 Windows 启动器；固定产物完成后才更新渠道清单。"""

import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from datetime import datetime, timezone
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import zipfile


def file_info(path, key):
    """流式计算校验值，避免大 JAR 重复驻留内存。"""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"key": key, "sha256": digest.hexdigest(), "size": path.stat().st_size}


def package_launcher(directory, output, readme):
    """只打包发布模板白名单，绝不包含真实配置、凭据或运行态数据。"""
    names = ["AGENTS.md", "backend.ps1", "backend-core.ps1", "signal-stop.ps1",
             "backend-settings.example.json", "backend.env.example", "logback-windows.xml",
             "update-and-start.bat", "start-backend.bat", "stop-backend.bat", "view-logs.bat"]
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            if name.endswith((".bat", ".ps1")):
                text = (directory / name).read_text(encoding="utf-8-sig").replace("\r\n", "\n")
                payload = text.replace("\n", "\r\n").encode("utf-8")
                if name.endswith(".ps1"):
                    payload = b"\xef\xbb\xbf" + payload
                archive.writestr(name, payload)
            else:
                archive.write(directory / name, name)
        archive.write(readme, "WINDOWS.md")


def publish(client, bucket, prefix, commit, build_id, jar, launcher, is_current):
    """上传失败或旧提交均不得覆盖跟随清单；重复构建使用独立目录。"""
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("发布提交必须为完整 SHA")
    if not re.fullmatch(r"[0-9]+-[0-9]+", build_id):
        raise ValueError("构建标识必须为 run-id-attempt")
    if not re.fullmatch(r"[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*", prefix):
        raise ValueError("发布前缀格式无效")
    base = f"{prefix}/releases/{commit}/{build_id}"
    manifest = {
        "schemaVersion": 1, "commit": commit, "javaVersion": 21,
        "publishedAt": datetime.now(timezone.utc).isoformat(),
        "jar": file_info(jar, f"{base}/butvan-backend.jar"),
        "launcher": file_info(launcher, f"{base}/windows-launcher.zip"),
    }
    for path, entry in [(jar, manifest["jar"]), (launcher, manifest["launcher"])]:
        with path.open("rb") as body:
            client.put_object(Bucket=bucket, Key=entry["key"], Body=body,
                              ContentLength=entry["size"], ContentType="application/octet-stream")
    client.put_object(Bucket=bucket, Key=f"{base}/butvan-backend.jar.sha256",
                      Body=(manifest["jar"]["sha256"] + "  butvan-backend.jar\n").encode(),
                      ContentType="text/plain")
    payload = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    client.put_object(Bucket=bucket, Key=f"{base}/manifest.json", Body=payload,
                      ContentType="application/json")
    promoted = is_current()
    if promoted:
        client.put_object(Bucket=bucket, Key=f"{prefix}/channels/develop.json", Body=payload,
                          ContentType="application/json", CacheControl="no-store, max-age=0")
    return manifest, promoted


def current_develop(commit):
    """上传完成后读取权威分支，避免慢构建或手动重跑覆盖新提交。"""
    repository = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("仓库标识格式无效")
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    request = Request(f"{api}/repos/{repository}/git/ref/heads/develop", headers={
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json",
    })
    with urlopen(request, timeout=30) as response:
        return json.load(response)["object"]["sha"] == commit


def main():
    """真实凭据仅从进程环境读取；失败不输出可能含认证信息的 SDK 异常。"""
    endpoint = os.environ["MINIO_ENDPOINT"]
    parsed = urlsplit(endpoint)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
        raise ValueError("MinIO 地址必须是无凭据、无路径的 HTTPS S3 API 入口")
    import boto3
    from botocore.config import Config
    client = boto3.client("s3", endpoint_url=endpoint,
                          aws_access_key_id=os.environ["MINIO_ACCESS_KEY"],
                          aws_secret_access_key=os.environ["MINIO_SECRET_KEY"],
                          region_name="us-east-1", config=Config(
                              signature_version="s3v4", s3={"addressing_style": "path"},
                              request_checksum_calculation="when_required",
                              retries={"max_attempts": 3, "mode": "standard"},
                              connect_timeout=15, read_timeout=180))
    backend = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory() as staging:
        launcher = Path(staging) / "windows-launcher.zip"
        package_launcher(backend / "deployment/windows", launcher, backend / "WINDOWS.md")
        commit = os.environ["GITHUB_SHA"]
        manifest, promoted = publish(
            client, os.environ["MINIO_BUCKET"], os.environ.get("MINIO_PREFIX", "backend"),
            commit, f'{os.environ["GITHUB_RUN_ID"]}-{os.environ["GITHUB_RUN_ATTEMPT"]}',
            backend / "server-network/target/server-network-0.0.1-SNAPSHOT.jar", launcher,
            lambda: current_develop(commit))
    summary = (f"## 后端 JAR 发布结果\n- 提交：`{commit}`\n"
               f'- JAR：`{manifest["jar"]["key"]}`\n'
               f'- 启动器：`{manifest["launcher"]["key"]}`\n'
               f"- develop 指针：{'已更新' if promoted else '已有新提交，未覆盖'}\n"
               "- Windows 仍由用户运行更新脚本，本工作流不部署服务器。\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary)
    print(summary)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"发布失败（{type(error).__name__}）；未自动部署，请检查连接、权限和构建配置。", file=sys.stderr)
        sys.exit(1)
