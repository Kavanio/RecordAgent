# BAT 内置的明文引导；由普通 -File 调用，遵循现有执行策略。
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
        $text = [IO.File]::ReadAllText($self, [Text.Encoding]::UTF8)
        $match = [regex]::Match($text, '(?ms)^::BEGIN_RUNTIME\r?\n(.*?)^::END_RUNTIME\r?$')
        if (-not $match.Success) { throw '缺少明文运行文件。' }
        $payload = $match.Groups[1].Value
        $hasher = [Security.Cryptography.SHA256]::Create()
        try { $hash = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($payload))).Replace('-', '').ToLowerInvariant() }
        finally { $hasher.Dispose() }
        if ($hash -ne '__BUNDLE_SHA__') { throw 'BAT 文件不完整，请重新复制原文件。' }
        $expected = @(__SOURCE_FILES__)
        $files = @{}
        $name = $null
        $lines = New-Object 'Collections.Generic.List[string]'
        foreach ($line in ($payload -split "`r`n")) {
            if ($line.StartsWith('::FILE ')) {
                if ($null -ne $name) { throw '文件分段未结束。' }
                $name = $line.Substring(7)
                if ($name -notin $expected -or $files.ContainsKey($name)) { throw '明文文件名无效或重复。' }
                $lines.Clear()
            } elseif ($line -eq '::END_FILE') {
                if ($null -eq $name) { throw '文件分段无效。' }
                $files[$name] = [string]::Join("`r`n", $lines) + "`r`n"
                $name = $null
            } elseif ($null -ne $name -and $line.StartsWith('::|')) {
                [void]$lines.Add($line.Substring(3))
            } elseif ($line -ne '') { throw '明文文件分段损坏。' }
        }
        if ($null -ne $name -or $files.Count -ne $expected.Count) { throw '明文运行文件不完整。' }
        # 来源标记随派生文件保留，不自动解除互联网来源限制。
        $zone = $null
        if ($env:OS -eq 'Windows_NT') {
            try { $zone = [IO.File]::ReadAllBytes($self + ':Zone.Identifier') }
            catch [IO.FileNotFoundException] { $zone = $null }
        }
        $runtime = Join-Path (Join-Path $data 'launcher') $hash
        [void][IO.Directory]::CreateDirectory($runtime)
        foreach ($name in $expected) {
            $target = Join-Path $runtime $name
            # 相同文件保持不动，避免另一入口读取时被截断；损坏缓存从已校验明文恢复。
            $encoding = New-Object Text.UTF8Encoding ($name.EndsWith('.ps1'))
            $bytes = [byte[]]($encoding.GetPreamble() + $encoding.GetBytes($files[$name]))
            $matches = (Test-Path -LiteralPath $target) -and
                ([BitConverter]::ToString([IO.File]::ReadAllBytes($target)) -eq [BitConverter]::ToString($bytes))
            if (-not $matches) { [IO.File]::WriteAllBytes($target, $bytes) }
            if ($null -ne $zone) { [IO.File]::WriteAllBytes($target + ':Zone.Identifier', $zone) }
        }
        [IO.File]::WriteAllText((Join-Path $runtime 'complete'), $hash)
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
    Write-Host '启动器准备失败，请检查 BAT 是否完整、目录权限和系统脚本执行策略。' -ForegroundColor Red
    exit 1
}
