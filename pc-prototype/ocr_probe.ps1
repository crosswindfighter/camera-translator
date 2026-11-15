# Windows'un kendi OCR motoruyla ekran bolgesi okuma denemesi.
# Amac: PC'de oyun altyazisi okunabiliyor mu, kac ms suruyor?
# Hicbir sey indirmez, tamamen cevrimdisi calisir.
#
# Kullanim:
#   powershell -ExecutionPolicy Bypass -File ocr_probe.ps1 -SelfTest
#   powershell -ExecutionPolicy Bypass -File ocr_probe.ps1 -Seconds 60
#   powershell -ExecutionPolicy Bypass -File ocr_probe.ps1 -X 400 -Y 800 -W 1200 -H 200

param(
  [int]$X = -1,
  [int]$Y = -1,
  [int]$W = 0,
  [int]$H = 0,
  [int]$IntervalMs = 300,
  [int]$Seconds = 60,
  [int]$Scale = 2,
  [switch]$SelfTest,
  [switch]$SaveFrames,
  [string]$LogFile = ""
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Runtime.WindowsRuntime

[void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Graphics.Imaging.SoftwareBitmap, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Storage.FileAccessMode, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime]

# ---- WinRT async cagrilarini senkron bekleme yardimcisi ----
$asTaskMethod = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and
    $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
  })[0]

function Wait-WinRT($operation, $resultType) {
  $task = $asTaskMethod.MakeGenericMethod($resultType).Invoke($null, @($operation))
  [void]$task.Wait(-1)
  return $task.Result
}

# ---- OCR motoru ----
$language = New-Object Windows.Globalization.Language 'en-US'
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($language)
if ($null -eq $engine) {
  Write-Host 'en-US OCR motoru olusturulamadi.' -ForegroundColor Red
  exit 1
}

$workDir = Join-Path $env:TEMP 'ocr_probe'
if (-not (Test-Path $workDir)) { [void](New-Item -ItemType Directory -Path $workDir) }

function Read-ImageText([string]$path) {
  $file = Wait-WinRT ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
  $stream = Wait-WinRT ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
  try {
    $decoder = Wait-WinRT ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap = Wait-WinRT ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $result = Wait-WinRT ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
    return $result
  } finally {
    $stream.Dispose()
  }
}

function Get-LineTexts($ocrResult) {
  $lines = @()
  foreach ($line in $ocrResult.Lines) { $lines += $line.Text }
  return $lines
}

