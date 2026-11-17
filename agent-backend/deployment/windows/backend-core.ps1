# 更新边界与校验函数：无顶层副作用，可用临时目录与模拟操作验证。
Set-StrictMode -Version Latest

function Assert-ReleaseManifest {
    param($Manifest, [string]$Prefix)
    if ($Manifest.schemaVersion -ne 1 -or $Manifest.javaVersion -ne 21 -or
        $Manifest.commit -cnotmatch '^[a-f0-9]{40}$') {
        throw '版本清单格式或 Java 版本不支持。'
    }
    $keyPattern = '^' + [regex]::Escape($Prefix) + '/releases/' + $Manifest.commit + '/[0-9]+-[0-9]+/butvan-backend\.jar$'
    if ($Manifest.jar.key -cnotmatch $keyPattern -or
        $Manifest.jar.sha256 -cnotmatch '^[a-f0-9]{64}$' -or
        $Manifest.jar.size -isnot [ValueType] -or $Manifest.jar.size -le 0 -or
        $Manifest.jar.size -gt 1GB -or [long]$Manifest.jar.size -ne $Manifest.jar.size) {
        throw 'JAR 路径、大小或 SHA-256 清单字段无效。'
    }
}

function Assert-DownloadedJar {
    param([string]$Path, $Entry)
    if ((Get-Item -LiteralPath $Path).Length -ne [long]$Entry.size -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Entry.sha256) {
        throw '下载文件大小或 SHA-256 校验失败，原服务未停止。'
    }
}

function Invoke-BackendUpdate {
    param([scriptblock]$Download, [scriptblock]$Stop, [scriptblock]$Backup,
          [scriptblock]$Install, [scriptblock]$Start)
    # 顺序是数据安全契约：校验完成后才停机，完整备份成功后才替换。
    $release = & $Download
    & $Stop
    & $Backup
    & $Install $release
    & $Start
}

function Read-BackendEnvironment {
    param([string]$Path)
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)) {
        if ($line.Trim().Length -eq 0 -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'backend.env 存在无效行；必须为原始 KEY=value。' }
        $name = $Matches[1]
        $value = $Matches[2]
        # 只允许业务配置，禁止注入 Java 选项、PATH 或下载凭据到后端进程。
        if ($name -notmatch '^BUTVAN_' -or $name -match '^BUTVAN_NATIVE_' -or $name -eq 'BUTVAN_API_REQUIRE_TOKEN') {
            throw 'backend.env 只接受 BUTVAN_ 业务配置，不接受运行器保留字段。'
        }
        if ($values.ContainsKey($name)) { throw 'backend.env 存在重复配置键。' }
        $values[$name] = $value
    }
    if (-not $values.ContainsKey('BUTVAN_API_TOKEN') -or $values['BUTVAN_API_TOKEN'].Trim().Length -lt 32) {
        throw '请在 backend.env 配置至少 32 字符的 BUTVAN_API_TOKEN。'
    }
    if ($values.ContainsKey('BUTVAN_SERVER_SSL_ENABLED') -and $values['BUTVAN_SERVER_SSL_ENABLED'] -ne 'false') {
        throw '当前运行器使用本机 HTTP 健康检查，请由现有反向代理提供 HTTPS。'
    }
    return $values
}

function ConvertTo-NativeArgument {
    param([string]$Value)
    if ($Value.Contains('"') -or $Value.Contains("`r") -or $Value.Contains("`n")) {
        throw '启动路径或参数不得包含双引号或换行。'
    }
    # Windows argv 规则：闭引号前的反斜杠必须成对。
    return '"' + ($Value -replace '(\\+)$', '$1$1') + '"'
}
