# 使用 Windows 内置 HTTPS 下载，不依赖 MinIO Client、下载账户或别名。
function Get-ReleaseObjectUrl {
    param([string]$BaseUrl, [string]$Key)
    $parsed = $null
    if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -ne 'https' -or $parsed.UserInfo -or $parsed.Query -or $parsed.Fragment) {
        throw 'downloadBaseUrl 必须是不含凭据、查询参数和片段的 HTTPS 地址。'
    }
    if ($Key -cnotmatch '^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_.-]+)+$' -or
        @($Key.Split('/') | Where-Object { $_ -eq '.' -or $_ -eq '..' }).Count -gt 0) {
        throw '下载对象路径无效。'
    }
    return $BaseUrl.TrimEnd('/') + '/' + $Key
}

function Save-PublicDownload {
    param([string]$Url, [string]$Destination, [long]$MaximumBytes)
    if ($MaximumBytes -le 0 -or $MaximumBytes -gt 1GB) { throw '下载大小上限无效。' }
    $response = $null
    $source = $null
    $target = $null
    $complete = $false
    $ownsTarget = $false
    try {
        $request = [Net.HttpWebRequest]::Create($Url)
        $request.Method = 'GET'
        $request.AllowAutoRedirect = $false
        $request.Timeout = 30000
        $request.ReadWriteTimeout = 30000
        $request.CachePolicy = New-Object Net.Cache.RequestCachePolicy([Net.Cache.RequestCacheLevel]::NoCacheNoStore)
        $request.Headers['Cache-Control'] = 'no-cache'
        $response = $request.GetResponse()
        if ([int]$response.StatusCode -ne 200) { throw '下载地址未返回 HTTP 200，请检查固定 HTTPS 地址。' }
        if ($response.ContentLength -gt $MaximumBytes) { throw '下载文件超过允许大小，原服务未停止。' }
        $source = $response.GetResponseStream()
        $target = [IO.File]::Open($Destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        $ownsTarget = $true
        $buffer = New-Object byte[] 65536
        $total = 0L
        while (($count = $source.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $total += $count
            if ($total -gt $MaximumBytes) { throw '实际下载大小超过限制，原服务未停止。' }
            $target.Write($buffer, 0, $count)
        }
        $complete = $true
    } catch [Net.WebException] {
        $status = if ($null -ne $_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        if ($null -ne $_.Exception.Response) { $_.Exception.Response.Close() }
        if ($status -eq 403) { throw '下载被拒绝（HTTP 403），请检查发布目录的匿名只读策略。原服务未停止。' }
        if ($status -eq 404) { throw '发布文件不存在（HTTP 404），请确认流水线已成功发布。原服务未停止。' }
        throw 'HTTPS 下载失败，请检查网络、TLS 证书及下载地址。原服务未停止。'
    } finally {
        if ($null -ne $target) { $target.Dispose() }
        if ($null -ne $source) { $source.Dispose() }
        if ($null -ne $response) { $response.Close() }
        if ($ownsTarget -and -not $complete -and (Test-Path -LiteralPath $Destination)) {
            Remove-Item -LiteralPath $Destination -Force
        }
    }
}

function Read-BackendSettings {
    param([string]$Directory)
    foreach ($file in @('backend-settings.json','backend.env')) {
        $path = Join-Path $Directory $file
        if (-not (Test-Path -LiteralPath $path)) {
            $template = if ($file -eq 'backend.env') { 'backend.env.example' } else { 'backend-settings.example.json' }
            Copy-Item -LiteralPath (Join-Path $Directory $template) -Destination $path
            Write-Host "已创建 $file；backend.env 中需填写原部署的业务 Token。"
        }
    }
    try {
        $settings = Get-Content -LiteralPath (Join-Path $Directory 'backend-settings.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($null -eq $settings -or $settings -is [array] -or $settings -is [string]) { throw 'invalid settings' }
    } catch { throw 'backend-settings.json 不是有效的 JSON 对象；Windows 路径中的反斜杠需要写成双反斜杠。' }
    # 兼容首版配置；原 mcExe/minioAlias/bucket 字段不再读取，也不覆盖用户文件。
    if (-not ($settings.PSObject.Properties.Name -contains 'downloadBaseUrl')) {
        $settings | Add-Member -NotePropertyName downloadBaseUrl -NotePropertyValue 'https://minio.server.butvan.top/recordagent'
    }
    $null = Get-ReleaseObjectUrl $settings.downloadBaseUrl 'backend/channels/develop.json'
    return $settings
}
