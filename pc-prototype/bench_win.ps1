# Kiyas setini Windows OCR ile okur (uygulamanin kullandigi on islemenin aynisiyla).
# Sonuclari win_<ad>.txt olarak yazar; esiklenmis goruntuyu de pre_<ad>.bmp olarak birakir
# ki ikinci motor ayni girdiyle denenebilsin.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime

[void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Storage.FileAccessMode, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime]

$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Wait-WinRT($op, $type) {
  $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); [void]$t.Wait(-1); $t.Result
}
$ocr = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage((New-Object Windows.Globalization.Language 'en-US'))

function Read-Image([string]$path) {
  $f = Wait-WinRT ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
  $s = Wait-WinRT ($f.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
  try {
    $d = Wait-WinRT ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($s)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $b = Wait-WinRT ($d.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $r = Wait-WinRT ($ocr.RecognizeAsync($b)) ([Windows.Media.Ocr.OcrResult])
    $out = @(); foreach ($l in $r.Lines) { $out += $l.Text }
    return ($out -join ' ')
  } finally { $s.Dispose() }
}

# app.ps1'deki Get-TextThreshold ve esikleme ile birebir ayni
$appSrc = Get-Content (Join-Path $PSScriptRoot 'app.ps1') -Raw -Encoding UTF8
$i = $appSrc.IndexOf('function Get-TextThreshold')
$j = $appSrc.IndexOf('function Clear-Junk')
Invoke-Expression $appSrc.Substring($i, $j - $i)

function Write-Preprocessed($raw, [string]$out) {
  $rw = $raw.Width; $rh = $raw.Height
  $t = (Get-TextThreshold $raw) / 255.0
  $c = 24.0
  $off = 0.5 + $t * $c
  $m = New-Object 'single[][]' 5
  for ($k = 0; $k -lt 5; $k++) { $m[$k] = New-Object single[] 5 }
  $rr = [single](-0.299 * $c); $gg = [single](-0.587 * $c); $bb = [single](-0.114 * $c)
  $m[0][0] = $rr; $m[0][1] = $rr; $m[0][2] = $rr
  $m[1][0] = $gg; $m[1][1] = $gg; $m[1][2] = $gg
  $m[2][0] = $bb; $m[2][1] = $bb; $m[2][2] = $bb
  $m[3][3] = [single]1.0
  $m[4][0] = [single]$off; $m[4][1] = [single]$off; $m[4][2] = [single]$off; $m[4][4] = [single]1.0
  $attr = New-Object System.Drawing.Imaging.ImageAttributes
  $attr.SetColorMatrix((New-Object System.Drawing.Imaging.ColorMatrix (, $m)))
  $dw = $rw * 2; $dh = $rh * 2
  $big = New-Object System.Drawing.Bitmap $dw, $dh
  $g = [System.Drawing.Graphics]::FromImage($big)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $dest = New-Object System.Drawing.Rectangle 0, 0, $dw, $dh
  $g.DrawImage($raw, $dest, 0, 0, $rw, $rh, [System.Drawing.GraphicsUnit]::Pixel, $attr)
  $g.Dispose(); $attr.Dispose()
  $big.Save($out, [System.Drawing.Imaging.ImageFormat]::Bmp)
  $big.Dispose()
  return [int]($t * 255)
}

$dir = Join-Path $env:TEMP 'ocrbench'
foreach ($jpg in Get-ChildItem $dir -Filter *.jpg | Sort-Object Name) {
  $name = $jpg.BaseName
  $img = [System.Drawing.Image]::FromFile($jpg.FullName)
  $raw = New-Object System.Drawing.Bitmap $img
  $img.Dispose()

  $pre = Join-Path $dir ('pre_' + $name + '.bmp')
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $thr = Write-Preprocessed $raw $pre
  $sw.Stop()
  $prep = $sw.ElapsedMilliseconds

  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $text = Read-Image $pre
  $sw.Stop()

  Set-Content -Path (Join-Path $dir ('win_' + $name + '.txt')) -Value $text -Encoding UTF8
  '{0,-14} esik {1,3}  hazirlik {2,3}ms  ocr {3,3}ms' -f $name, $thr, $prep, $sw.ElapsedMilliseconds
  $raw.Dispose()
}
