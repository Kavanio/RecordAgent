# 使用 Windows 内置 HTTPS 下载，不依赖 MinIO Client、下载账户或别名。
function Assert-JarUrl {
    param([string]$Url)
    $parsed = $null
    if (-not [Uri]::TryCreate($Url, [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -ne 'https' -or $parsed.UserInfo -or $parsed.Query -or $parsed.Fragment -or
        -not $parsed.AbsolutePath.EndsWith('.jar', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'jarUrl 必须是不含凭据、查询参数和片段的 HTTPS JAR 地址。'
    }
}

function Save-PublicDownload {
    param([string]$Url, [string]$Destination, [long]$MaximumBytes, [switch]$RequireJarMetadata)
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
        $digest = $response.Headers['x-amz-meta-sha256']
        $commit = $response.Headers['x-amz-meta-commit']
        if ($RequireJarMetadata -and ($digest -cnotmatch '^[a-f0-9]{64}$' -or $commit -cnotmatch '^[a-f0-9]{40}$')) {
            throw 'JAR 响应缺少有效的 SHA-256 或提交信息，请先发布新版 JAR。原服务未停止。'
        }
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
        if ($total -eq 0 -or ($response.ContentLength -ge 0 -and $total -ne $response.ContentLength)) {
            throw 'JAR 下载不完整，原服务未停止。'
        }
        $complete = $true
        return [pscustomobject]@{ sha256 = $digest; commit = $commit; size = $total }
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

function Assert-ExecutableJar {
    param([string]$Path)
    $archive = $null
    $reader = $null
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [IO.Compression.ZipFile]::OpenRead($Path)
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if ($null -eq $entry -or $entry.Length -gt 64KB) { throw 'invalid manifest' }
        $reader = New-Object IO.StreamReader($entry.Open())
        $manifest = $reader.ReadToEnd()
        if ($manifest -notmatch '(?m)^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\r?$' -or
            $manifest -notmatch '(?m)^Start-Class: butvan\.agent\.network\.ButVanAgentApplication\r?$') {
            throw 'not executable backend jar'
        }
    } catch { throw '下载文件不是可执行后端 JAR，原服务未停止。' }
    finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $archive) { $archive.Dispose() }
    }
}

function Read-BackendSettings {
    param([string]$Directory, [string]$TemplateDirectory = $PSScriptRoot)
    foreach ($file in @('backend-settings.json','backend.env')) {
        $path = Join-Path $Directory $file
        if (-not (Test-Path -LiteralPath $path)) {
            $template = if ($file -eq 'backend.env') { 'backend.env.example' } else { 'backend-settings.example.json' }
            Copy-Item -LiteralPath (Join-Path $TemplateDirectory $template) -Destination $path
        }
        if ($env:OS -eq 'Windows_NT') {
            $acl = New-Object Security.AccessControl.FileSecurity
            $acl.SetAccessRuleProtection($true, $false)
            $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
            $acl.SetOwner($owner)
            foreach ($identity in @($owner, (New-Object Security.Principal.SecurityIdentifier('S-1-5-18')),
                (New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')))) {
                $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'Allow')
                $acl.AddAccessRule($rule)
            }
            Set-Acl -LiteralPath $path -AclObject $acl
        }
    }
    try {
        $settings = Get-Content -LiteralPath (Join-Path $Directory 'backend-settings.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($null -eq $settings -or $settings -is [array] -or $settings -is [string]) { throw 'invalid settings' }
    } catch { throw 'backend-settings.json 不是有效的 JSON 对象；Windows 路径中的反斜杠需要写成双反斜杠。' }
    # 保留已有配置；旧下载清单字段忽略，缺少新 JAR 地址时使用固定默认值。
    if (-not ($settings.PSObject.Properties.Name -contains 'jarUrl')) {
        $settings | Add-Member -NotePropertyName jarUrl -NotePropertyValue 'https://minio.server.butvan.top/recordagent/backend/butvan-backend.jar'
    }
    Assert-JarUrl $settings.jarUrl
    return $settings
}
