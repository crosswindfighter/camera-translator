# OCR kiyas seti: farkli zorlukta altyazi kareleri uretir (sikistirilmis, gurultulu).
# Her kare icin dogru metin .txt olarak yanina yazilir.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$dir = Join-Path $env:TEMP 'ocrbench'
if (Test-Path $dir) { Remove-Item (Join-Path $dir '*') -Force -ErrorAction SilentlyContinue }
else { [void](New-Item -ItemType Directory -Path $dir) }

function Save-Jpeg($bmp, $path, [int]$quality) {
  $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
  $pars = New-Object System.Drawing.Imaging.EncoderParameters 1
  $pars.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), ([long]$quality)
  $bmp.Save($path, $codec, $pars)
}

function New-Frame([string]$name, [string]$line1, [string]$line2,
                   [int]$bgBase, [int]$noise, [int]$fontPx, [int]$quality, [bool]$shadow) {
  $W = 2047; $H = 228
  $bmp = New-Object System.Drawing.Bitmap $W, $H
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $rnd = New-Object Random 11
  for ($y = 0; $y -lt $H; $y += 3) {
    for ($x = 0; $x -lt $W; $x += 3) {
      $v = $bgBase + $rnd.Next(-$noise, $noise)
      if ($v -lt 0) { $v = 0 }; if ($v -gt 255) { $v = 255 }
      $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb($v, $v, $v))
      $g.FillRectangle($brush, $x, $y, 3, 3)
      $brush.Dispose()
    }
  }
  $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
  $font = New-Object System.Drawing.Font('Segoe UI', $fontPx, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
  $fmt = New-Object System.Drawing.StringFormat
  $fmt.Alignment = [System.Drawing.StringAlignment]::Center
  $r1 = New-Object System.Drawing.RectangleF 0, 80, $W, 50
  $r2 = New-Object System.Drawing.RectangleF 0, 130, $W, 50
  if ($shadow) {
    $sb = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(170, 0, 0, 0))
    $s1 = New-Object System.Drawing.RectangleF 2, 82, $W, 50
    $s2 = New-Object System.Drawing.RectangleF 2, 132, $W, 50
    $g.DrawString($line1, $font, $sb, $s1, $fmt)
    $g.DrawString($line2, $font, $sb, $s2, $fmt)
    $sb.Dispose()
  }
  $g.DrawString($line1, $font, [System.Drawing.Brushes]::White, $r1, $fmt)
  $g.DrawString($line2, $font, [System.Drawing.Brushes]::White, $r2, $fmt)
  $g.Dispose(); $font.Dispose()

  Save-Jpeg $bmp (Join-Path $dir ($name + '.jpg')) $quality
  $bmp.Dispose()
  Set-Content -Path (Join-Path $dir ($name + '.txt')) -Value ($line1 + ' ' + $line2) -Encoding UTF8
}

New-Frame 'k1_temiz'     "Don't do it, Johnny."                           'You been with my girl again?'        40  10 34 92 $true
New-Frame 'k2_dusukkon'  "Don't do it, Johnny."                           'You been with my girl again?'       150  30 30 45 $false
New-Frame 'k3_kucuk'     'Who the fuck are you speaking to?'              "I've got to hear your crap?"         60  20 22 40 $true
New-Frame 'k4_parlak'    'Take off your pants, cowboy, alright?'          'Let us get this over with.'         200  25 26 35 $false
New-Frame 'k5_sikisik'   'I messed up. I know, cowboy.'                   "It's okay, man. I still love her."    90  35 24 30 $true
New-Frame 'k6_cokbozuk'  'Fucking shit! Who the fuck are you speaking to?' "I've got to hear your crap, Johnny?" 130  45 20 25 $false

Get-ChildItem $dir -Filter *.jpg | ForEach-Object { '{0,-14} {1,7:N0} bayt' -f $_.BaseName, $_.Length }
Write-Host ('klasor: ' + $dir)