# ---- Sahte metinle boru hatti dogrulamasi ----
if ($SelfTest) {
  Write-Host 'Boru hatti testi: bilinen metni resme yazip geri okuyoruz.' -ForegroundColor Cyan
  $samples = @(
    'You ever get that feeling, Arthur?',
    'We need to lay low for a while.',
    "I ain't got nothing left to lose."
  )
  $testPath = Join-Path $workDir 'selftest.png'
  $bmp = New-Object System.Drawing.Bitmap 1200, 260
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.Clear([System.Drawing.Color]::Black)
  $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
  $font = New-Object System.Drawing.Font 'Segoe UI', 26, ([System.Drawing.FontStyle]::Regular)
  $brush = [System.Drawing.Brushes]::White
  $y = 20
  foreach ($s in $samples) {
    $g.DrawString($s, $font, $brush, 20, $y)
    $y += 70
  }
  $g.Dispose()
  $bmp.Save($testPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()

  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $res = Read-ImageText $testPath
  $sw.Stop()

  Write-Host ("OCR suresi: {0} ms" -f $sw.ElapsedMilliseconds)
  Write-Host 'Beklenen:'
  foreach ($s in $samples) { Write-Host ("  > " + $s) }
  Write-Host 'Okunan:'
  foreach ($l in (Get-LineTexts $res)) { Write-Host ("  < " + $l) }
  Write-Host ("Resim: " + $testPath)
  exit 0
}

# ---- Ekran bolgesi ----
$screen = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
if ($W -le 0 -or $H -le 0) {
  # Varsayilan: ekranin alt ucte birinin orta bandi (tipik altyazi yeri)
  $W = [int]($screen.Width * 0.76)
  $H = [int]($screen.Height * 0.22)
  $X = [int](($screen.Width - $W) / 2)
  $Y = [int]($screen.Height - $H - ($screen.Height * 0.04))
}
if ($X -lt 0) { $X = 0 }
if ($Y -lt 0) { $Y = 0 }

Write-Host ("Ekran: {0}x{1}   Okunan bolge: {2},{3} {4}x{5}   Buyutme: {6}x" -f `
    $screen.Width, $screen.Height, $X, $Y, $W, $H, $Scale) -ForegroundColor Cyan
Write-Host ("Sure: {0} sn   Aralik: {1} ms" -f $Seconds, $IntervalMs) -ForegroundColor Cyan
Write-Host 'Durdurmak icin Ctrl+C.' -ForegroundColor DarkGray
Write-Host ''

$framePath = Join-Path $workDir 'frame.bmp'
$size = New-Object System.Drawing.Size $W, $H

function Save-Region([string]$out) {
  $raw = New-Object System.Drawing.Bitmap $W, $H
  $g = [System.Drawing.Graphics]::FromImage($raw)
  $g.CopyFromScreen($X, $Y, 0, 0, $size)
  $g.Dispose()
  if ($Scale -gt 1) {
    $bw = $W * $Scale
    $bh = $H * $Scale
    $big = New-Object System.Drawing.Bitmap $bw, $bh
    $gb = [System.Drawing.Graphics]::FromImage($big)
    $gb.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gb.DrawImage($raw, 0, 0, $bw, $bh)
    $gb.Dispose()
    # BMP sikistirmasiz: PNG kodlamasi kare basina ~100 ms yiyordu
    $big.Save($out, [System.Drawing.Imaging.ImageFormat]::Bmp)
    $big.Dispose()
  } else {
    $raw.Save($out, [System.Drawing.Imaging.ImageFormat]::Bmp)
  }
  $raw.Dispose()
}

$deadline = (Get-Date).AddSeconds($Seconds)
$lastText = ''
$frames = 0
$hits = 0
$capMs = New-Object System.Collections.ArrayList
$ocrMs = New-Object System.Collections.ArrayList
$log = New-Object System.Collections.ArrayList

while ((Get-Date) -lt $deadline) {
  $loopStart = [System.Diagnostics.Stopwatch]::StartNew()

  $sw1 = [System.Diagnostics.Stopwatch]::StartNew()
  Save-Region $framePath
  $sw1.Stop()

  $sw2 = [System.Diagnostics.Stopwatch]::StartNew()
  $res = Read-ImageText $framePath
  $sw2.Stop()

  $frames++
  [void]$capMs.Add($sw1.ElapsedMilliseconds)
  [void]$ocrMs.Add($sw2.ElapsedMilliseconds)

  $lines = Get-LineTexts $res
  $text = ($lines -join ' ').Trim()

  if ($text -ne '' -and $text -ne $lastText) {
    $hits++
    $stamp = (Get-Date).ToString('HH:mm:ss.fff')
    $line = ("[{0}] yakala {1} ms | ocr {2} ms | {3}" -f $stamp, $sw1.ElapsedMilliseconds, $sw2.ElapsedMilliseconds, $text)
    Write-Host $line
    [void]$log.Add($line)
    if ($SaveFrames) {
      Copy-Item $framePath (Join-Path $workDir ("hit_{0}.bmp" -f $hits)) -Force
    }
    $lastText = $text
  }

  $loopStart.Stop()
  $rest = $IntervalMs - $loopStart.ElapsedMilliseconds
  if ($rest -gt 0) { Start-Sleep -Milliseconds $rest }
}

function Get-Median($list) {
  if ($list.Count -eq 0) { return 0 }
  $sorted = @($list | Sort-Object)
  return $sorted[[int]([math]::Floor($sorted.Count / 2))]
}

Write-Host ''
Write-Host '================ OZET ================' -ForegroundColor Green
Write-Host ("Kare sayisi      : {0}" -f $frames)
Write-Host ("Yeni metin       : {0}" -f $hits)
Write-Host ("Yakalama medyan  : {0} ms" -f (Get-Median $capMs))
Write-Host ("OCR medyan       : {0} ms" -f (Get-Median $ocrMs))
Write-Host ("Kare basi toplam : {0} ms" -f ((Get-Median $capMs) + (Get-Median $ocrMs)))
Write-Host ("Calisma klasoru  : {0}" -f $workDir)

if ($LogFile -ne '') {
  $log | Out-File -FilePath $LogFile -Encoding utf8
  Write-Host ("Kayit            : {0}" -f $LogFile)
}
