param(
  [Parameter(Mandatory=$true)][string]$Mac,
  [int]$TimeoutSeconds = 12
)

$ErrorActionPreference = "Stop"

function Await-WinRt($Async, [Type]$ResultType) {
  $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() |
    Where-Object {
      $_.Name -eq "AsTask" -and
      $_.GetParameters().Count -eq 1 -and
      $_.GetParameters()[0].ParameterType.Name -like "IAsyncOperation*"
    } |
    Select-Object -First 1
  $task = $asTask.MakeGenericMethod($ResultType).Invoke($null, @($Async))
  $task.Wait()
  return $task.Result
}

function To-Bytes($Hex) {
  $clean = $Hex -replace '[^0-9A-Fa-f]', ''
  for ($i = 0; $i -lt $clean.Length; $i += 2) {
    [Convert]::ToByte($clean.Substring($i, 2), 16)
  }
}

function Hex($Bytes) {
  (($Bytes | ForEach-Object { $_.ToString("X2") }) -join "")
}

function Le48([Int64]$Value) {
  0..5 | ForEach-Object { [byte](($Value -shr (8 * $_)) -band 0xff) }
}

function Checksum([byte[]]$Bytes) {
  $sum = 0
  foreach ($b in $Bytes) { $sum = ($sum + $b) -band 0xff }
  [byte]$sum
}

function Build-BindPacket {
  $now = [DateTimeOffset]::Now.ToUnixTimeSeconds()
  $payload = New-Object byte[] 13
  $payload[0] = 0x02 # non-Chinese locale bit, 24-hour time.
  $t = @(Le48 $now)
  [Array]::Copy($t, 0, $payload, 1, 6)
  [Array]::Copy($t, 0, $payload, 7, 6)

  $flag = [byte]0x02 # app's first normal command flag: not request, not split, needs response.
  $body = New-Object byte[] ($payload.Length + 4)
  $body[0] = $flag
  $body[1] = 0x60
  $body[2] = [byte]($payload.Length -band 0xff)
  $body[3] = [byte](($payload.Length -shr 8) -band 0xff)
  [Array]::Copy($payload, 0, $body, 4, $payload.Length)

  $packet = New-Object byte[] ($body.Length + 2)
  $packet[0] = 0x9e
  $packet[1] = Checksum $body
  [Array]::Copy($body, 0, $packet, 2, $body.Length)
  $packet
}

function Parse-QixPacket([byte[]]$Packet) {
  if ($Packet.Length -lt 7 -or $Packet[0] -ne 0x9e) { return $null }
  $cmd = $Packet[3]
  $len = $Packet[4] -bor ($Packet[5] -shl 8)
  if ($Packet.Length -lt ($len + 6)) { return $null }
  $payload = New-Object byte[] $len
  [Array]::Copy($Packet, 6, $payload, 0, $len)
  [pscustomobject]@{ Command = $cmd; Payload = $payload }
}

function Parse-BindResponse([byte[]]$Payload) {
  if ($Payload.Length -lt 17) { return $null }
  $state = $Payload[0]
  $pact = ($Payload[1..3] -join ".")
  $fw = ($Payload[4..13] | Where-Object { $_ -ne 0xff }) -join "."
  $platform = $Payload[14]
  $serial = $Payload[15] -bor ($Payload[16] -shl 8)
  [pscustomobject]@{
    State = $state
    PactVersion = $pact
    FirmwareVersion = $fw
    Platform = $platform
    SerialNumber = $serial
  }
}

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Devices.Bluetooth.BluetoothLEDevice, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
$null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristic, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.DataWriter, Windows.Storage.Streams, ContentType = WindowsRuntime]

$serviceUuid = [Guid]"C2E6FD00-E966-1000-8000-BEF9C223DF6A"
$notifyUuid = [Guid]"C2E6FD01-E966-1000-8000-BEF9C223DF6A"
$writeUuid = [Guid]"C2E6FD02-E966-1000-8000-BEF9C223DF6A"

$addr = [Convert]::ToUInt64(($Mac -replace ":", ""), 16)
Write-Host "Connecting to $Mac / 0x$($addr.ToString('X12'))"

$device = Await-WinRt ([Windows.Devices.Bluetooth.BluetoothLEDevice]::FromBluetoothAddressAsync($addr)) ([Windows.Devices.Bluetooth.BluetoothLEDevice])
if ($null -eq $device) { throw "Windows could not open the BLE device. Pair/connect it in Windows Bluetooth settings first, or disconnect it from the phone." }

$svcResult = Await-WinRt ($device.GetGattServicesForUuidAsync($serviceUuid)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult])
if ($svcResult.Services.Count -lt 1) { throw "Qix service not found on device." }
$service = $svcResult.Services[0]

$notifyResult = Await-WinRt ($service.GetCharacteristicsForUuidAsync($notifyUuid)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult])
$writeResult = Await-WinRt ($service.GetCharacteristicsForUuidAsync($writeUuid)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristicsResult])
if ($notifyResult.Characteristics.Count -lt 1 -or $writeResult.Characteristics.Count -lt 1) { throw "Notify/write characteristics not found." }

$notifyChar = $notifyResult.Characteristics[0]
$writeChar = $writeResult.Characteristics[0]
$received = New-Object System.Collections.Generic.List[byte]

$handler = [Windows.Foundation.TypedEventHandler[Windows.Devices.Bluetooth.GenericAttributeProfile.GattCharacteristic,Windows.Devices.Bluetooth.GenericAttributeProfile.GattValueChangedEventArgs]]{
  param($sender, $args)
  $reader = [Windows.Storage.Streams.DataReader]::FromBuffer($args.CharacteristicValue)
  $bytes = New-Object byte[] $args.CharacteristicValue.Length
  $reader.ReadBytes($bytes)
  Write-Host "RX $(Hex $bytes)"
  foreach ($b in $bytes) { $received.Add($b) }
}
$token = $notifyChar.add_ValueChanged($handler)

$ccc = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattClientCharacteristicConfigurationDescriptorValue]::Notify
$null = Await-WinRt ($notifyChar.WriteClientCharacteristicConfigurationDescriptorAsync($ccc)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCommunicationStatus])

$packet = [byte[]](Build-BindPacket)
Write-Host "TX $(Hex $packet)"
$writer = [Windows.Storage.Streams.DataWriter]::new()
$writer.WriteBytes($packet)
$status = Await-WinRt ($writeChar.WriteValueAsync($writer.DetachBuffer())) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattCommunicationStatus])
Write-Host "Write status: $status"

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
  Start-Sleep -Milliseconds 250
  $parsed = Parse-QixPacket ([byte[]]$received.ToArray())
  if ($parsed -and $parsed.Command -eq 0x61) {
    $bind = Parse-BindResponse $parsed.Payload
    if ($bind) {
      $bind | Format-List
      break
    }
  }
} while ((Get-Date) -lt $deadline)

$notifyChar.remove_ValueChanged($token)
if (-not $bind) {
  Write-Host "No bind response parsed. Try closing the phone app/disconnecting the phone, then run again."
}
