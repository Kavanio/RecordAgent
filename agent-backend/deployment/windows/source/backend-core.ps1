# 更新边界与校验函数：无顶层副作用，可用临时目录与模拟操作验证。
Set-StrictMode -Version Latest

function Assert-JarMetadata {
    param($Entry)
    if ($Entry.commit -cnotmatch '^[a-f0-9]{40}$' -or $Entry.sha256 -cnotmatch '^[a-f0-9]{64}$' -or
        $Entry.size -isnot [ValueType] -or $Entry.size -le 0 -or $Entry.size -gt 1GB -or
        [long]$Entry.size -ne $Entry.size) {
        throw 'JAR 的提交、大小或 SHA-256 元数据无效，原服务未停止。'
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

function Initialize-BackendToken {
    param([string]$Path)
    $lines = [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)
    $tokenLine = @($lines | Where-Object { $_ -match '^BUTVAN_API_TOKEN=' })
    if ($tokenLine.Count -ne 1 -or $tokenLine[0] -ne 'BUTVAN_API_TOKEN=') { return }
    Write-Host '首次运行，请输入原后端的 API Token；输入不显示，配置只保存于本机。'
    $secret = Read-Host 'API Token' -AsSecureString
    $value = (New-Object Net.NetworkCredential('', $secret)).Password
    if ($value.Trim().Length -lt 32 -or $value.Contains("`r") -or $value.Contains("`n")) {
        throw 'API Token 至少需要 32 字符，且不能包含换行。'
    }
    $content = ($lines | ForEach-Object { if ($_ -eq 'BUTVAN_API_TOKEN=') { 'BUTVAN_API_TOKEN=' + $value.Trim() } else { $_ } }) -join "`r`n"
    [IO.File]::WriteAllText($Path, $content + "`r`n", (New-Object Text.UTF8Encoding $false))
}

function ConvertTo-NativeArgument {
    param([string]$Value)
    if ($Value.Contains('"') -or $Value.Contains("`r") -or $Value.Contains("`n")) {
        throw '启动路径或参数不得包含双引号或换行。'
    }
    # Windows argv 规则：闭引号前的反斜杠必须成对。
    return '"' + ($Value -replace '(\\+)$', '$1$1') + '"'
}
