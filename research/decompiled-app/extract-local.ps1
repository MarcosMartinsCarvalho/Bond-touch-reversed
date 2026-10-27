param([string]$PackageName='com.impossible.bondtouch',[switch]$RunJadx)
$ErrorActionPreference='Stop'
foreach ($tool in @('adb','apktool')) { if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Install $tool and add it to PATH." } }
$out=Join-Path $PSScriptRoot ("local/"+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $out -Force | Out-Null
$paths=@(& adb shell pm path $PackageName) | ForEach-Object { ($_ -replace '^package:','').Trim() } | Where-Object { $_ }
if ($LASTEXITCODE -ne 0 -or -not $paths) { throw 'Could not locate the installed package on your connected device.' }
foreach ($remote in $paths) {
 $name=Split-Path $remote -Leaf
 $apk=Join-Path $out $name
 & adb pull $remote $apk
 if ($LASTEXITCODE -ne 0) { throw "Failed to pull $name" }
 & apktool d $apk -o (Join-Path $out ([IO.Path]::GetFileNameWithoutExtension($name)))
 if ($LASTEXITCODE -ne 0) { throw "APKTool failed for $name" }
}
if ($RunJadx) {
 if (-not (Get-Command jadx -ErrorAction SilentlyContinue)) { throw 'Install JADX and add it to PATH.' }
 & jadx --no-res --threads-count 4 -d (Join-Path $out 'jadx') (Join-Path $out 'base.apk')
 if ($LASTEXITCODE -ne 0) { Write-Warning 'JADX reported errors; inspect partial output and compare important methods with smali.' }
}
Write-Host "Local research output: $out"
