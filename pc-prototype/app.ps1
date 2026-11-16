# CameraTranslator PC
# Oyun altyazisini okur, Turkceye cevirir, ingilizcenin tam ustune basar.
#
#   ekran alani -> Windows OCR -> OPUS (translate.py) -> seffaf katman
#
# Yazi boyutu ayarlanmaz: ingilizce altyazinin yuksekligine VE satir sayisina
# gore kendini ayarlar, boylece kutu buyumez ve yerinden oynamaz.

param(
  [switch]$SelfTest,
  [switch]$LogTest,
  [string]$Python = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[System.Windows.Forms.Application]::EnableVisualStyles()

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Native {
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint dwAffinity);
    [DllImport("user32.dll", SetLastError = true)]
    public static extern int GetWindowLong(IntPtr hWnd, int nIndex);
    [DllImport("user32.dll", SetLastError = true)]
    public static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);
    [DllImport("user32.dll")]
    public static extern short GetAsyncKeyState(int vKey);
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool ReleaseCapture();
    [DllImport("user32.dll")]
    public static extern IntPtr SendMessage(IntPtr hWnd, int msg, int wParam, int lParam);
}
"@

$root = $PSScriptRoot
$settingsPath = Join-Path $root 'settings.json'
$logPath = Join-Path $root 'app_log.txt'

# Python yorumlayicisi: -Python ile verilen, CT_PYTHON, yanindaki .venv, sonra PATH
function Resolve-Python([string]$given) {
  if ($given -and (Test-Path $given)) { return $given }
  if ($env:CT_PYTHON -and (Test-Path $env:CT_PYTHON)) { return $env:CT_PYTHON }
  foreach ($p in @(
      (Join-Path $root '.venv\Scripts\python.exe'),
      (Join-Path $root 'venv\Scripts\python.exe'),
      (Join-Path $env:TEMP 'opusvenv\Scripts\python.exe'))) {
    if (Test-Path $p) { return $p }
  }
  $cmd = Get-Command python.exe -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  return 'python'
}
$Python = Resolve-Python $Python

# Arayuzde gosterilen yazi tipleri: hepsi Windows'ta kurulu ve Turkce harfleri destekliyor
$FONTS = @('Segoe UI', 'Bahnschrift', 'Trebuchet MS', 'Verdana', 'Georgia')

function New-Rgb([int]$r, [int]$g, [int]$b) { [System.Drawing.Color]::FromArgb(255, $r, $g, $b) }

# Iki tema. Anahtarlar ayni; tema degisince $C'nin degerleri yerinde degisir ve
# Set-Theme kontrolleri eski renkten yeni renge esleyerek gezer. Boylece Paint
# icinde $C.X okuyan her cizim kendiliginden yeni temaya gecer.
$PALETTES = @{
  gece   = [ordered]@{
    Bg = (New-Rgb 18 18 22); Card = (New-Rgb 28 28 34); Card2 = (New-Rgb 36 36 44)
    Line = (New-Rgb 48 48 58); Text = (New-Rgb 235 235 242); Muted = (New-Rgb 132 132 148)
    Accent = (New-Rgb 255 206 122); Accent2 = (New-Rgb 234 170 80)
    Go = (New-Rgb 58 170 110); Stop = (New-Rgb 196 76 76)
  }
  gunduz = [ordered]@{
    Bg = (New-Rgb 244 245 249); Card = (New-Rgb 255 255 255); Card2 = (New-Rgb 237 239 245)
    Line = (New-Rgb 214 217 226); Text = (New-Rgb 24 26 33); Muted = (New-Rgb 104 109 124)
    Accent = (New-Rgb 176 118 16); Accent2 = (New-Rgb 214 152 48)
    Go = (New-Rgb 38 146 92); Stop = (New-Rgb 192 62 62)
  }
}
$C = @{}
foreach ($k in $PALETTES.gece.Keys) { $C[$k] = $PALETTES.gece[$k] }

# ---------------- Ayarlar ----------------
$defaults = [ordered]@{
  yaziTipi = 'Segoe UI'
  yaziRengi = '#FFFFFF'
  kutuRengi = '#000000'
  saydamlik = 1.0
  alan = $null
  ayrintiliKayit = $false
  # Arayuz temasi: 'gece' ya da 'gunduz'
  tema = 'gece'
  # Gelismis OCR duzeltme: bozuk Ingilizceyi yerel GPU modeli onarir, ceviriyi yine OPUS yapar
  gelismisOcr = $false
  llmModel = 'aya-expanse:8b'
}
$script:cfg = [PSCustomObject]$defaults
if (Test-Path $settingsPath) {
  try {
    $loaded = Get-Content $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($k in $defaults.Keys) { if ($null -ne $loaded.$k) { $script:cfg.$k = $loaded.$k } }
  } catch { }
}
if ($FONTS -notcontains [string]$script:cfg.yaziTipi) { $script:cfg.yaziTipi = $FONTS[0] }
function Save-Settings { $script:cfg | ConvertTo-Json -Depth 5 | Out-File -FilePath $settingsPath -Encoding utf8 }
function ConvertTo-Color([string]$hex, $fallback) {
  try { return [System.Drawing.ColorTranslator]::FromHtml($hex) } catch { return $fallback }
}

$READ_MS = 120
$HOLD_MS = 400

# ---------------- Kayit ----------------
$script:logBox = $null
function Write-Line([string]$text, [switch]$Quiet) {
  $stamp = (Get-Date).ToString('HH:mm:ss')
  $line = "[$stamp] $text"
  try { Add-Content -Path $logPath -Value $line -Encoding UTF8 } catch { }
  if ($null -ne $script:logBox) {
    try {
      $script:logBox.AppendText($line + "`r`n")
      $script:logBox.SelectionStart = $script:logBox.TextLength
      $script:logBox.ScrollToCaret()
    } catch { }
  }
}

# ---------------- Ayrintili kayit (gelistirici) ----------------
# Her okuma, ceviri ve cizim ayri ayri yazilir; sonradan incelemek icin.
# Yazma islemi tamponlanir, yoksa her karede diske yazmak donguyu yavaslatir.
$script:devPath = ''
$script:devBuf = New-Object System.Collections.ArrayList
$script:devLastFlush = Get-Date

function Write-Dev([string]$line) {
  if (-not [bool]$script:cfg.ayrintiliKayit -or $script:devPath -eq '') { return }
  [void]$script:devBuf.Add(((Get-Date).ToString('HH:mm:ss.fff') + ' ' + $line))
  if ($script:devBuf.Count -ge 40 -or ((Get-Date) - $script:devLastFlush).TotalSeconds -ge 3) { Flush-Dev }
}

function Flush-Dev {
  if ($script:devPath -eq '' -or $script:devBuf.Count -eq 0) { return }
  try { Add-Content -Path $script:devPath -Value $script:devBuf -Encoding UTF8 } catch { }
  $script:devBuf.Clear()
  $script:devLastFlush = Get-Date
}

