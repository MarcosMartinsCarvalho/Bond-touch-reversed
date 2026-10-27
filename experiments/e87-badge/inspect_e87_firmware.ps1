param(
    [string]$Firmware = "QX7613_E87_C32_OTA_APP_V1.0.3_20260104.bin"
)

$path = Resolve-Path -LiteralPath $Firmware
$bytes = [System.IO.File]::ReadAllBytes($path.Path)

function Find-Ascii {
    param(
        [byte[]]$Data,
        [string]$Needle,
        [int]$Limit = 20
    )
    $pattern = [System.Text.Encoding]::ASCII.GetBytes($Needle)
    $hits = New-Object System.Collections.Generic.List[int]
    for ($i = 0; $i -le $Data.Length - $pattern.Length; $i++) {
        $ok = $true
        for ($j = 0; $j -lt $pattern.Length; $j++) {
            if ($Data[$i + $j] -ne $pattern[$j]) {
                $ok = $false
                break
            }
        }
        if ($ok) {
            $hits.Add($i)
            if ($hits.Count -ge $Limit) { break }
        }
    }
    return $hits
}

function Entropy {
    param(
        [byte[]]$Data,
        [int]$Offset,
        [int]$Length
    )
    $counts = @{}
    $end = [Math]::Min($Offset + $Length, $Data.Length)
    for ($i = $Offset; $i -lt $end; $i++) {
        $v = $Data[$i]
        $counts[$v] = 1 + ($counts[$v] -as [int])
    }
    $actual = $end - $Offset
    $h = 0.0
    foreach ($count in $counts.Values) {
        $p = $count / $actual
        $h += -$p * [Math]::Log($p, 2)
    }
    return $h
}

$version = [System.Text.Encoding]::ASCII.GetString($bytes, 3, 10).Trim([char]0)
$payloadLength = [BitConverter]::ToUInt32($bytes, 13)

"Firmware: $($path.Path)"
"File length: $($bytes.Length)"
"Magic: " + (($bytes[0..1] | ForEach-Object { "{0:X2}" -f $_ }) -join " ")
"Update type byte: 0x{0:X2}" -f $bytes[2]
"Version field: $version"
"Header payload length: $payloadLength"
"Actual payload length: $($bytes.Length - 27)"
""
"Interesting ASCII markers:"
foreach ($needle in @(
    "JLUFW",
    "lcflash_ota.bin",
    "app_dir_head",
    "OTA_TOUT",
    "quicklz",
    "flash",
    "AC707N",
    "NORFLASH_DTR_EN",
    "NORFLASH_WPS_EN"
)) {
    $hits = Find-Ascii -Data $bytes -Needle $needle
    $formatted = ($hits | ForEach-Object { "0x{0:X}" -f $_ }) -join ", "
    "${needle}: $formatted"
}
""
"Entropy, 4 KiB windows at start/end and low-entropy regions:"
for ($offset = 0; $offset -lt $bytes.Length; $offset += 4096) {
    $entropy = Entropy -Data $bytes -Offset $offset -Length 4096
    if ($offset -lt 65536 -or $offset -gt ($bytes.Length - 65536) -or $entropy -lt 6.5) {
        "0x{0:X8} {1:N3}" -f $offset, $entropy
    }
}
