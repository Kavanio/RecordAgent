# Windows PowerShell 5.1 的 .NET File API 不支持普通 ADS 路径，使用文件系统 Provider。
# 分号用于生成器将同一份函数作为明文命令嵌入 BAT，不执行动态脚本文本。
function Copy-LauncherOrigin {
    param([string]$SourceFile, [string]$TargetFile);
    if ($env:OS -ne 'Windows_NT') { return };
    $streams = @(Get-Item -LiteralPath $SourceFile -Stream * -ErrorAction Stop);
    $origin = @($streams | Where-Object { $_.Stream -eq 'Zone.Identifier' });
    if ($origin.Count -eq 0) { return };
    $read = @{ LiteralPath = $SourceFile; Stream = 'Zone.Identifier'; ReadCount = 0; ErrorAction = 'Stop' };
    if ($PSVersionTable.PSVersion.Major -ge 6) { $read.AsByteStream = $true } else { $read.Encoding = 'Byte' };
    $zone = Get-Content @read;
    $write = @{ LiteralPath = $TargetFile; Stream = 'Zone.Identifier'; Value = $zone; ErrorAction = 'Stop' };
    if ($PSVersionTable.PSVersion.Major -ge 6) { $write.AsByteStream = $true } else { $write.Encoding = 'Byte' };
    Set-Content @write;
}