function Start-DevLog {
  if (-not [bool]$script:cfg.ayrintiliKayit) { $script:devPath = ''; return }
  $name = 'kayit_' + (Get-Date).ToString('yyyyMMdd_HHmmss') + '.txt'
  $script:devPath = Join-Path $root $name
  # Ham kareler: motor kiyasi icin
  $script:frameDir = Join-Path $root ('kareler_' + (Get-Date).ToString('yyyyMMdd_HHmmss'))
  if (-not (Test-Path $script:frameDir)) { [void](New-Item -ItemType Directory -Path $script:frameDir) }
  $script:frameSaved = 0
  $script:devBuf.Clear()
  $vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
  $a = $script:cfg.alan
  $head = @(
    '==================== CameraTranslator PC — ayrıntılı kayıt ====================',
    ('Tarih      : ' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')),
    ('Ekranlar   : ' + $vs.Width + 'x' + $vs.Height + ' (sanal), birincil ' +
      [System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Width + 'x' + [System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Height),
    ('OCR alanı  : ' + $a.W + 'x' + $a.H + ' konum ' + $a.X + ',' + $a.Y),
    ('Yazı tipi  : ' + $script:cfg.yaziTipi + '   yazı ' + $script:cfg.yaziRengi + '   kutu ' + $script:cfg.kutuRengi + '   saydamlık ' + $script:cfg.saydamlik),
    ('Okuma      : her ' + $READ_MS + ' ms, kutuyu tutma ' + $HOLD_MS + ' ms'),
    '',
    'Satır türleri: OKUMA (ham ve filtreli metin) · CEVIRI · ONBELLEK · CIZIM · UYARI · OZET',
    '------------------------------------------------------------------------------'
  )
  try { Set-Content -Path $script:devPath -Value $head -Encoding UTF8 } catch { }
  Write-Line ('Ayrıntılı kayıt: ' + $name)
}

function Stop-DevLog {
  if ($script:frameDir -ne '') {
    Write-Line ('Ham kare kaydedildi: ' + $script:frameSaved + ' adet')
    $script:frameDir = ''
  }
  if ($script:devPath -eq '') { return }
  Write-Dev '------------------------------------------------------------------------------'
  Flush-Dev
  $script:devPath = ''
}

# ---------------- Altyazi cizimi ----------------
# Punto disaridan verilir ve degismez: ingilizce yaziyla ayni boyda kalir.
# Metin sigmazsa yazi kucultulmez, kutu buyutulur (geometri cagiran tarafta hesaplanir).
function Draw-Subtitle($g, [string]$text, [int]$bx, [int]$by, [int]$bw, [int]$bh, [int]$fontSize) {
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
  $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

  $boxBrush = New-Object System.Drawing.SolidBrush (ConvertTo-Color $script:cfg.kutuRengi ([System.Drawing.Color]::Black))
  $g.FillRectangle($boxBrush, (New-Object System.Drawing.Rectangle $bx, $by, $bw, $bh))
  $boxBrush.Dispose()

  $fmt = New-Object System.Drawing.StringFormat
  $fmt.Alignment = [System.Drawing.StringAlignment]::Center
  $fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
  $name = [string]$script:cfg.yaziTipi
  $font = New-Object System.Drawing.Font($name, $fontSize, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
  $script:lastFontSize = $fontSize
  $script:lastFit = (($g.MeasureString($text, $font, $bw - 10)).Height -le $bh)

  # RectangleF sart: Rectangle verilince PowerShell DrawString'in PointF surumunu seciyor
  $layout = New-Object System.Drawing.RectangleF ([float]$bx), ([float]$by), ([float]$bw), ([float]$bh)
  $dark = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(225, 0, 0, 0))
  foreach ($dx in -1, 1) {
    foreach ($dy in -1, 1) {
      $shift = New-Object System.Drawing.RectangleF ([float]($bx + $dx)), ([float]($by + $dy)), ([float]$bw), ([float]$bh)
      $g.DrawString($text, $font, $dark, $shift, $fmt)
    }
  }
  $dark.Dispose()
  $textBrush = New-Object System.Drawing.SolidBrush (ConvertTo-Color $script:cfg.yaziRengi ([System.Drawing.Color]::White))
  $g.DrawString($text, $font, $textBrush, $layout, $fmt)
  $textBrush.Dispose(); $font.Dispose(); $fmt.Dispose()
}

# ---------------- OCR ----------------
[void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Graphics.Imaging.SoftwareBitmap, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Storage.FileAccessMode, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime]

$script:asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
  })[0]
function Wait-WinRT($operation, $resultType) {
  $task = $script:asTask.MakeGenericMethod($resultType).Invoke($null, @($operation))
  [void]$task.Wait(-1)
  return $task.Result
}
$script:ocr = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage((New-Object Windows.Globalization.Language 'en-US'))
$script:workDir = Join-Path $env:TEMP 'ocr_probe'
if (-not (Test-Path $script:workDir)) { [void](New-Item -ItemType Directory -Path $script:workDir) }
$script:framePath = Join-Path $script:workDir 'app.bmp'

function Read-ImageText([string]$path) {
  $file = Wait-WinRT ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
  $stream = Wait-WinRT ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
  try {
    $decoder = Wait-WinRT ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap = Wait-WinRT ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    return Wait-WinRT ($script:ocr.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
  } finally { $stream.Dispose() }
}

# Alan ekran disina tasarsa CopyFromScreen hata firlatiyor; once kirpiyoruz
function Get-SafeArea {
  $vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
  $a = $script:cfg.alan
  $x = [int]$a.X; $y = [int]$a.Y; $w = [int]$a.W; $h = [int]$a.H
  if ($x -lt $vs.Left) { $w -= ($vs.Left - $x); $x = $vs.Left }
  if ($y -lt $vs.Top) { $h -= ($vs.Top - $y); $y = $vs.Top }
  if ($x + $w -gt $vs.Right) { $w = $vs.Right - $x }
  if ($y + $h -gt $vs.Bottom) { $h = $vs.Bottom - $y }
  if ($w -lt 20 -or $h -lt 12) { return $null }
  return @{ X = $x; Y = $y; W = $w; H = $h }
}

# Altyazinin parlakligini tahmin et: kucuk bir orneklemden en parlak dilimin esigi.
# Oyun sahnesi acik ya da koyu olsun, yazi her zaman en parlak piksellerdedir.
function Get-TextThreshold($bitmap) {
  $sw = 256; $sh = 32
  $small = New-Object System.Drawing.Bitmap $sw, $sh
  $g = [System.Drawing.Graphics]::FromImage($small)
  # En yakin komsu sart: ortalama alan kuculmede ince yazinin parlakligi siliniyor
  # (olcumde tepe deger 255 yerine 179 cikiyordu, esik da buna gore yanlis seciliyordu)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
  $g.DrawImage($bitmap, 0, 0, $sw, $sh)
  $g.Dispose()
  $rect = New-Object System.Drawing.Rectangle 0, 0, $sw, $sh
  $data = $small.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $len = $data.Stride * $sh
  $buf = New-Object byte[] $len
  [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $buf, 0, $len)
  $small.UnlockBits($data); $small.Dispose()

  $hist = New-Object int[] 256
  $count = 0
  $sum = 0
  for ($i = 0; $i -lt $len; $i += 4) {
    $lum = [int](0.114 * $buf[$i] + 0.587 * $buf[$i + 1] + 0.299 * $buf[$i + 2])
    $hist[$lum]++
    $sum += $lum
    $count++
  }
  if ($count -eq 0) { return 190 }
  $mean = $sum / $count
  # Yazi en parlak %0.4'te; tek sicak pikselden etkilenmemek icin yuzdelik kullaniliyor
  $target = [math]::Max(1, [int]($count * 0.004))
  $seen = 0
  $peak = 255
  for ($v = 255; $v -ge 0; $v--) {
    $seen += $hist[$v]
    if ($seen -ge $target) { $peak = $v; break }
  }
  $t = [int]($mean + 0.55 * ($peak - $mean))
  if ($t -lt 140) { $t = 140 }
  if ($t -gt 240) { $t = 240 }
  return $t
}

# Ayrintili kayit acikken ham kareler de saklanir: OCR motorlarini GERCEK
# goruntuyle kiyaslayabilmek icin. JPEG, kalite 60, en fazla $FRAME_LIMIT kare.
$FRAME_LIMIT = 500
$script:frameDir = ''
$script:frameSaved = 0

function Save-Frame($bitmap) {
  if ($script:frameDir -eq '' -or $script:frameSaved -ge $FRAME_LIMIT) { return }
  try {
    $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
    $pars = New-Object System.Drawing.Imaging.EncoderParameters 1
    $pars.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), ([long]60)
    $name = 'kare_' + (Get-Date).ToString('HHmmss_fff') + '.jpg'
    $bitmap.Save((Join-Path $script:frameDir $name), $codec, $pars)
    $script:frameSaved++
  } catch { }
}

function Grab-Region([int]$rx, [int]$ry, [int]$rw, [int]$rh, [string]$out) {
  # PP-OCR ham goruntuyle calisiyor: esikleme ve buyutme gereksiz, kendi olcegini seciyor
  $raw = New-Object System.Drawing.Bitmap $rw, $rh
  $g = [System.Drawing.Graphics]::FromImage($raw)
  $g.CopyFromScreen($rx, $ry, 0, 0, (New-Object System.Drawing.Size $rw, $rh))
  $g.Dispose()
  Save-Frame $raw
  $raw.Save($out, [System.Drawing.Imaging.ImageFormat]::Bmp)
  $raw.Dispose()
}

function Clear-Junk([string]$text) {
  $t = $text -replace '^[^A-Za-z"'']{0,12}', ''
  $t = $t -replace '\s{2,}', ' '
  return $t.Trim()
}
function Get-Key([string]$t) {
  $k = $t.ToLowerInvariant() -replace '[^a-z0-9 ]', ''
  return ($k -replace '\s+', ' ').Trim()
}
# Iki okuma ayni altyazi mi? Icerme testi yalnizca bastan/sondan kirpilmayi yakaliyordu;
# "military grade" / "military grad" gibi ORTADAKI farki kaciriyor ve ayni altyaziyi
# yeni sanip tekrar tekrar ceviriyorduk. Artik gercek benzerlik olculuyor.
function Get-Distance([string]$a, [string]$b) {
  $n = $a.Length; $m = $b.Length
  if ($n -eq 0) { return $m }
  if ($m -eq 0) { return $n }
  $prev = New-Object int[] ($m + 1)
  $curr = New-Object int[] ($m + 1)
  for ($j = 0; $j -le $m; $j++) { $prev[$j] = $j }
  for ($i = 1; $i -le $n; $i++) {
    $curr[0] = $i
    $ca = $a[$i - 1]
    for ($j = 1; $j -le $m; $j++) {
      $cost = if ($ca -eq $b[$j - 1]) { 0 } else { 1 }
      $d = $curr[$j - 1] + 1
      $e = $prev[$j] + 1
      $f = $prev[$j - 1] + $cost
      $min = if ($d -lt $e) { $d } else { $e }
      if ($f -lt $min) { $min = $f }
      $curr[$j] = $min
    }
    $tmp = $prev; $prev = $curr; $curr = $tmp
  }
  return $prev[$m]
}

$script:simA = ''
$script:simB = ''
$script:simR = $false

function Test-SameEnough([string]$a, [string]$b) {
  if ($a -eq $b) { return $true }
  if ($a.Length -eq 0 -or $b.Length -eq 0) { return $false }
  # Ayni cift kareden kareye tekrar geliyor; bir kez hesapla
  if ($a -eq $script:simA -and $b -eq $script:simB) { return $script:simR }
  # OCR ayni cumlede kelimeleri birlestirip ayirabiliyor ("be outside" / "beoutside");
  # bosluklari atinca bu gurultu yok oluyor
  $ca = $a -replace ' ', ''
  $cb = $b -replace ' ', ''
  if ($ca.Length -ge $cb.Length) { $long = $ca; $short = $cb } else { $long = $cb; $short = $ca }
  if ($long.Contains($short) -and $short.Length -ge $long.Length * 0.8) {
    $result = $true
  } elseif ($short.Length -lt $long.Length * 0.7) {
    # Uzunluklar cok farkliysa bosuna hesaplama
    $result = $false
  } else {
    # Esik mobildeki OcrStabilizer ile ayni (0.75)
    $result = ((1.0 - (Get-Distance $ca $cb) / [double]$long.Length) -ge 0.75)
  }
  $script:simA = $a; $script:simB = $b; $script:simR = $result
  return $result
}
# ---- Okuma penceresi (mobildeki OcrStabilizer'in karsiligi) ----
$WINDOW_MS = 1500      # bu sure icindeki okumalar ayni altyazi sayilabilir
$WINDOW_SIZE = 8
$REQUIRED_HITS = 2     # onay icin gereken benzer okuma
$VOTE_HITS = 3         # bu kadar birikirse beklemeden onayla
$MAX_WAIT_MS = 500     # 2 okuma varsa en fazla bu kadar bekle

# Okuma kalitesi: harf cok olsun, kelime icinde sembol/rakam ve rastgele buyuk harf olmasin
function Get-Quality([string]$t) {
  $letters = ([regex]::Matches($t, '[A-Za-z]')).Count
  $mixed = ([regex]::Matches($t, "[A-Za-z][^A-Za-z\s'\-.,!?]|[^A-Za-z\s'\-.,!?][A-Za-z]")).Count
  $midCaps = ([regex]::Matches($t, '(?<=[a-z])[A-Z]')).Count
  $punct = ([regex]::Matches($t, '[^A-Za-z0-9\s]')).Count
  return $letters - 6 * $mixed - 4 * $midCaps - $punct
}

# Mobildeki kelime oylamasinin sade hali: bir okumanin kelimeleri gruptaki
# diger okumalarda da geciyorsa o okuma daha guvenilirdir. Esitlikte kalite karar verir.
function Select-Best($group) {
  if ($group.Count -eq 1) { return $group[0] }
  $wordSets = @()
  foreach ($g in $group) {
    $set = @{}
    foreach ($w in ($g.Key -split ' ')) { if ($w.Length -ge 2) { $set[$w] = $true } }
    $wordSets += , $set
  }
  $best = $group[0]; $bestVotes = -1; $bestQuality = 0
  for ($i = 0; $i -lt $group.Count; $i++) {
    $votes = 0
    foreach ($w in $wordSets[$i].Keys) {
      for ($j = 0; $j -lt $group.Count; $j++) {
        if ($j -ne $i -and $wordSets[$j].ContainsKey($w)) { $votes++ }
      }
    }
    $quality = Get-Quality $group[$i].Text
    if ($votes -gt $bestVotes -or ($votes -eq $bestVotes -and $quality -gt $bestQuality)) {
      $best = $group[$i]; $bestVotes = $votes; $bestQuality = $quality
    }
  }
  return $best
}

# Grup onaylanmaya hazir mi? Hazirsa gosterilecek okumayi doner.
function Approve-Group($group, $now) {
  if ($group.Count -lt $REQUIRED_HITS) { return $null }
  if ($group.Count -ge $VOTE_HITS) { return (Select-Best $group) }
  # Iki okuma birebir ayniysa beklemeye gerek yok
  $keys = @($group | ForEach-Object { $_.Key })
  if (@($keys | Select-Object -Unique).Count -lt $keys.Count) { return (Select-Best $group) }
  if (($now - $group[0].At).TotalMilliseconds -ge $MAX_WAIT_MS) { return (Select-Best $group) }
  return $null
}

# Mobildeki SubtitleRegionFilter'in karsiligi: alanin kenarlarindaki HUD yazilarini atar.
# Bir satirdaki kelimeler buyuk bosluklara gore gruplanir; yalnizca ortayi kapsayan ya da
# orta noktasi alanin ortasina yakin olan gruplar tutulur.
$CENTER_TOLERANCE = 0.22
$GAP_FACTOR = 2.0

function Get-Subtitle($ocrResult, [int]$rx, [int]$ry, [int]$imageWidth) {
  $scale = $script:captureScale
  $center = $imageWidth / 2.0
  $tolerance = $imageWidth * $CENTER_TOLERANCE
  $texts = @()
  $dropped = @()
  $minX = [double]::MaxValue; $minY = [double]::MaxValue
  $maxX = [double]::MinValue; $maxY = [double]::MinValue
  $lineCount = 0

  foreach ($line in $ocrResult.Lines) {
    $words = @()
    foreach ($w in $line.Words) {
      $r = $w.BoundingRect
      $words += [PSCustomObject]@{
        Text = [string]$w.Text
        Left = [double]$r.X; Right = [double]($r.X + $r.Width)
        Top = [double]$r.Y; Bottom = [double]($r.Y + $r.Height)
        Height = [double]$r.Height
      }
    }
    if (@($words).Count -eq 0) { continue }
    $sorted = @($words | Sort-Object Left)
    $heights = @($sorted | ForEach-Object { $_.Height } | Sort-Object)
    $lineHeight = [math]::Max(1.0, $heights[[int]([math]::Floor(@($heights).Count / 2))])
    $maxGap = $lineHeight * $GAP_FACTOR

    # Bosluklara gore grupla
    $groups = New-Object System.Collections.ArrayList
    $current = New-Object System.Collections.ArrayList
    [void]$current.Add($sorted[0])
    for ($i = 1; $i -lt @($sorted).Count; $i++) {
      $prev = $current[$current.Count - 1]
      if (($sorted[$i].Left - $prev.Right) -gt $maxGap) {
        [void]$groups.Add($current)
        $current = New-Object System.Collections.ArrayList
      }
      [void]$current.Add($sorted[$i])
    }
    [void]$groups.Add($current)

    $kept = @()
    foreach ($grp in $groups) {
      $left = $grp[0].Left
      $right = ($grp | Measure-Object -Property Right -Maximum).Maximum
      $coversCenter = ($left -le $center -and $right -ge $center)
      $midNear = ([math]::Abs((($left + $right) / 2.0) - $center) -le $tolerance)
      if ($coversCenter -or $midNear) { $kept += , $grp }
      else { $dropped += (($grp | ForEach-Object { $_.Text }) -join ' ') }
    }
    if (@($kept).Count -eq 0) { continue }

    $lineText = @()
    foreach ($grp in $kept) {
      foreach ($w in $grp) {
        $lineText += $w.Text
        if ($w.Left -lt $minX) { $minX = $w.Left }
        if ($w.Top -lt $minY) { $minY = $w.Top }
        if ($w.Right -gt $maxX) { $maxX = $w.Right }
        if ($w.Bottom -gt $maxY) { $maxY = $w.Bottom }
      }
    }
    $texts += ($lineText -join ' ')
    $lineCount++
  }

  if ($lineCount -eq 0) { return $null }
  return @{
    Text  = ($texts -join ' ')
    Lines = $lineCount
    Dropped = $dropped
    Rect  = New-Object System.Drawing.Rectangle `
      ([int]($rx + $minX / $scale)), ([int]($ry + $minY / $scale)), `
      ([int](($maxX - $minX) / $scale)), ([int](($maxY - $minY) / $scale))
  }
}

# ---------------- Durum ----------------
$script:running = $false
$script:proc = $null
$script:writer = $null
$script:overlay = $null
$script:gameWindow = [IntPtr]::Zero
$script:lockAt = $null
$script:boxRect = $null
$script:boxText = ''
$script:boxLines = 1
$script:prevKey = ''
$script:shownKey = ''
$script:firstSeen = $null
$script:blankFrames = 0
$script:cache = @{}
$script:latencies = New-Object System.Collections.ArrayList
$script:paused = $false
$script:prevF8 = $false
$script:errorStreak = 0
# Ayrintili kayit icin sayaclar
$script:frameCount = 0
$script:cacheHits = 0
$script:warnCount = 0
$script:capMs = 0
$script:ocrMs = 0
$script:lastRaw = ''
$script:lastEn = ''
$script:lastTr = ''
$script:warnedPending = $false
$script:lastSummary = Get-Date
$script:lastFontSize = 0
$script:lastFit = $true
# OCR'in olctugu yazi yuksekligi karede zipliyor (alt/ust uzantili harf varsa buyuyor).
# Punto bu zipli degeri degil, son okumalarin medyanini kullanir; yoksa yazi titriyor.
$script:captureScale = 1
$script:lineHeights = New-Object System.Collections.ArrayList
$script:stableLineH = 0
# Son okumalar (pencere): altyazi bir karede bozuk okunsa bile ikinci bir benzer
# okuma geldiginde onaylanabilsin diye tutuluyor
$script:readings = New-Object System.Collections.ArrayList
# Altyazi kaybolurken onaylanan aday (bir sonraki karede cevrilir)
$script:lateCandidate = $null

function New-OverlayWindow($a) {
  $key = [System.Drawing.Color]::Magenta
  $f = New-Object System.Windows.Forms.Form
  $f.FormBorderStyle = 'None'
  $f.StartPosition = 'Manual'
  $f.Left = [int]$a.X; $f.Top = [int]$a.Y; $f.Width = [int]$a.W; $f.Height = [int]$a.H
  $f.TopMost = $true
  $f.ShowInTaskbar = $false
  $f.BackColor = $key
  $f.TransparencyKey = $key
  if ([double]$script:cfg.saydamlik -lt 1.0) { $f.Opacity = [double]$script:cfg.saydamlik }
  $f.Add_Paint({
      param($sender, $e)
      try {
        $rect = $script:boxRect
        $text = $script:boxText
        if ($null -eq $rect -or $text -eq '') { return }
        $lines = [math]::Max(1, $script:boxLines)
        $lineH = [math]::Max(8, [int]($rect.Height / $lines))
        # Pay satir boyuna oranli: ingilizcenin kenar yumusatmasi kutunun disinda kalmasin
        $padX = [int][math]::Max(18, $lineH * 0.45)
        $padY = [int][math]::Max(4, $lineH * 0.22)
        $maxW = $sender.ClientSize.Width
        $maxH = $sender.ClientSize.Height

        # Punto SABIT: oturmus satir yuksekligine gore, karedeki zipli olcume gore degil.
        $stable = if ($script:stableLineH -gt 0) { $script:stableLineH } else { $lineH }
        $size = [int][math]::Min(64, [math]::Max(14, [math]::Round($stable * 0.95)))
        $font = New-Object System.Drawing.Font([string]$script:cfg.yaziTipi, $size, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
        $single = [double]$e.Graphics.MeasureString($text, $font).Width
        $lineBox = [double]$e.Graphics.MeasureString('Ag', $font).Height
        $font.Dispose()

        $avail = [math]::Max(40, $maxW - $padX * 2)
        if ($single -le $avail) {
          $needLines = 1
          $textW = $single
        } else {
          $needLines = [int][math]::Ceiling($single / $avail)
          $textW = $avail
        }

        $clamp = @()
        $want = [int]($textW) + $padX * 2
        $bw = [math]::Max($rect.Width + $padX * 2, $want)
        if ($bw -gt $maxW) { $bw = $maxW; $clamp += 'genişlik' }
        $bh = [math]::Max($rect.Height + $padY * 2, [int]($needLines * $lineBox) + $padY * 2)
        if ($bh -gt $maxH) { $bh = $maxH; $clamp += 'yükseklik' }

        # Ingilizcenin tam merkezine otur: kutu buyurken her yone esit acilir, ustunu orter
        $centerX = $rect.X + [int]($rect.Width / 2) - $sender.Left
        $centerY = $rect.Y + [int]($rect.Height / 2) - $sender.Top
        $bx = $centerX - [int]($bw / 2)
        $by = $centerY - [int]($bh / 2)
        if ($bx -lt 0) { $bx = 0; $clamp += 'sol kenar' }
        if ($bx + $bw -gt $maxW) { $bx = $maxW - $bw; $clamp += 'sağ kenar' }
        if ($by -lt 0) { $by = 0; $clamp += 'üst kenar' }
        if ($by + $bh -gt $maxH) { $by = $maxH - $bh; $clamp += 'alt kenar' }
        if ($bw -lt 10 -or $bh -lt 10) { return }

        Draw-Subtitle $e.Graphics $text $bx $by $bw $bh $size

        if ([bool]$script:cfg.ayrintiliKayit) {
          Write-Dev ('CIZIM  | ing=' + $rect.Width + 'x' + $rect.Height + '@' + $rect.X + ',' + $rect.Y +
            ' | kutu=' + $bw + 'x' + $bh + '@' + ($bx + $sender.Left) + ',' + ($by + $sender.Top) +
            ' | punto=' + $script:lastFontSize + ' | satır=' + $lines)
          if (-not $script:lastFit) {
            Write-Dev ('UYARI  | yazı kutuya sığmadı, punto tabana vurdu (11) | TR=' + $text)
          }
          if (@($clamp).Count -gt 0) {
            Write-Dev ('UYARI  | kutu alan sınırına dayandı: ' + ($clamp -join ', ') +
              ' | istenen genişlik=' + $want + ' verilen=' + $bw + ' | İngilizcenin bir kısmı görünebilir')
          }
        }
      } catch {
        Write-Line ('Çizim hatası: ' + $_.Exception.Message)
      }
    })
  $f.Show()
  [System.Windows.Forms.Application]::DoEvents()
  $h = $f.Handle
  $ex = [Native]::GetWindowLong($h, -20)
  [void][Native]::SetWindowLong($h, -20, $ex -bor 0x20 -bor 0x80000 -bor 0x80 -bor 0x8000000)
  # Kendi yakalamamizda gorunmesin, yoksa bastigimiz Turkceyi OCR okur
  [void][Native]::SetWindowDisplayAffinity($h, 0x11)
  return $f
}

# ---------------- Alan secici ----------------
function Select-Area {
  $vs = [System.Windows.Forms.SystemInformation]::VirtualScreen
  $script:selStart = $null
  $script:selRect = New-Object System.Drawing.Rectangle 0, 0, 0, 0
  $script:selDragging = $false
  $script:selOk = $false

  $picker = New-Object System.Windows.Forms.Form
  $picker.FormBorderStyle = 'None'
  $picker.StartPosition = 'Manual'
  $picker.Bounds = $vs
  $picker.TopMost = $true
  $picker.ShowInTaskbar = $false
  $picker.BackColor = [System.Drawing.Color]::Black
  $picker.Opacity = 0.42
  $picker.Cursor = [System.Windows.Forms.Cursors]::Cross
  $picker.KeyPreview = $true
  if ($null -ne $script:cfg.alan) {
    $script:selRect = New-Object System.Drawing.Rectangle `
      ([int]$script:cfg.alan.X - $vs.X), ([int]$script:cfg.alan.Y - $vs.Y), `
      ([int]$script:cfg.alan.W), ([int]$script:cfg.alan.H)
  }
  $picker.Add_Paint({
      param($sender, $e)
      try {
        $g = $e.Graphics
        $f1 = New-Object System.Drawing.Font('Segoe UI', 26, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
        $f2 = New-Object System.Drawing.Font('Segoe UI', 19, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
        $wb = [System.Drawing.Brushes]::White
        $g.DrawString('Altyazının çıktığı yerin etrafına dikdörtgen çiz', $f1, $wb, 40, 36)
        $g.DrawString('İçinden tutup taşı · köşelerden boyutlandır · boşluğa yeni çiz', $f2, $wb, 40, 74)
        $g.DrawString('Enter ile kaydet · Esc ile vazgeç', $f2, $wb, 40, 104)
        $r = $script:selRect
        if ($r.Width -gt 2 -and $r.Height -gt 2) {
          $accent = [System.Drawing.Color]::FromArgb(255, 255, 206, 122)
          $pen = New-Object System.Drawing.Pen $accent, 3
          $g.DrawRectangle($pen, $r); $pen.Dispose()
          # Kose tutamaklari
          $hb = New-Object System.Drawing.SolidBrush $accent
          $k = 7
          foreach ($c in @(
              @{ x = $r.Left; y = $r.Top }, @{ x = $r.Right; y = $r.Top },
              @{ x = $r.Left; y = $r.Bottom }, @{ x = $r.Right; y = $r.Bottom })) {
            $g.FillRectangle($hb, ($c.x - $k), ($c.y - $k), ($k * 2), ($k * 2))
          }
          $hb.Dispose()
          $ly = if ($r.Y -gt 40) { $r.Y - 32 } else { $r.Y + $r.Height + 8 }
          $g.DrawString(('{0} × {1}' -f $r.Width, $r.Height), $f2, $wb, [float]$r.X, [float]$ly)
        }
        $f1.Dispose(); $f2.Dispose()
      } catch { }
    })
  # Kose tutamagindan boyutlandir, icinden tut tasi, disina ciz
  $script:selMode = 'new'
  $script:selAnchor = New-Object System.Drawing.Point 0, 0
  $script:selOrig = $script:selRect
  $HANDLE = 18

  $picker.Add_MouseDown({
      param($s, $e)
      $r = $script:selRect
      $mode = 'new'
      if ($r.Width -gt 4 -and $r.Height -gt 4) {
        $corners = @(
          @{ n = 'nw'; x = $r.Left; y = $r.Top },
          @{ n = 'ne'; x = $r.Right; y = $r.Top },
          @{ n = 'sw'; x = $r.Left; y = $r.Bottom },
          @{ n = 'se'; x = $r.Right; y = $r.Bottom }
        )
        foreach ($c in $corners) {
          if ([math]::Abs($e.X - $c.x) -le $HANDLE -and [math]::Abs($e.Y - $c.y) -le $HANDLE) { $mode = $c.n; break }
        }
        if ($mode -eq 'new' -and $r.Contains((New-Object System.Drawing.Point $e.X, $e.Y))) { $mode = 'move' }
      }
      $script:selMode = $mode
      $script:selAnchor = New-Object System.Drawing.Point $e.X, $e.Y
      $script:selOrig = $r
      $script:selDragging = $true
    })

  $picker.Add_MouseMove({
      param($s, $e)
      if (-not $script:selDragging) { return }
      $a = $script:selAnchor
      $o = $script:selOrig
      $dx = $e.X - $a.X
      $dy = $e.Y - $a.Y
      switch ($script:selMode) {
        'move' { $x = $o.X + $dx; $y = $o.Y + $dy; $w = $o.Width; $h = $o.Height }
        'nw' { $x = $o.X + $dx; $y = $o.Y + $dy; $w = $o.Width - $dx; $h = $o.Height - $dy }
        'ne' { $x = $o.X; $y = $o.Y + $dy; $w = $o.Width + $dx; $h = $o.Height - $dy }
        'sw' { $x = $o.X + $dx; $y = $o.Y; $w = $o.Width - $dx; $h = $o.Height + $dy }
        'se' { $x = $o.X; $y = $o.Y; $w = $o.Width + $dx; $h = $o.Height + $dy }
        default {
          $x = [math]::Min($a.X, $e.X); $y = [math]::Min($a.Y, $e.Y)
          $w = [math]::Abs($dx); $h = [math]::Abs($dy)
        }
      }
      if ($w -lt 0) { $x += $w; $w = -$w }
      if ($h -lt 0) { $y += $h; $h = -$h }
      $script:selRect = New-Object System.Drawing.Rectangle ([int]$x), ([int]$y), ([int]$w), ([int]$h)
      $s.Invalidate()
    })
  $picker.Add_MouseUp({ param($s, $e) $script:selDragging = $false; $s.Invalidate() })
  $picker.Add_KeyDown({
      param($s, $e)
      if ($e.KeyCode -eq [System.Windows.Forms.Keys]::Escape) { $script:selOk = $false; $s.Close() }
      elseif ($e.KeyCode -eq [System.Windows.Forms.Keys]::Enter) { $script:selOk = $true; $s.Close() }
    })
  # Odak alinmazsa Enter'a basmak ise yaramiyordu (fare ile cizince odak geliyordu)
  $picker.Add_Shown({
      param($s, $e)
      $s.Activate()
      [void][Native]::SetForegroundWindow($s.Handle)
      $s.Focus()
    })
  [void]$picker.ShowDialog()
  $picker.Dispose()

  if (-not $script:selOk) { return $false }
  $r = $script:selRect
  if ($r.Width -lt 40 -or $r.Height -lt 20) { return $false }
  $script:cfg.alan = [PSCustomObject]@{ X = ($r.X + $vs.X); Y = ($r.Y + $vs.Y); W = $r.Width; H = $r.Height }
  Save-Settings
  Write-Line ('OCR alanı: {0}×{1} konum {2},{3}' -f $r.Width, $r.Height, ($r.X + $vs.X), ($r.Y + $vs.Y))
  return $true
}

# ---------------- Arayuz ----------------
function Set-Rounded($ctrl, [int]$radius) {
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $w = $ctrl.Width; $h = $ctrl.Height; $d = $radius * 2
  $p.AddArc(0, 0, $d, $d, 180, 90)
  $p.AddArc($w - $d, 0, $d, $d, 270, 90)
  $p.AddArc($w - $d, $h - $d, $d, $d, 0, 90)
  $p.AddArc(0, $h - $d, $d, $d, 90, 90)
  $p.CloseFigure()
  $ctrl.Region = New-Object System.Drawing.Region($p)
  $p.Dispose()
}
function New-Card([int]$x, [int]$y, [int]$w, [int]$h) {
  $pn = New-Object System.Windows.Forms.Panel
  $pn.SetBounds($x, $y, $w, $h)
  $pn.BackColor = $C.Card
  Set-Rounded $pn 14
  return $pn
}
function New-Caption([string]$text, [int]$x, [int]$y, [int]$w = 150) {
  $l = New-Object System.Windows.Forms.Label
  $l.Text = $text; $l.SetBounds($x, $y, $w, 20)
  $l.ForeColor = $C.Muted
  $l.Font = New-Object System.Drawing.Font('Segoe UI', 9)
  $l.BackColor = [System.Drawing.Color]::Transparent
  return $l
}

$form = New-Object System.Windows.Forms.Form
$form.Text = 'CameraTranslator PC'
$form.FormBorderStyle = 'None'
$form.Size = New-Object System.Drawing.Size 520, 656
$form.StartPosition = 'CenterScreen'
$form.BackColor = $C.Bg
$form.ForeColor = $C.Text
$form.Font = New-Object System.Drawing.Font('Segoe UI', 9.75)
$icoPath = Join-Path $root 'app.ico'
if (Test-Path $icoPath) { try { $form.Icon = New-Object System.Drawing.Icon($icoPath) } catch { } }

$bar = New-Object System.Windows.Forms.Panel
$bar.SetBounds(0, 0, 520, 52)
$bar.BackColor = $C.Bg
$dot = New-Object System.Windows.Forms.Panel
$dot.SetBounds(22, 20, 12, 12); $dot.BackColor = $C.Accent; Set-Rounded $dot 6
$lblTitle = New-Object System.Windows.Forms.Label
$lblTitle.Text = 'CameraTranslator'
$lblTitle.SetBounds(44, 16, 260, 24)
$lblTitle.Font = New-Object System.Drawing.Font('Segoe UI Semibold', 11.5)
$lblTitle.ForeColor = $C.Text
$lblTitle.BackColor = [System.Drawing.Color]::Transparent
$btnClose = New-Object System.Windows.Forms.Button
$btnClose.Text = '✕'; $btnClose.SetBounds(470, 12, 32, 28)
$btnClose.FlatStyle = 'Flat'; $btnClose.FlatAppearance.BorderSize = 0
$btnClose.BackColor = $C.Bg; $btnClose.ForeColor = $C.Muted
$btnClose.Font = New-Object System.Drawing.Font('Segoe UI', 11); $btnClose.TabStop = $false
$btnMin = New-Object System.Windows.Forms.Button
$btnMin.Text = '—'; $btnMin.SetBounds(434, 12, 32, 28)
$btnMin.FlatStyle = 'Flat'; $btnMin.FlatAppearance.BorderSize = 0
$btnMin.BackColor = $C.Bg; $btnMin.ForeColor = $C.Muted
$btnMin.Font = New-Object System.Drawing.Font('Segoe UI', 9); $btnMin.TabStop = $false
$btnTheme = New-Object System.Windows.Forms.Button
$btnTheme.SetBounds(398, 12, 32, 28)
$btnTheme.FlatStyle = 'Flat'; $btnTheme.FlatAppearance.BorderSize = 0
$btnTheme.BackColor = $C.Bg; $btnTheme.ForeColor = $C.Muted
$btnTheme.Font = New-Object System.Drawing.Font('Segoe UI Symbol', 10)
$btnTheme.TabStop = $false
$ttTheme = New-Object System.Windows.Forms.ToolTip
$bar.Controls.AddRange(@($dot, $lblTitle, $btnTheme, $btnMin, $btnClose))
$drag = {
  param($s, $e)
  if ($e.Button -eq [System.Windows.Forms.MouseButtons]::Left) {
    [void][Native]::ReleaseCapture()
    [void][Native]::SendMessage($form.Handle, 0xA1, 2, 0)
  }
}
$bar.Add_MouseDown($drag); $lblTitle.Add_MouseDown($drag)

# Ortak anahtar cizimi: hangi kutuya bagliysa onun durumunu gosterir
$pillPaint = {
    param($s, $e)
    $g = $e.Graphics
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear($C.Card)
    $on = [bool]$s.Tag.Checked
    $h = $s.Height; $w = $s.Width
    $track = New-Object System.Drawing.SolidBrush $(if ($on) { $C.Accent2 } else { $C.Line })
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc(0, 0, $h, $h, 90, 180)
    $path.AddArc($w - $h - 1, 0, $h, $h, 270, 180)
    $path.CloseFigure()
    $g.FillPath($track, $path); $track.Dispose(); $path.Dispose()
    $knob = New-Object System.Drawing.SolidBrush $(if ($on) { $C.Card } else { $C.Muted })
    $kx = if ($on) { $w - $h + 2 } else { 2 }
    $g.FillEllipse($knob, $kx, 2, $h - 4, $h - 4); $knob.Dispose()
  }

# --- Gorunum ---
$cardLook = New-Card 20 62 480 196
$lblLookCap = New-Caption 'GÖRÜNÜM' 18 12 200
$lblLookCap.Font = New-Object System.Drawing.Font('Segoe UI Semibold', 8)

$fontField = New-Object System.Windows.Forms.Panel
$fontField.SetBounds(140, 38, 324, 30)
$fontField.BackColor = $C.Card2
$fontField.Cursor = [System.Windows.Forms.Cursors]::Hand
Set-Rounded $fontField 8
$fontField.Add_Paint({
    param($s, $e)
    $g = $e.Graphics
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $tb = New-Object System.Drawing.SolidBrush $C.Text
    $f = New-Object System.Drawing.Font([string]$script:cfg.yaziTipi, 13, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
    $g.DrawString([string]$script:cfg.yaziTipi, $f, $tb, (New-Object System.Drawing.RectangleF 12, 0, ($s.Width - 40), $s.Height), $fmt)
    $f.Dispose(); $fmt.Dispose(); $tb.Dispose()
    $pen = New-Object System.Drawing.Pen $C.Muted, 2
    $cx = $s.Width - 20; $cy = [int]($s.Height / 2) - 2
    $g.DrawLine($pen, $cx - 5, $cy - 1, $cx, $cy + 4)
    $g.DrawLine($pen, $cx + 5, $cy - 1, $cx, $cy + 4)
    $pen.Dispose()
  })

# Renk kutusu: kendi cizdigi yuvarlak kose. Region ile kirpmak kenari
# tirtikli birakiyordu, ayrica koyu renkte kartin zemini kosede siziyordu.
$script:colText = ConvertTo-Color $script:cfg.yaziRengi ([System.Drawing.Color]::White)
$script:colBox = ConvertTo-Color $script:cfg.kutuRengi ([System.Drawing.Color]::Black)
$swatchPaint = {
    param($s, $e)
    $g = $e.Graphics
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear($C.Card)
    $col = if ($s.Name -eq 'swText') { $script:colText } else { $script:colBox }
    $w = $s.Width - 1; $h = $s.Height - 1; $d = 12
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($w - $d, 0, $d, $d, 270, 90)
    $path.AddArc($w - $d, $h - $d, $d, $d, 0, 90)
    $path.AddArc(0, $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    $br = New-Object System.Drawing.SolidBrush $col
    $g.FillPath($br, $path); $br.Dispose()
    # Ince cerceve: beyaz kutu beyaz kartta, siyah kutu siyah kartta kaybolmasin
    $pen = New-Object System.Drawing.Pen $C.Line, 1
    $g.DrawPath($pen, $path); $pen.Dispose()
    $path.Dispose()
  }
$swText = New-Object System.Windows.Forms.Panel
$swText.Name = 'swText'
$swText.SetBounds(140, 82, 54, 26)
$swText.BackColor = [System.Drawing.Color]::Transparent
$swText.Cursor = [System.Windows.Forms.Cursors]::Hand
$swText.Add_Paint($swatchPaint)
$swBox = New-Object System.Windows.Forms.Panel
$swBox.Name = 'swBox'
$swBox.SetBounds(300, 82, 54, 26)
$swBox.BackColor = [System.Drawing.Color]::Transparent
$swBox.Cursor = [System.Windows.Forms.Cursors]::Hand
$swBox.Add_Paint($swatchPaint)
$lblTextCol = New-Caption 'Yazı rengi' 18 84 120
$lblBoxCol = New-Caption 'Kutu rengi' 226 84 80
$lblFontCap = New-Caption 'Yazı tipi' 18 44 120
$lblOpaCap = New-Caption 'Saydamlık' 18 132 120

$sld = New-Object System.Windows.Forms.Panel
$sld.SetBounds(140, 124, 260, 32); $sld.BackColor = $C.Card
$lblOpaVal = New-Object System.Windows.Forms.Label
$lblOpaVal.SetBounds(410, 132, 60, 20)
$lblOpaVal.ForeColor = $C.Accent
$lblOpaVal.Font = New-Object System.Drawing.Font('Segoe UI Semibold', 9.5)
$lblOpaVal.BackColor = [System.Drawing.Color]::Transparent
$script:opacity = [double]$script:cfg.saydamlik
$script:sliderDrag = $false
function Set-OpacityFromX([int]$x) {
  $w = $sld.Width - 16
  $v = ($x - 8) / [double]$w
  if ($v -lt 0) { $v = 0 }
  if ($v -gt 1) { $v = 1 }
  $script:opacity = [math]::Round(0.4 + $v * 0.6, 2)
  $script:cfg.saydamlik = $script:opacity
  $lblOpaVal.Text = ('%{0}' -f [int]($script:opacity * 100))
  $sld.Invalidate()
  if ($null -ne $script:overlay) {
    $script:overlay.Opacity = $(if ($script:opacity -lt 1.0) { $script:opacity } else { 1.0 })
  }
  Save-Settings
}
$sld.Add_Paint({
    param($s, $e)
    $g = $e.Graphics
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $y = [int]($s.Height / 2)
    $trackW = $s.Width - 16
    $track = New-Object System.Drawing.SolidBrush $C.Line
    $g.FillRectangle($track, 8, $y - 2, $trackW, 4); $track.Dispose()
    $t = ($script:opacity - 0.4) / 0.6
    $px = 8 + [int]($trackW * $t)
    $fill = New-Object System.Drawing.SolidBrush $C.Accent2
    $g.FillRectangle($fill, 8, $y - 2, [math]::Max(0, $px - 8), 4); $fill.Dispose()
    $knob = New-Object System.Drawing.SolidBrush $C.Accent
    $g.FillEllipse($knob, $px - 8, $y - 8, 16, 16); $knob.Dispose()
  })
$sld.Add_MouseDown({ param($s, $e) $script:sliderDrag = $true; Set-OpacityFromX $e.X })
$sld.Add_MouseMove({ param($s, $e) if ($script:sliderDrag) { Set-OpacityFromX $e.X } })
$sld.Add_MouseUp({ $script:sliderDrag = $false })
$cardLook.Controls.AddRange(@($lblLookCap, $lblFontCap, $fontField, $lblTextCol, $swText, $lblBoxCol, $swBox, $lblOpaCap, $sld, $lblOpaVal))

# --- Alan ---
$cardArea = New-Card 20 272 480 110
$btnArea = New-Object System.Windows.Forms.Button
$btnArea.Text = 'OCR alanını çiz'
$btnArea.SetBounds(16, 18, 190, 36)
$btnArea.FlatStyle = 'Flat'
$btnArea.FlatAppearance.BorderSize = 1
$btnArea.FlatAppearance.BorderColor = $C.Line
$btnArea.BackColor = $C.Card2; $btnArea.ForeColor = $C.Text
Set-Rounded $btnArea 10
$lblArea = New-Object System.Windows.Forms.Label
$lblArea.SetBounds(222, 27, 240, 20)
$lblArea.ForeColor = $C.Muted
$lblArea.BackColor = [System.Drawing.Color]::Transparent
# Gorunmez kutu: mantigi tasiyor, gorunen taraf yanindaki anahtar
$chkLlm = New-Object System.Windows.Forms.CheckBox
$chkLlm.Checked = [bool]$script:cfg.gelismisOcr
$lblLlmCap = New-Caption 'Gelişmiş OCR düzeltme (GPU modeli)' 60 68 260
$lblLlmCap.Cursor = [System.Windows.Forms.Cursors]::Hand
$swLlm = New-Object System.Windows.Forms.Panel
$swLlm.SetBounds(18, 66, 34, 18)
$swLlm.BackColor = [System.Drawing.Color]::Transparent
$swLlm.Cursor = [System.Windows.Forms.Cursors]::Hand
$swLlm.Add_Paint($pillPaint)
$swLlm.Tag = $chkLlm
$toggleLlm = { $chkLlm.Checked = -not $chkLlm.Checked; $swLlm.Invalidate() }
$swLlm.Add_Click($toggleLlm)
$lblLlmCap.Add_Click($toggleLlm)

$cardArea.Controls.AddRange(@($btnArea, $lblArea, $swLlm, $lblLlmCap))

$chkLlm.Add_CheckedChanged({
    $script:cfg.gelismisOcr = [bool]$chkLlm.Checked
    Save-Settings
    Write-Line $(if ($chkLlm.Checked) { 'Gelişmiş OCR düzeltme açık — sonraki başlatmada geçerli' } else { 'Gelişmiş OCR düzeltme kapalı' })
  })

# --- Baslat ---
$btnStart = New-Object System.Windows.Forms.Button
$btnStart.Text = 'BAŞLAT'
$btnStart.SetBounds(20, 398, 480, 54)
$btnStart.FlatStyle = 'Flat'; $btnStart.FlatAppearance.BorderSize = 0
$btnStart.BackColor = $C.Go; $btnStart.ForeColor = [System.Drawing.Color]::White
$btnStart.Font = New-Object System.Drawing.Font('Segoe UI Semibold', 13)
Set-Rounded $btnStart 14

$lblStatus = New-Object System.Windows.Forms.Label
$lblStatus.SetBounds(20, 458, 480, 22)
$lblStatus.ForeColor = $C.Muted
$lblStatus.Font = New-Object System.Drawing.Font('Segoe UI', 8.75)
$lblStatus.TextAlign = 'MiddleCenter'

# --- Kayit ---
$cardLog = New-Card 20 486 480 148
$lblLogCap = New-Caption 'KAYIT' 18 10 200
$lblLogCap.Font = New-Object System.Drawing.Font('Segoe UI Semibold', 8)
$txtLog = New-Object System.Windows.Forms.TextBox
$txtLog.SetBounds(14, 34, 452, 102)
$txtLog.Multiline = $true
$txtLog.ScrollBars = 'None'
$txtLog.ReadOnly = $true
$txtLog.BorderStyle = 'None'
$txtLog.BackColor = $C.Card2
$txtLog.ForeColor = $C.Muted
$txtLog.Font = New-Object System.Drawing.Font('Consolas', 8.5)
$chkDev = New-Object System.Windows.Forms.CheckBox
$chkDev.Text = 'Ayrıntılı kayıt'
$chkDev.SetBounds(236, 6, 130, 22)
$chkDev.FlatStyle = 'Flat'
$chkDev.ForeColor = $C.Muted
$chkDev.BackColor = [System.Drawing.Color]::Transparent
$chkDev.Checked = [bool]$script:cfg.ayrintiliKayit

$btnOpenLog = New-Object System.Windows.Forms.Button
$btnOpenLog.Text = 'Klasör'
$btnOpenLog.SetBounds(378, 5, 88, 24)
$btnOpenLog.FlatStyle = 'Flat'
$btnOpenLog.FlatAppearance.BorderSize = 1
$btnOpenLog.FlatAppearance.BorderColor = $C.Line
$btnOpenLog.BackColor = $C.Card2
$btnOpenLog.ForeColor = $C.Text
$btnOpenLog.Font = New-Object System.Drawing.Font('Segoe UI', 8.5)
Set-Rounded $btnOpenLog 8

# $chkDev hicbir karta eklenmiyor: gorunmez kaliyor ama Checked'i degistirince
# CheckedChanged yine calisiyor. Gorunen taraf asagidaki sessiz anahtar.
$lblDevCap = New-Caption 'ayrıntılı' 246 11 60
$lblDevCap.Font = New-Object System.Drawing.Font('Segoe UI', 8)
$swDev = New-Object System.Windows.Forms.Panel
$swDev.SetBounds(310, 9, 34, 18)
$swDev.BackColor = [System.Drawing.Color]::Transparent
$swDev.Cursor = [System.Windows.Forms.Cursors]::Hand
$swDev.Tag = $chkDev
$swDev.Add_Paint($pillPaint)
$swDev.Add_Click({ $chkDev.Checked = -not $chkDev.Checked; $swDev.Invalidate() })
$lblDevCap.Cursor = [System.Windows.Forms.Cursors]::Hand
$lblDevCap.Add_Click({ $chkDev.Checked = -not $chkDev.Checked; $swDev.Invalidate() })
$cardLog.Controls.AddRange(@($lblLogCap, $lblDevCap, $swDev, $btnOpenLog, $txtLog))
$script:logBox = $txtLog

$chkDev.Add_CheckedChanged({
    $script:cfg.ayrintiliKayit = [bool]$chkDev.Checked
    Save-Settings
    if ($script:running) {
      # Calisirken acilip kapanabilsin
      if ($script:cfg.ayrintiliKayit) { Start-DevLog } else { Stop-DevLog }
    }
    Write-Line $(if ($script:cfg.ayrintiliKayit) { 'Ayrıntılı kayıt açık' } else { 'Ayrıntılı kayıt kapalı' })
  })
$btnOpenLog.Add_Click({
    try {
      $newest = Get-ChildItem -Path $root -Filter 'kayit_*.txt' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
      if ($null -ne $newest) { Start-Process -FilePath 'explorer.exe' -ArgumentList ('/select,"' + $newest.FullName + '"') }
      else { Start-Process -FilePath 'explorer.exe' -ArgumentList ('"' + $root + '"') }
    } catch { Write-Line ('Klasör açılamadı: ' + $_.Exception.Message) }
  })

$form.Controls.AddRange(@($bar, $cardLook, $cardArea, $btnStart, $lblStatus, $cardLog))

# ---------------- Tema ----------------
# Renkler kontrollere tek tek yazildigi icin tema degisiminde hepsini gezmek
# gerekiyor. Her kontrolun mevcut rengini eski paletten yeni palete esliyoruz;
# Transparent ve kullanicinin sectigi altyazi renkleri hicbir anahtara
# uymadigi icin dokunulmadan kaliyor.
function Update-ControlColors($ctrl, $map) {
  $bg = $ctrl.BackColor.ToArgb()
  if ($map.ContainsKey($bg)) { $ctrl.BackColor = $map[$bg] }
  $fg = $ctrl.ForeColor.ToArgb()
  if ($map.ContainsKey($fg)) { $ctrl.ForeColor = $map[$fg] }
  if ($ctrl -is [System.Windows.Forms.Button]) {
    $bc = $ctrl.FlatAppearance.BorderColor.ToArgb()
    if ($map.ContainsKey($bc)) { $ctrl.FlatAppearance.BorderColor = $map[$bc] }
  }
  foreach ($child in $ctrl.Controls) { Update-ControlColors $child $map }
  $ctrl.Invalidate()
}

function Set-Theme([string]$name) {
  if (-not $PALETTES.ContainsKey($name)) { $name = 'gece' }
  $map = @{}
  foreach ($k in $PALETTES[$name].Keys) { $map[$C[$k].ToArgb()] = $PALETTES[$name][$k] }
  foreach ($k in $PALETTES[$name].Keys) { $C[$k] = $PALETTES[$name][$k] }
  $form.SuspendLayout()
  Update-ControlColors $form $map
  $form.ResumeLayout()
  # Gunduzde ay, gecede gunes degil: dugme icinde bulundugun modu gosteriyor
  $btnTheme.Text = $(if ($name -eq 'gece') { [char]0x263D } else { [char]0x2600 })
  $ttTheme.SetToolTip($btnTheme, $(if ($name -eq 'gece') { 'Gece modu — gündüze geç' } else { 'Gündüz modu — geceye geç' }))
  $script:cfg.tema = $name
}

$btnTheme.Add_Click({
    Set-Theme $(if ($script:cfg.tema -eq 'gece') { 'gunduz' } else { 'gece' })
    Save-Settings
    Write-Line $(if ($script:cfg.tema -eq 'gece') { 'Gece moduna geçildi' } else { 'Gündüz moduna geçildi' })
  })


# ---------------- Doldur ----------------
$swText.Invalidate(); $swBox.Invalidate()
Set-Theme ([string]$script:cfg.tema)
$lblOpaVal.Text = ('%{0}' -f [int]([double]$script:cfg.saydamlik * 100))

function Update-AreaLabel {
  if ($null -eq $script:cfg.alan) { $lblArea.Text = 'henüz çizilmedi'; return }
  $a = $script:cfg.alan
  $lblArea.Text = ('{0} × {1}  ·  konum {2},{3}' -f $a.W, $a.H, $a.X, $a.Y)
}
Update-AreaLabel

# Olay bloklari yalniz script kapsamini goruyor: acilir listenin degiskenleri
# yerel tutulursa tiklama aninda null oluyor ve hata veriyordu.
$script:fontField = $fontField
$script:fontPopup = $null

function Close-FontPopup {
  if ($null -ne $script:fontPopup) {
    try { $script:fontPopup.Close(); $script:fontPopup.Dispose() } catch { }
    $script:fontPopup = $null
  }
}

function Set-Font([string]$name) {
  if ([string]::IsNullOrWhiteSpace($name)) { return }
  $script:cfg.yaziTipi = $name
  Save-Settings
  try { $script:fontField.Invalidate() } catch { }
  if ($null -ne $script:overlay) { try { $script:overlay.Invalidate() } catch { } }
  Write-Line ('Yazı tipi: ' + $name)
}

$fontField.Add_Click({
    if ($null -ne $script:fontPopup) { Close-FontPopup; return }
    $pop = New-Object System.Windows.Forms.Form
    $pop.FormBorderStyle = 'None'; $pop.StartPosition = 'Manual'
    $pop.ShowInTaskbar = $false; $pop.TopMost = $true; $pop.BackColor = $C.Line
    $pt = $script:fontField.PointToScreen((New-Object System.Drawing.Point 0, $script:fontField.Height))
    $pop.SetBounds($pt.X, $pt.Y + 4, $script:fontField.Width, ($FONTS.Count * 34 + 4))
    $lst = New-Object System.Windows.Forms.ListBox
    $lst.Dock = 'Fill'; $lst.BackColor = $C.Card2; $lst.ForeColor = $C.Text
    $lst.BorderStyle = 'None'; $lst.DrawMode = 'OwnerDrawFixed'; $lst.ItemHeight = 34
    foreach ($n in $FONTS) { [void]$lst.Items.Add($n) }
    $idx = $lst.Items.IndexOf([string]$script:cfg.yaziTipi)
    if ($idx -ge 0) { $lst.SelectedIndex = $idx }
    $lst.Add_DrawItem({
        param($s, $e)
        try {
          if ($e.Index -lt 0) { return }
          $sel = ($e.State -band [System.Windows.Forms.DrawItemState]::Selected) -ne 0
          $bg = New-Object System.Drawing.SolidBrush $(if ($sel) { $C.Line } else { $C.Card2 })
          $e.Graphics.FillRectangle($bg, $e.Bounds); $bg.Dispose()
          $name = [string]$s.Items[$e.Index]
          $tb = New-Object System.Drawing.SolidBrush $(if ($sel) { $C.Accent } else { $C.Text })
          $ff = New-Object System.Drawing.Font($name, 15, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
          $e.Graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
          $e.Graphics.DrawString(($name + '  —  Gözünü dört aç'), $ff, $tb, $e.Bounds.X + 12, $e.Bounds.Y + 8)
          $ff.Dispose(); $tb.Dispose()
        } catch { }
      })
    # $sender kullaniliyor: yerel degiskene bagli kalmasin
    $lst.Add_MouseUp({
        param($s, $e)
        try {
          $i = $s.IndexFromPoint($e.Location)
          if ($i -ge 0) { Set-Font ([string]$s.Items[$i]) }
        } catch {
          Write-Line ('Yazı tipi seçilemedi: ' + $_.Exception.Message)
        }
        Close-FontPopup
      })
    $pop.Controls.Add($lst)
    $pop.Add_Deactivate({ Close-FontPopup })
    $script:fontPopup = $pop
    $pop.Show(); $lst.Focus()
  })

$swText.Add_Click({
    $d = New-Object System.Windows.Forms.ColorDialog
    $d.Color = ConvertTo-Color $script:cfg.yaziRengi ([System.Drawing.Color]::White); $d.FullOpen = $true
    if ($d.ShowDialog() -eq 'OK') {
      $script:cfg.yaziRengi = [System.Drawing.ColorTranslator]::ToHtml($d.Color)
      $script:colText = $d.Color; $swText.Invalidate(); Save-Settings
      if ($null -ne $script:overlay) { $script:overlay.Invalidate() }
    }
  })
$swBox.Add_Click({
    $d = New-Object System.Windows.Forms.ColorDialog
    $d.Color = ConvertTo-Color $script:cfg.kutuRengi ([System.Drawing.Color]::Black); $d.FullOpen = $true
    if ($d.ShowDialog() -eq 'OK') {
      $script:cfg.kutuRengi = [System.Drawing.ColorTranslator]::ToHtml($d.Color)
      $script:colBox = $d.Color; $swBox.Invalidate(); Save-Settings
      if ($null -ne $script:overlay) { $script:overlay.Invalidate() }
    }
  })
$btnArea.Add_Click({
    $form.WindowState = 'Minimized'
    Start-Sleep -Milliseconds 350
    $ok = Select-Area
    $form.WindowState = 'Normal'
    [void][Native]::SetForegroundWindow($form.Handle)
    if ($ok) { Update-AreaLabel }
  })
$btnClose.Add_Click({ $form.Close() })
$btnMin.Add_Click({ $form.WindowState = 'Minimized' })

# ---------------- Ceviri sureci ----------------
function Start-Translator {
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $Python
  $psi.Arguments = '"' + (Join-Path $root 'translate.py') + '"'
  $psi.UseShellExecute = $false
  $psi.RedirectStandardInput = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
  $psi.CreateNoWindow = $true
  $psi.WorkingDirectory = $root
  $psi.EnvironmentVariables['PYTHONIOENCODING'] = 'utf-8'
  $psi.EnvironmentVariables['CT_LLM_MODEL'] = [string]$script:cfg.llmModel
  $psi.EnvironmentVariables['CT_OCRFIX'] = $(if ([bool]$script:cfg.gelismisOcr) { '1' } else { '0' })
  $script:proc = [System.Diagnostics.Process]::Start($psi)
  $script:writer = New-Object System.IO.StreamWriter($script:proc.StandardInput.BaseStream, (New-Object System.Text.UTF8Encoding $false))
  $script:writer.AutoFlush = $true
  $ready = $script:proc.StandardOutput.ReadLine()
  if ($ready -notlike 'READY*') {
    Write-Line ('Çeviri motoru açılmadı. Gelen yanıt: ' + $ready)
    try { Write-Line ('Python hatası: ' + $script:proc.StandardError.ReadToEnd()) } catch { }
    return $false
  }
  $bits = $ready.Split(' ')
  $nerede = if ($bits.Count -gt 2 -and $bits[2] -eq 'gpu') { 'ekran kartı' } else { 'işlemci' }
  Write-Line ('Çeviri motoru hazır (' + $bits[1] + ' ms) · okuma: ' + $nerede)
  Write-Dev ('OZET   | okuma ' + $nerede + ' üzerinde çalışıyor')
  return $true
}

function Stop-Translator {
  if ($null -ne $script:writer) { try { $script:writer.WriteLine('__quit__') } catch { } }
  if ($null -ne $script:proc -and -not $script:proc.HasExited) { try { $script:proc.Kill() } catch { } }
  $script:proc = $null; $script:writer = $null
}

# ---------------- Dongu ----------------
# IPC yanitini beklerken arayuzu dondurmaz. Blocking ReadLine, kare basina
# 230-570 ms boyunca mesaj dongusunu kilitliyordu; altyazi kutusu oyun
# yeniden cizdiginde tazelenemedigi icin ekranda takilma gorunuyordu.
function Wait-Reply {
  $task = $script:proc.StandardOutput.ReadLineAsync()
  while (-not $task.IsCompleted) {
    [System.Windows.Forms.Application]::DoEvents()
    [System.Threading.Thread]::Sleep(3)
  }
  return $task.Result
}

function Invoke-Tick {
    if (-not $script:running) { return }

    # Tuslar
    if (([Native]::GetAsyncKeyState(0x78) -band 0x8000) -ne 0) { Write-Line 'F9 ile durduruldu'; Stop-Run; return }
    $f8 = (([Native]::GetAsyncKeyState(0x77) -band 0x8000) -ne 0)
    if ($f8 -and -not $script:prevF8) {
      $script:paused = -not $script:paused
      Write-Line $(if ($script:paused) { 'Duraklatıldı' } else { 'Devam' })
      $lblStatus.Text = $(if ($script:paused) { 'Duraklatıldı · F8 ile devam' } else { 'Çalışıyor' })
      if ($script:paused -and $null -ne $script:overlay) { $script:boxText = ''; $script:boxRect = $null; $script:overlay.Invalidate() }
    }
    $script:prevF8 = $f8
    if ($script:paused) { return }

    # Oyun penceresini kilitle
    if ($null -ne $script:lockAt) {
      if ((Get-Date) -ge $script:lockAt) {
        $script:gameWindow = [Native]::GetForegroundWindow()
        $script:lockAt = $null
        Write-Line 'Oyun penceresi kilitlendi'
        $lblStatus.Text = 'Çalışıyor'
      } else {
        $left = [int]((New-TimeSpan -Start (Get-Date) -End $script:lockAt).TotalSeconds) + 1
        $lblStatus.Text = ("Oyun penceresine geç… {0}" -f $left)
        return
      }
    }
    if ($script:gameWindow -ne [IntPtr]::Zero -and [Native]::GetForegroundWindow() -ne $script:gameWindow) {
      if ($script:boxText -ne '') { $script:boxText = ''; $script:boxRect = $null; $script:overlay.Invalidate() }
      return
    }

    # Ceviri sureci yasiyor mu
    if ($null -eq $script:proc -or $script:proc.HasExited) {
      Write-Line 'Çeviri süreci kapanmış, yeniden açılıyor…'
      Stop-Translator
      if (-not (Start-Translator)) { Write-Line 'Yeniden açılamadı, durduruluyor'; Stop-Run; return }
      $script:cache = @{}
      return
    }

    # --- Oku ---
    $res = $null
    try {
      $a = Get-SafeArea
      if ($null -eq $a) { Write-Line 'OCR alanı ekran dışında kaldı'; Stop-Run; return }
      $swCap = [System.Diagnostics.Stopwatch]::StartNew()
      Grab-Region $a.X $a.Y $a.W $a.H $script:framePath
      $swCap.Stop()
      $swOcr = [System.Diagnostics.Stopwatch]::StartNew()
      $script:writer.WriteLine("#ocr`t" + $script:framePath + "`t" + $a.W)
      $ocrReply = Wait-Reply
      $swOcr.Stop()
      if ($null -eq $ocrReply) { throw 'okuma sureci yanit vermedi' }
      $script:capMs = [int]$swCap.ElapsedMilliseconds
      $script:ocrMs = [int]$swOcr.ElapsedMilliseconds
      $script:frameCount++
      $script:errorStreak = 0
    } catch {
      # Tek tek okuma hatalari programi durdurmaz; ust uste gelirse durur
      $script:errorStreak++
      $script:warnCount++
      Write-Line ('Okuma hatası (' + $script:errorStreak + '): ' + $_.Exception.Message)
      Write-Dev ('UYARI  | okuma hatası (' + $script:errorStreak + '): ' + $_.Exception.Message)
      if ($script:errorStreak -ge 10) { Write-Line 'Arka arkaya hata, durduruluyor'; Stop-Run }
      return
    }

    try {
      # Yanit: ms <TAB> x,y,w,h <TAB> satir <TAB> metin  (kenardaki HUD zaten elenmis)
      $parts = $ocrReply.Split("`t")
      $raw = if ($parts.Count -gt 3) { $parts[3] } else { '' }
      $sub = $null
      if ($raw -ne '' -and $parts[1] -ne '') {
        $xy = $parts[1].Split(',')
        $sub = @{
          Text  = $raw
          Lines = [int]$parts[2]
          Rect  = New-Object System.Drawing.Rectangle `
            ([int]$a.X + [int]$xy[0]), ([int]$a.Y + [int]$xy[1]), ([int]$xy[2]), ([int]$xy[3])
          Dropped = @()
        }
      }
      $text = if ($null -eq $sub) { '' } else { Clear-Junk $sub.Text }
      $key = Get-Key $text
      $blankLimit = [math]::Max(1, [int]($HOLD_MS / $READ_MS))

      if ([bool]$script:cfg.ayrintiliKayit -and $raw -ne $script:lastRaw) {
        $script:lastRaw = $raw
        if ($raw -ne '') {
          $drop = if ($null -ne $sub -and @($sub.Dropped).Count -gt 0) { ' | atılan=' + (@($sub.Dropped) -join ' / ') } else { '' }
          Write-Dev ('OKUMA  | yakala=' + $script:capMs + 'ms ocr=' + $script:ocrMs + 'ms | ham=' + $raw + ' | filtreli=' + $text + $drop)
          if ($text -eq '' ) {
            $script:warnCount++
            Write-Dev ('UYARI  | filtre tüm satırları attı, çeviri yapılmadı | ham=' + $raw)
          }
        }
      }

      # Ekranda yazi var ama uzun suredir onaylanamiyor (OCR her karede farkli okuyor)
      if ($text.Length -ge 3 -and $null -ne $script:firstSeen) {
        $waited = ((Get-Date) - $script:firstSeen).TotalMilliseconds
        if ($waited -gt 1500 -and -not $script:warnedPending) {
          $script:warnedPending = $true
          $script:warnCount++
          Write-Dev ('UYARI  | ' + [int]$waited + ' ms boyunca onaylanamadı, OCR her karede farklı okuyor | son=' + $text)
        }
      }

      if ($text.Length -lt 3) {
        $script:blankFrames++
        if ($script:blankFrames -ge $blankLimit) {
          # Altyazi onay beklerken kayboldu: elimizdeki en iyi adayi yine de goster,
          # yoksa kisa suren altyazilar hic cevrilmeden geciyor
          if ($script:readings.Count -ge $REQUIRED_HITS -and $script:boxText -eq '') {
            $pending = $null
            foreach ($r in $script:readings) {
              $g = @($script:readings | Where-Object { Test-SameEnough $_.Key $r.Key })
              if ($null -eq $pending -or $g.Count -gt $pending.Count) { $pending = $g }
            }
            if ($null -ne $pending -and $pending.Count -ge $REQUIRED_HITS) {
              $script:lateCandidate = Select-Best $pending
              Write-Dev ('ONAY   | altyazı kaybolurken onaylandı (' + $pending.Count + ' okuma) | EN=' + $script:lateCandidate.Text)
            }
          }
          $script:readings.Clear()
          if ($script:boxText -ne '') {
            $script:boxText = ''; $script:boxRect = $null; $script:overlay.Invalidate(); $script:shownKey = ''
          }
        }
        $script:prevKey = ''; $script:firstSeen = $null; $script:warnedPending = $false
        if ($null -eq $script:lateCandidate) { return }
        # Kaybolurken onaylanan adayi cevir
        $chosen = $script:lateCandidate
        $script:lateCandidate = $null
        $text = $chosen.Text; $sub = $chosen.Sub; $key = $chosen.Key
        if ($null -eq $script:firstSeen) { $script:firstSeen = $chosen.At }
      } else {
      $script:blankFrames = 0
      # Ekranda duran altyazinin kucuk oynamalari: kilitli kal
      if (Test-SameEnough $key $script:shownKey) { $script:readings.Clear(); return }
      if ($null -eq $script:firstSeen) { $script:firstSeen = Get-Date; $script:warnedPending = $false }

      # Mobildeki OcrStabilizer mantigi: arka arkaya degil, PENCERE icinde benzer
      # iki okuma ariyoruz. Araya tamamen bozuk kareler girse bile altyazi onaylanir.
      $now = Get-Date
      while ($script:readings.Count -gt 0 -and ($now - $script:readings[0].At).TotalMilliseconds -gt $WINDOW_MS) {
        $script:readings.RemoveAt(0)
      }
      $entry = @{ Key = $key; Text = $text; Sub = $sub; At = $now }
      $group = @($script:readings | Where-Object { Test-SameEnough $_.Key $key })
      $group += $entry
      [void]$script:readings.Add($entry)
      while ($script:readings.Count -gt $WINDOW_SIZE) { $script:readings.RemoveAt(0) }

        $chosen = Approve-Group $group $now
        if ($null -eq $chosen) { return }
        $text = $chosen.Text
        $sub = $chosen.Sub
        $key = $chosen.Key
        $script:readings.Clear()
      }

      $turkish = $script:cache[$key]
      $fromCache = $true
      $trMs = 0
      if ($null -eq $turkish) {
        $fromCache = $false
        $script:writer.WriteLine(($text -replace "`t", ' '))
        $reply = Wait-Reply
        if ($null -eq $reply) {
          Write-Line 'Çeviri süreci yanıt vermedi'
          $script:warnCount++
          Write-Dev ('UYARI  | çeviri süreci yanıt vermedi, yeniden açılacak | EN=' + $text)
          Stop-Translator
          return
        }
        $parts = $reply.Split("`t", 2)
        $trMs = if ($parts.Count -gt 0) { [int]$parts[0] } else { 0 }
        $turkish = if ($parts.Count -gt 1) { $parts[1] } else { '' }
        # Okuma cok bozuk: gosterme, onbellege alma; temiz okuma birazdan gelir
        if ($turkish -eq '__atla__') {
          Write-Dev ('ATLANDI| okuma çok bozuk, çevrilmedi | EN=' + $text)
          $script:prevKey = $key
          $script:firstSeen = $null
          return
        }
        if ($turkish -eq '') {
          $script:warnCount++
          Write-Dev ('UYARI  | çeviri boş döndü, altyazı gösterilmedi | EN=' + $text)
          $script:prevKey = $key
          return
        }
        $script:cache[$key] = $turkish
      } else {
        $script:cacheHits++
      }

      $total = [int]((Get-Date) - $script:firstSeen).TotalMilliseconds
      [void]$script:latencies.Add($total)
      $script:boxRect = $sub.Rect
      $script:boxLines = [math]::Max(1, [int]$sub.Lines)

      # Punto icin oturmus satir yuksekligi: son 40 okumanin medyani, 2 pikselden
      # kucuk oynamalar yok sayilir. Boylece yazi boyu sabit kalir.
      $lh = [int]($sub.Rect.Height / [math]::Max(1, [int]$sub.Lines))
      if ($lh -ge 8 -and $lh -le 200) {
        [void]$script:lineHeights.Add($lh)
        while ($script:lineHeights.Count -gt 40) { $script:lineHeights.RemoveAt(0) }
        $hs = @($script:lineHeights | Sort-Object)
        $medH = [int]$hs[[int]([math]::Floor($hs.Count / 2))]
        if ($script:stableLineH -eq 0 -or [math]::Abs($medH - $script:stableLineH) -gt 2) {
          if ($script:stableLineH -ne 0) {
            Write-Dev ('CIZIM  | yazı boyu güncellendi: ' + $script:stableLineH + ' -> ' + $medH + ' (son 40 okumanın medyanı)')
          }
          $script:stableLineH = $medH
        }
      }
      $script:boxText = $turkish
      $script:overlay.Invalidate()
      $sorted = @($script:latencies | Sort-Object)
      $med = $sorted[[int]([math]::Floor($sorted.Count / 2))]
      $lblStatus.Text = ('{0} altyazı · {1} ms' -f $script:latencies.Count, $med)

      if ([bool]$script:cfg.ayrintiliKayit) {
        $tag = if ($fromCache) { 'ONBELLEK' } else { 'CEVIRI ' }
        Write-Dev ($tag + '| toplam=' + $total + 'ms yakala=' + $script:capMs + 'ms ocr=' + $script:ocrMs + 'ms çeviri=' + $trMs + 'ms | satır=' + $sub.Lines)
        Write-Dev ('       | EN=' + $text)
        Write-Dev ('       | TR=' + $turkish)
        if ($total -gt 800) {
          $script:warnCount++
          Write-Dev ('UYARI  | gecikme yüksek: ' + $total + ' ms')
        }
        if ($turkish -eq $script:lastTr -and $text -ne $script:lastEn) {
          $script:warnCount++
          Write-Dev ('UYARI  | farklı İngilizce aynı Türkçeye çevrildi | önceki EN=' + $script:lastEn)
        }
        if ($text -eq $script:lastEn) {
          $script:warnCount++
          Write-Dev ('UYARI  | aynı altyazı tekrar çevrildi (üst üste)')
        }
        # Ingilizce alanin kenarina degiyorsa bir kismi disarida kalmis olabilir
        $r = $sub.Rect
        if (($r.X - $a.X) -lt 6 -or (($a.X + $a.W) - ($r.X + $r.Width)) -lt 6 -or
            ($r.Y - $a.Y) -lt 6 -or (($a.Y + $a.H) - ($r.Y + $r.Height)) -lt 6) {
          $script:warnCount++
          Write-Dev ('UYARI  | İngilizce yazı OCR alanının kenarına değiyor, alan dar olabilir | ing=' +
            $r.Width + 'x' + $r.Height + '@' + $r.X + ',' + $r.Y + ' | alan=' + $a.W + 'x' + $a.H + '@' + $a.X + ',' + $a.Y)
        }
      }
      $script:lastEn = $text
      $script:lastTr = $turkish
      $script:shownKey = $key
      $script:firstSeen = $null
      $script:warnedPending = $false
      $script:prevKey = $key

      # Dakikada bir ozet
      if (((Get-Date) - $script:lastSummary).TotalSeconds -ge 60) {
        $script:lastSummary = Get-Date
        Write-Dev ('OZET   | kare=' + $script:frameCount + ' altyazı=' + $script:latencies.Count +
          ' önbellek=' + $script:cacheHits + ' uyarı=' + $script:warnCount + ' medyan=' + $med + 'ms')
      }
    } catch {
      $script:errorStreak++
      $script:warnCount++
      Write-Line ('Çeviri hatası (' + $script:errorStreak + '): ' + $_.Exception.Message)
      Write-Dev ('UYARI  | çeviri hatası (' + $script:errorStreak + '): ' + $_.Exception.Message)
      if ($script:errorStreak -ge 10) { Write-Line 'Arka arkaya hata, durduruluyor'; Stop-Run }
    }
}

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = $READ_MS
# DoEvents yuzunden tik suresi uzarsa zamanlayici yeniden girebilir: tek kapi
$script:inTick = $false
$timer.Add_Tick({
    if ($script:inTick) { return }
    $script:inTick = $true
    try { Invoke-Tick } finally { $script:inTick = $false }
  })

function Start-Run {
  if ($null -eq $script:cfg.alan) { $lblStatus.Text = 'Önce OCR alanını çiz'; Write-Line 'OCR alanı yok'; return }
  if ($null -eq $script:ocr) { $lblStatus.Text = 'OCR motoru yok'; Write-Line 'en-US OCR motoru bulunamadı'; return }
  $lblStatus.Text = 'Çeviri motoru yükleniyor…'
  $form.Refresh()
  Write-Line '— başlatılıyor —'

  # GPU modeli yerel Ollama sunucusunu kullanir; kapaliysa baslat
  if ([bool]$script:cfg.gelismisOcr) {
    $up = $false
    try { [void](Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/version' -TimeoutSec 2); $up = $true } catch { }
    if (-not $up) {
      $exe = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'
      if (Test-Path $exe) {
        Write-Line 'Ollama başlatılıyor…'
        Start-Process -FilePath $exe -ArgumentList 'serve' -WindowStyle Hidden
        Start-Sleep -Seconds 4
      } else {
        Write-Line 'Ollama bulunamadı, hızlı motora düşülecek'
      }
    }
  }
  if ([bool]$script:cfg.gelismisOcr) {
    Write-Line ('Gelişmiş OCR düzeltme açık — ' + $script:cfg.llmModel)
  }
  if (-not (Start-Translator)) { $lblStatus.Text = 'Başlatılamadı · kayda bak'; return }

  $script:overlay = New-OverlayWindow $script:cfg.alan
  $script:cache = @{}
  $script:latencies = New-Object System.Collections.ArrayList
  $script:prevKey = ''; $script:shownKey = ''; $script:firstSeen = $null; $script:blankFrames = 0
  $script:paused = $false; $script:prevF8 = $false; $script:errorStreak = 0
  $script:frameCount = 0; $script:cacheHits = 0; $script:warnCount = 0
  $script:lastRaw = ''; $script:lastEn = ''; $script:lastTr = ''
  $script:warnedPending = $false; $script:lastSummary = Get-Date
  $script:gameWindow = [IntPtr]::Zero
  $script:lockAt = (Get-Date).AddSeconds(4)
  Start-DevLog
  $script:running = $true
  $timer.Start()
  $btnStart.Text = 'DURDUR'; $btnStart.BackColor = $C.Stop
  $lblStatus.Text = 'Oyun penceresine geç… 4'
}

function Stop-Run {
  $timer.Stop()
  $script:running = $false
  if ($null -ne $script:overlay) { $script:overlay.Close(); $script:overlay = $null }
  Stop-Translator
  $btnStart.Text = 'BAŞLAT'; $btnStart.BackColor = $C.Go
  if ($script:latencies.Count -gt 0) {
    $sorted = @($script:latencies | Sort-Object)
    $med = $sorted[[int]([math]::Floor($sorted.Count / 2))]
    $lblStatus.Text = ('Durdu · {0} altyazı · {1} ms' -f $script:latencies.Count, $med)
    Write-Line ('— durdu · {0} altyazı · medyan {1} ms —' -f $script:latencies.Count, $med)
    Write-Dev ('OZET   | BITTI · kare=' + $script:frameCount + ' altyazı=' + $script:latencies.Count +
      ' önbellek=' + $script:cacheHits + ' uyarı=' + $script:warnCount + ' medyan=' + $med + 'ms')
  } else {
    $lblStatus.Text = 'Durdu'
    Write-Line '— durdu —'
    Write-Dev 'OZET   | BITTI · hiç altyazı çevrilmedi'
  }
  Stop-DevLog
}

$btnStart.Add_Click({ if ($script:running) { Stop-Run } else { Start-Run } })
$form.Add_FormClosing({ if ($script:running) { Stop-Run } })
$form.Add_Shown({
    Set-Rounded $form 16
    [void][Native]::ShowWindow($form.Handle, 1)
    [void][Native]::SetForegroundWindow($form.Handle)
  })

$lblStatus.Text = 'F8 duraklat · F9 durdur'

if ($LogTest) {
  # Sahte bir oyun karesi uretip tum kayit yolunu gercekten calistirir
  $script:cfg.ayrintiliKayit = $true
  if ($null -eq $script:cfg.alan) { $script:cfg.alan = [PSCustomObject]@{ X = 0; Y = 0; W = 800; H = 120 } }
  Start-DevLog

  $iw = 1600; $ih = 240
  $bmp = New-Object System.Drawing.Bitmap $iw, $ih
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.Clear([System.Drawing.Color]::Black)
  $fBig = New-Object System.Drawing.Font('Segoe UI', 34)
  $fSml = New-Object System.Drawing.Font('Segoe UI', 24)
  $g.DrawString('AMMO 124', $fSml, [System.Drawing.Brushes]::White, 20, 100)
  $g.DrawString('WANTED', $fSml, [System.Drawing.Brushes]::White, 1420, 100)
  $g.DrawString('Keep your eyes open for Pinkertons.', $fBig, [System.Drawing.Brushes]::White, 460, 60)
  $g.DrawString('They have patrols all over this area.', $fBig, [System.Drawing.Brushes]::White, 455, 130)
  $g.Dispose(); $fBig.Dispose(); $fSml.Dispose()
  $tp = Join-Path $env:TEMP 'logtest.bmp'
  $bmp.Save($tp, [System.Drawing.Imaging.ImageFormat]::Bmp); $bmp.Dispose()

  $res = Read-ImageText $tp
  $rawParts = @(); foreach ($l in $res.Lines) { $rawParts += [string]$l.Text }
  $raw = ($rawParts -join ' ').Trim()
  $sub = Get-Subtitle $res 0 0 $iw
  $text = Clear-Junk $sub.Text
  $drop = if (@($sub.Dropped).Count -gt 0) { ' | atılan=' + (@($sub.Dropped) -join ' / ') } else { '' }

  Write-Dev ('OKUMA  | yakala=31ms ocr=44ms | ham=' + $raw + ' | filtreli=' + $text + $drop)
  Write-Dev ('CEVIRI | toplam=186ms yakala=31ms ocr=44ms çeviri=52ms | satır=' + $sub.Lines)
  Write-Dev ('       | EN=' + $text)
  Write-Dev ('       | TR=Gözlerini Pinkertonlara açık tut. Bu bölgede devriyeler var.')
  $script:lastFontSize = 34; $script:lastFit = $false
  Write-Dev ('CIZIM  | ing=' + $sub.Rect.Width + 'x' + $sub.Rect.Height + ' | punto=' + $script:lastFontSize)
  Write-Dev 'UYARI  | yazı kutuya sığmadı, punto tabana vurdu (11)'
  Write-Dev 'OZET   | kare=120 altyazı=8 önbellek=1 uyarı=2 medyan=183ms'
  Stop-DevLog

  $file = Get-ChildItem -Path $root -Filter 'kayit_*.txt' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  Write-Host ('Kayıt dosyası: ' + $file.Name + '  (' + $file.Length + ' bayt)')
  Write-Host '----------------------------------------'
  Get-Content $file.FullName -Encoding UTF8 | ForEach-Object { Write-Host $_ }
  $form.Dispose()
  exit 0
}

if ($SelfTest) {
  $bmp = New-Object System.Drawing.Bitmap 900, 160
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.Clear([System.Drawing.Color]::FromArgb(255, 62, 60, 56))
  Draw-Subtitle $g 'Bu bölgede devriyeler var, gözünü dört aç.' 60 50 780 60 38
  $g.Dispose()
  $bmp.Save((Join-Path $env:TEMP 'app_preview.png'), [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Host ('Denetim: ' + $form.Controls.Count + '  yazi tipi: ' + $FONTS.Count + '  alan: ' + $lblArea.Text)
  $form.Dispose()
  exit 0
}

Write-Line '— uygulama açıldı —'
$form.Show()
[void][Native]::ShowWindow($form.Handle, 1)
[void][Native]::SetForegroundWindow($form.Handle)
[System.Windows.Forms.Application]::Run($form)
