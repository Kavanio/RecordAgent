# BAT 内置的启动引导；执行前由生成器替换固定包摘要。
$ErrorActionPreference = 'Stop'
try {
    $self = $env:BUTVAN_LAUNCHER_FILE
    $root = [IO.Path]::GetDirectoryName($self)
    $data = Join-Path $root 'data'
    [void][IO.Directory]::CreateDirectory($data)
    $lock = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ($null -eq $lock) {
        try { $lock = [IO.File]::Open((Join-Path $data 'bootstrap.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
        catch {
            if ([DateTime]::UtcNow -ge $deadline) { throw '另一个启动器正在准备文件。' }
            Start-Sleep -Milliseconds 100
        }
    }
    try {
        $text = [IO.File]::ReadAllText($self)
        $marker = ':__BUTVAN_PAYLOAD__'
        $bytes = [Convert]::FromBase64String($text.Substring($text.LastIndexOf($marker) + $marker.Length).Trim())
        $hasher = [Security.Cryptography.SHA256]::Create()
        try { $hash = [BitConverter]::ToString($hasher.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant() }
        finally { $hasher.Dispose() }
        if ($hash -ne '__BUNDLE_SHA__') { throw 'BAT 文件不完整，请重新复制原文件。' }
        $runtime = Join-Path (Join-Path $data 'launcher') $hash
        $complete = Join-Path $runtime 'complete'
        if (-not (Test-Path -LiteralPath $complete)) {
            Add-Type -AssemblyName System.IO.Compression
            [void][IO.Directory]::CreateDirectory($runtime)
            $memory = New-Object IO.MemoryStream(,$bytes)
            $archive = New-Object IO.Compression.ZipArchive($memory)
            try {
                foreach ($entry in $archive.Entries) {
                    if ([IO.Path]::GetFileName($entry.FullName) -ne $entry.FullName) { throw 'BAT 内置文件名无效。' }
                    $source = $entry.Open()
                    $target = [IO.File]::Create((Join-Path $runtime $entry.FullName))
                    try { $source.CopyTo($target) }
                    finally { $target.Dispose(); $source.Dispose() }
                }
                [IO.File]::WriteAllText($complete, $hash)
            } finally { $archive.Dispose(); $memory.Dispose() }
        }
        foreach ($name in @('backend.env','backend-settings.json')) {
            $old = Join-Path $root $name
            $current = Join-Path $data $name
            if ((Test-Path -LiteralPath $old) -and -not (Test-Path -LiteralPath $current)) {
                Copy-Item -LiteralPath $old -Destination $current
            }
        }
    } finally { $lock.Dispose() }
    if ($env:BUTVAN_LAUNCHER_ACTION -eq 'Extract') { return }
    & (Join-Path $runtime 'backend.ps1') -Action $env:BUTVAN_LAUNCHER_ACTION -DeploymentRoot $data
} catch {
    Write-Host '启动器准备失败，请将原始 BAT 完整复制到可写入目录后重试。' -ForegroundColor Red
    exit 1
}
