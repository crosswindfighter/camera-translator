# Altyazi bolgesini fareyle cizdirir, oyun adiyla kaydeder, icinde ne okundugunu gosterir.
#
#   powershell -ExecutionPolicy Bypass -STA -File pick_region.ps1 -Game RDR2
#   powershell -ExecutionPolicy Bypass -STA -File pick_region.ps1 -List
#
# Fare ile surukle, Enter onayla, Esc iptal, yeniden surukleyerek degistir.
# Kayit: regions.json (betikle ayni klasor)

param(
  [string]$Game = '',
  [int]$Monitor = -1,
  [switch]$List,
  [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Runtime.WindowsRuntime

$configPath = Join-Path $PSScriptRoot 'regions.json'

function Read-Config {
  if (Test-Path $configPath) {
    try { return Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
  }
  return (New-Object PSObject)
}

function Write-Config($config) {
  $config | ConvertTo-Json -Depth 5 | Out-File -FilePath $configPath -Encoding utf8
}

if ($List) {
  $cfg = Read-Config
  $names = @($cfg.PSObject.Properties.Name)
  if ($names.Count -eq 0) { Write-Host 'Kayitli bolge yok.'; exit 0 }
  Write-Host 'Kayitli bolgeler:' -ForegroundColor Cyan
  foreach ($n in $names) {
    $r = $cfg.$n
    Write-Host ('  {0,-16} {1},{2}  {3}x{4}' -f $n, $r.X, $r.Y, $r.W, $r.H)
  }
  exit 0
}

if ($Game -eq '') {
  Write-Host 'Oyun adi gerekli: -Game RDR2' -ForegroundColor Red
  exit 1
}

# ---- OCR (secilen kutuda ne okundugunu gostermek icin) ----
[void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Graphics.Imaging.SoftwareBitmap, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Storage.FileAccessMode, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime]

$asTaskMethod = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
  })[0]

function Wait-WinRT($operation, $resultType) {
  $task = $asTaskMethod.MakeGenericMethod($resultType).Invoke($null, @($operation))
  [void]$task.Wait(-1)
  return $task.Result
}

$ocr = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage((New-Object Windows.Globalization.Language 'en-US'))

function Show-WhatIsRead([int]$rx, [int]$ry, [int]$rw, [int]$rh) {
  if ($null -eq $ocr) { Write-Host 'OCR motoru yok, onizleme atlandi.' -ForegroundColor Yellow; return }
  $scale = 2
  $raw = New-Object System.Drawing.Bitmap $rw, $rh
  $g = [System.Drawing.Graphics]::FromImage($raw)
  $g.CopyFromScreen($rx, $ry, 0, 0, (New-Object System.Drawing.Size $rw, $rh))
  $g.Dispose()
  $big = New-Object System.Drawing.Bitmap ($rw * $scale), ($rh * $scale)
  $gb = [System.Drawing.Graphics]::FromImage($big)
  $gb.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $gb.DrawImage($raw, 0, 0, $rw * $scale, $rh * $scale)
  $gb.Dispose()
  $path = Join-Path $env:TEMP 'region_preview.bmp'
  $big.Save($path, [System.Drawing.Imaging.ImageFormat]::Bmp)
  $raw.Dispose(); $big.Dispose()

  $file = Wait-WinRT ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
  $stream = Wait-WinRT ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
  try {
    $decoder = Wait-WinRT ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bmp = Wait-WinRT ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $res = Wait-WinRT ($ocr.RecognizeAsync($bmp)) ([Windows.Media.Ocr.OcrResult])
  } finally { $stream.Dispose() }

  Write-Host ''
  Write-Host 'Bu kutunun icinde su an sunlar okunuyor:' -ForegroundColor Cyan
  $any = $false
  foreach ($line in $res.Lines) { Write-Host ('  > ' + $line.Text); $any = $true }
  if (-not $any) { Write-Host '  (bos - altyazi yoksa normal)' -ForegroundColor DarkGray }
}

# ---- Hedef ekran ----
$screens = @([System.Windows.Forms.Screen]::AllScreens)
if ($Monitor -ge 0 -and $Monitor -lt $screens.Count) {
  $target = $screens[$Monitor]
} else {
  $target = [System.Windows.Forms.Screen]::PrimaryScreen
}
$b = $target.Bounds

if ($SelfTest) {
  # Fare olmadan: ortada ornek bir kutu varsayip kayit ve onizleme yolunu dogrular
  $rx = $b.X + [int]($b.Width * 0.24)
  $ry = $b.Y + [int]($b.Height * 0.75)
  $rw = [int]($b.Width * 0.52)
  $rh = [int]($b.Height * 0.14)
  Write-Host ('SelfTest kutu: {0},{1} {2}x{3}' -f $rx, $ry, $rw, $rh)
  Show-WhatIsRead $rx $ry $rw $rh
  $cfg = Read-Config
  $cfg | Add-Member -NotePropertyName $Game -NotePropertyValue ([PSCustomObject]@{ X = $rx; Y = $ry; W = $rw; H = $rh }) -Force
  Write-Config $cfg
  Write-Host ''
  Write-Host ('Kaydedildi -> ' + $configPath) -ForegroundColor Green
  exit 0
}

# ---- Cizim ekrani ----
$script:startPt = $null
$script:curRect = New-Object System.Drawing.Rectangle 0, 0, 0, 0
$script:dragging = $false
$script:accepted = $false
$script:paintError = ''

$form = New-Object System.Windows.Forms.Form
$form.FormBorderStyle = 'None'
$form.StartPosition = 'Manual'
$form.Bounds = $b
$form.TopMost = $true
$form.ShowInTaskbar = $false
$form.BackColor = [System.Drawing.Color]::Black
$form.Opacity = 0.45
$form.Cursor = [System.Windows.Forms.Cursors]::Cross
$form.KeyPreview = $true

# Olay bloklari yalniz script kapsamindaki degiskenleri goruyor
$script:gameName = $Game
$script:baseX = $b.X
$script:baseY = $b.Y

# Onceki kayit varsa baslangic olarak goster
$existing = (Read-Config).$Game
if ($null -ne $existing) {
  $script:curRect = New-Object System.Drawing.Rectangle `
    ([int]$existing.X - $b.X), ([int]$existing.Y - $b.Y), ([int]$existing.W), ([int]$existing.H)
}

$form.Add_Paint({
    param($sender, $e)
    try {
      $g = $e.Graphics
      $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
      $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

      $info = New-Object System.Drawing.Font('Segoe UI', 22, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
      $white = [System.Drawing.Brushes]::White
      $g.DrawString(
        ('[' + $script:gameName + ']  Altyazinin ciktigi yerin etrafina fareyle dikdortgen ciz.   Enter = kaydet   Esc = vazgec'),
        $info, $white, 30, 30)

      $r = $script:curRect
      if ($r.Width -gt 2 -and $r.Height -gt 2) {
        $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 80, 255, 120)), 3
        $g.DrawRectangle($pen, $r)
        $pen.Dispose()
        $label = ('{0},{1}   {2} x {3}' -f ($r.X + $script:baseX), ($r.Y + $script:baseY), $r.Width, $r.Height)
        $ly = if ($r.Y -gt 40) { $r.Y - 34 } else { $r.Y + $r.Height + 8 }
        $g.DrawString($label, $info, $white, [float]$r.X, [float]$ly)
      }
      $info.Dispose()
    } catch {
      $script:paintError = $_.Exception.Message
    }
  })

$form.Add_MouseDown({
    param($sender, $e)
    $script:startPt = New-Object System.Drawing.Point $e.X, $e.Y
    $script:dragging = $true
  })

$form.Add_MouseMove({
    param($sender, $e)
    if (-not $script:dragging) { return }
    $sp = $script:startPt
    $x = [math]::Min($sp.X, $e.X); $y = [math]::Min($sp.Y, $e.Y)
    $w = [math]::Abs($e.X - $sp.X); $h = [math]::Abs($e.Y - $sp.Y)
    $script:curRect = New-Object System.Drawing.Rectangle $x, $y, $w, $h
    $sender.Invalidate()
  })

$form.Add_MouseUp({
    param($sender, $e)
    $script:dragging = $false
    $sender.Invalidate()
  })

$form.Add_KeyDown({
    param($sender, $e)
    if ($e.KeyCode -eq [System.Windows.Forms.Keys]::Escape) {
      $script:accepted = $false
      $sender.Close()
    } elseif ($e.KeyCode -eq [System.Windows.Forms.Keys]::Enter) {
      $script:accepted = $true
      $sender.Close()
    }
  })

$form.ShowDialog() | Out-Null

if ($script:paintError -ne '') {
  Write-Host ('CIZIM HATASI: ' + $script:paintError) -ForegroundColor Red
}

if (-not $script:accepted) {
  Write-Host 'Vazgecildi, kayit degismedi.' -ForegroundColor Yellow
  exit 0
}

$r = $script:curRect
if ($r.Width -lt 40 -or $r.Height -lt 20) {
  Write-Host 'Kutu cok kucuk, kaydedilmedi.' -ForegroundColor Red
  exit 1
}

$rx = $r.X + $b.X
$ry = $r.Y + $b.Y
Write-Host ('Secilen kutu: {0},{1}  {2}x{3}' -f $rx, $ry, $r.Width, $r.Height) -ForegroundColor Green

Start-Sleep -Milliseconds 350   # cizim ekrani kapansin, altindaki oyun gorunsun
Show-WhatIsRead $rx $ry $r.Width $r.Height

$cfg = Read-Config
$cfg | Add-Member -NotePropertyName $Game -NotePropertyValue ([PSCustomObject]@{ X = $rx; Y = $ry; W = $r.Width; H = $r.Height }) -Force
Write-Config $cfg
Write-Host ''
Write-Host ('Kaydedildi -> ' + $configPath) -ForegroundColor Green
Write-Host ('Calistirmak icin:  overlay.ps1 -Game ' + $Game) -ForegroundColor DarkGray
