# Tasinabilir paket uretir: kullanici Python kurmaz, pip calistirmaz, komut yazmaz.
# Klasoru acip CameraTranslator.exe'ye cift tiklar.
#
# Python'un "embeddable" surumu kurulum istemeyen, klasorden calisan bir yapidir.
# Paketleri onun icine koyup programin yanina birakiyoruz; app.ps1 zaten Python'u
# ararken once yanindaki "python" klasorune bakiyor.
#
#   .\paket_yap.ps1                         -> Masaustu\CameraTranslator
#   .\paket_yap.ps1 -Hedef D:\CT -Zip       -> baska yere, ustune ZIP de uret
#
# Model dosyalari OPUS_DIR'den, models\opus_en_tr'den ya da Masaustu\opus_en_tr'den
# alinir; -ModelKlasoru ile elle de gosterilebilir.

param(
  [string]$Hedef,
  [string]$Surum = '3.13.15',
  [string]$ModelKlasoru,
  [switch]$Zip,
  [switch]$SadeceProgram
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Hedef) { $Hedef = Join-Path ([Environment]::GetFolderPath('Desktop')) 'CameraTranslator' }
$py = Join-Path $Hedef 'python'

function Say([string]$t) { Write-Host ('  ' + $t) -ForegroundColor Cyan }

# Guncellenen dosyalar yalnizca bunlar. python\ ve models\ aylarca ayni kalir,
# o yuzden -SadeceProgram onlara hic dokunmuyor.
$PROGRAM_DOSYALARI = @('app.ps1', 'ocr.py', 'translate.py', 'corrector.py', 'slang.py',
                       'ocrfix.py', 'llm.py', 'Baslat.vbs', 'app.ico', 'banner.png', 'README.md')

# Surum app.ps1'den okunur ki tek yerde dursun
$appKaynak = Get-Content (Join-Path $root 'app.ps1') -Raw -Encoding UTF8
$APP_SURUM = '0.0.0'
if ($appKaynak -match "\`$APP_SURUM\s*=\s*'([^']+)'") { $APP_SURUM = $Matches[1] }
$ETIKET = 'pc-v' + $APP_SURUM


# Sadece program dosyalarini tazele: Python ve model yerinde kalir, 2 saniye surer
if ($SadeceProgram) {
  if (-not (Test-Path (Join-Path $Hedef 'python\python.exe'))) {
    throw ('Burada kurulu bir paket yok: ' + $Hedef)
  }
  foreach ($f in $PROGRAM_DOSYALARI) {
    $src = Join-Path $root $f
    if (Test-Path $src) { Copy-Item $src $Hedef -Force }
  }
  $vk = Join-Path $root '..\app\src\main\assets'
  if (Test-Path (Join-Path $vk 'ocr_vocab.tsv')) {
    $vh = Join-Path $Hedef 'assets'
    New-Item -ItemType Directory -Path $vh -Force | Out-Null
    foreach ($v in @('ocr_vocab.tsv', 'ocr_unigrams.tsv', 'ocr_bigrams.bin', 'en_words.txt')) {
      $vs = Join-Path $vk $v
      if (Test-Path $vs) { Copy-Item $vs $vh -Force }
    }
  }
  Write-Host ('  Program dosyalari guncellendi: ' + $Hedef + '  (surum ' + $APP_SURUM + ')') -ForegroundColor Green
  return
}

if (Test-Path $Hedef) { Remove-Item $Hedef -Recurse -Force }

New-Item -ItemType Directory -Path $Hedef -Force | Out-Null

# ---------- 1. Gomulu Python ----------
# Dikkat: adi $zip olamaz, -Zip parametresiyle ayni ada gelir
$indirme = Join-Path $env:TEMP 'ct-python-embed.zip'
$url = "https://www.python.org/ftp/python/$Surum/python-$Surum-embed-amd64.zip"
Say "Gomulu Python indiriliyor ($Surum)"
$eski = $ProgressPreference; $ProgressPreference = 'SilentlyContinue'
Invoke-WebRequest -Uri $url -OutFile $indirme -UseBasicParsing
$ProgressPreference = $eski
Expand-Archive -Path $indirme -DestinationPath $py -Force
Remove-Item $indirme -Force

# Gomulu surumde site-packages varsayilan olarak kapali: ._pth dosyasindaki
# "import site" satiri acilmadan kurulan paketler gorunmuyor.
$pth = Get-ChildItem $py -Filter '*._pth' | Select-Object -First 1
if ($null -eq $pth) { throw '._pth dosyasi bulunamadi' }
$yeni = @()
foreach ($s in (Get-Content $pth.FullName)) {
  if ($s.Trim() -eq '#import site') { $yeni += 'import site' } else { $yeni += $s }
}
if ($yeni -notcontains 'Lib\site-packages') { $yeni += 'Lib\site-packages' }
# ._pth varken sys.path TAMAMEN bu dosyadan gelir; calistirilan betigin klasoru
# kendiliginden eklenmez. '..' = programin kendi klasoru, translate.py'nin
# yanindaki corrector/slang/ocr modulleri oradan bulunuyor.
if ($yeni -notcontains '..') { $yeni += '..' }
$yeni | Set-Content $pth.FullName -Encoding ASCII
Say ('Python hazir: ' + $pth.Name)

# Gomulu paket vcruntime140 getiriyor ama msvcp140'i getirmiyor; onnxruntime ve
# opencv ona bagli. Visual C++ dagitilabilir paketi kurulu olmayan bir makinede
# program hic acilmiyor. Bu DLL'ler Microsoft'un uygulama yaninda dagitilmasina
# izin verdigi dosyalar.
$sistem = Join-Path $env:WINDIR 'System32'
foreach ($dll in @('msvcp140.dll', 'msvcp140_1.dll', 'msvcp140_2.dll', 'concrt140.dll',
                   'vcruntime140.dll', 'vcruntime140_1.dll')) {
  $kaynak = Join-Path $sistem $dll
  $varis = Join-Path $py $dll
  if ((Test-Path $kaynak) -and -not (Test-Path $varis)) { Copy-Item $kaynak $varis -Force }
}
Say 'C++ calisma zamani kopyalandi'

# ---------- 2. Paketler ----------
# Bu makinedeki pip ile kuruluyor; --target dogrudan gomulu Python'un icine yazar.
$hostPy = $null
foreach ($c in @((Join-Path $root '.venv\Scripts\python.exe'),
                 (Join-Path $env:TEMP 'opusvenv\Scripts\python.exe'))) {
  if (Test-Path $c) { $hostPy = $c; break }
}
if ($null -eq $hostPy) {
  $cmd = Get-Command python.exe -ErrorAction SilentlyContinue
  if ($cmd) { $hostPy = $cmd.Source }
}
if ($null -eq $hostPy) { throw 'Paketleri kurmak icin bu makinede Python gerekiyor' }

$hedefPaket = Join-Path $py 'Lib\site-packages'
Say 'Paketler kuruluyor (birkac dakika surebilir)'
# Once bagimliliklar, sonra rapidocr --no-deps ile. Duz kurulumda rapidocr'in
# "onnxruntime" bagimliligi DirectML surumunun uzerine CPU surumunu kuruyor ve
# okuma ekran kartini hic kullanmiyor.
& $hostPy -m pip install --quiet --target $hedefPaket `
  numpy onnxruntime-directml opencv-python-headless pyclipper shapely six Pillow PyYAML
if ($LASTEXITCODE -ne 0) { throw 'pip kurulumu basarisiz' }
& $hostPy -m pip install --quiet --target $hedefPaket --no-deps rapidocr-onnxruntime
if ($LASTEXITCODE -ne 0) { throw 'rapidocr kurulumu basarisiz' }

# rapidocr normalde tam opencv istiyor; headless surumu ayni modulu veriyor ve
# ~70 MB daha kucuk. Ikisi birden kurulursa birbirinin ustune biniyor.
Get-ChildItem $hedefPaket -Filter 'opencv_python-*' -Directory -ErrorAction SilentlyContinue |
  Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
Get-ChildItem $hedefPaket -Filter '__pycache__' -Recurse -Directory -ErrorAction SilentlyContinue |
  Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

# Kurulum dogrulamasi: paketler yuklenebiliyor mu ve DirectML yerinde mi
$kontrol = & (Join-Path $py 'python.exe') -c "import onnxruntime as o, cv2, numpy, rapidocr_onnxruntime; print(','.join(o.get_available_providers()))"
if ($LASTEXITCODE -ne 0) { throw 'Gomulu Python paketleri yukleyemedi' }
if ($kontrol -notmatch 'Dml') { throw ('DirectML kurulmamis, saglayicilar: ' + $kontrol) }
Say ('Paketler dogrulandi: ' + $kontrol)

# ---------- 3. Baslatici exe ----------
# Windows'un kendi C# derleyicisi kullaniliyor, disaridan arac gerekmiyor.
# Exe sikistirilmiyor ve app.ps1 yaninda okunur duruyor: antivirusler paketlenmis,
# gizlenmis yapilardan siphelenir, burada saklanan bir sey yok.
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (Test-Path $csc) {
  $cs = Join-Path $env:TEMP 'ct_launcher.cs'
  @'
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows.Forms;

// Baslatici sessiz kalmamali. Onceki surum PowerShell'i baslatip hemen
// cikiyordu; app.ps1 acilista bir hata verirse ekranda hicbir sey olmuyor,
// kullanici neden acilmadigini goremiyordu. Simdi surec boyunca ayakta kalip
// ciktiyi dosyaya yaziyor, kotu bir cikis olursa hatayi gosteriyor.
class Baslatici {
    static StringBuilder kayit = new StringBuilder();
    static StringBuilder hata = new StringBuilder();

    [STAThread]
    static void Main() {
        string dizin = AppDomain.CurrentDomain.BaseDirectory;
        string betik = Path.Combine(dizin, "app.ps1");
        string gunluk = Path.Combine(dizin, "baslatma_kaydi.txt");

        if (!File.Exists(betik)) {
            Goster("app.ps1 bulunamadi:" + Environment.NewLine + betik +
                   Environment.NewLine + Environment.NewLine +
                   "Antivirus silmis olabilir. Klasoru taramadan haric tutun.");
            return;
        }

        var psi = new ProcessStartInfo();
        psi.FileName = "powershell.exe";
        psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -STA -File QBETIKQ".Replace("QBETIKQ", QUOTE + betik + QUOTE);
        psi.WorkingDirectory = dizin;
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        psi.RedirectStandardError = true;
        psi.RedirectStandardOutput = true;

        Process p;
        try {
            p = Process.Start(psi);
        } catch (Exception ex) {
            Goster("PowerShell baslatilamadi:" + Environment.NewLine + ex.Message);
            return;
        }

        p.OutputDataReceived += delegate(object s, DataReceivedEventArgs e) {
            if (e.Data != null) Ekle(kayit, e.Data);
        };
        // Karar yalnizca hata akisina ve cikis koduna bakiyor; normal ciktiyi
        // sadece kayda yaziyoruz ki bos bir satir yuzunden uyari cikmasin.
        p.ErrorDataReceived += delegate(object s, DataReceivedEventArgs e) {
            if (e.Data != null) { Ekle(kayit, e.Data); Ekle(hata, e.Data); }
        };
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        p.WaitForExit();

        string metin = kayit.ToString();
        string hataMetni = hata.ToString();
        try {
            if (metin.Length > 0) {
                File.WriteAllText(gunluk, DateTime.Now + Environment.NewLine + metin);
            }
        } catch { }

        if (p.ExitCode != 0 || hataMetni.Trim().Length > 0) {
            string son = hataMetni.Trim();
            if (son.Length == 0) son = metin.Trim();
            if (son.Length > 1500) son = son.Substring(son.Length - 1500);
            if (son.Length == 0) son = "(cikis kodu " + p.ExitCode + ")";
            Goster("Uygulama acilamadi." + Environment.NewLine + Environment.NewLine + son +
                   Environment.NewLine + Environment.NewLine + "Ayrinti: baslatma_kaydi.txt");
        }
    }

    static readonly string QUOTE = ((char)34).ToString();

    static void Ekle(StringBuilder hedef, string satir) {
        lock (hedef) { hedef.AppendLine(satir); }
    }

    static void Goster(string mesaj) {
        MessageBox.Show(mesaj, "Camera Translator",
            MessageBoxButtons.OK, MessageBoxIcon.Error);
    }
}
'@ | Set-Content $cs -Encoding UTF8
  $exe = Join-Path $Hedef 'CameraTranslator.exe'
  $ico = Join-Path $root 'app.ico'
  $cscArgs = @('/nologo', '/target:winexe', '/optimize+', ('/out:' + $exe),
            '/reference:System.Windows.Forms.dll', '/reference:System.dll')
  if (Test-Path $ico) { $cscArgs += ('/win32icon:' + $ico) }
  $cscArgs += $cs
  & $csc $cscArgs | Out-Null
  if (-not (Test-Path $exe)) { throw 'Baslatici derlenemedi' }
  Remove-Item $cs -Force
  Say 'CameraTranslator.exe derlendi'
} else {
  Write-Warning 'C# derleyicisi yok; Baslat.vbs ile calistirilacak'
}

# ---------- 4. Program dosyalari ----------
Say 'Program dosyalari kopyalaniyor'
foreach ($f in $PROGRAM_DOSYALARI) {
  $src = Join-Path $root $f
  if (Test-Path $src) { Copy-Item $src $Hedef -Force } else { Write-Warning "eksik: $f" }
}

# Duzeltici sozlukleri. corrector.py once depodaki mobil varlik klasorune,
# sonra yanindaki 'assets' klasorune bakiyor; tek basina dagitilan pakette
# ikincisi olmazsa duzeltici sessizce devre disi kaliyor.
$varlikKaynak = Join-Path $root '..\app\src\main\assets'
if (Test-Path (Join-Path $varlikKaynak 'ocr_vocab.tsv')) {
  $varlikHedef = Join-Path $Hedef 'assets'
  New-Item -ItemType Directory -Path $varlikHedef -Force | Out-Null
  foreach ($v in @('ocr_vocab.tsv', 'ocr_unigrams.tsv', 'ocr_bigrams.bin', 'en_words.txt')) {
    $vs = Join-Path $varlikKaynak $v
    if (Test-Path $vs) { Copy-Item $vs $varlikHedef -Force }
  }
  Say 'Duzeltici sozlukleri kopyalandi'
} else {
  Write-Warning 'Sozluk varliklari bulunamadi, duzeltici devre disi kalacak'
}

# ---------- 5. Ceviri modeli ----------
$modelHedef = Join-Path $Hedef 'models\opus_en_tr'
New-Item -ItemType Directory -Path $modelHedef -Force | Out-Null
if (-not $ModelKlasoru) {
  foreach ($c in @($env:OPUS_DIR, (Join-Path $root 'models\opus_en_tr'),
                   (Join-Path ([Environment]::GetFolderPath('Desktop')) 'opus_en_tr'))) {
    if ($c -and (Test-Path (Join-Path $c 'encoder_model.onnx'))) { $ModelKlasoru = $c; break }
  }
}
if ($ModelKlasoru) {
  Say 'Ceviri modeli kopyalaniyor (357 MB)'
  Copy-Item (Join-Path $ModelKlasoru '*') $modelHedef -Force
} else {
  Write-Warning 'Model bulunamadi, klasor bos birakildi'
  @'
Çeviri modeli buraya gelecek. Dört dosyayı şu adresten indirip bu klasöre koyun:
https://github.com/crosswindfighter/camera-translator/releases/tag/offline-model-v2

  encoder_model.onnx
  decoder_model_merged.onnx
  source_pieces.tsv
  vocab.json
'@ | Set-Content (Join-Path $modelHedef 'BURAYA_MODEL_KOYUN.txt') -Encoding UTF8
}

# ---------- 6. Ozet ----------
if ($Zip) {
  $eski = $ProgressPreference; $ProgressPreference = 'SilentlyContinue'
  # Tam paket: ilk kurulum icin
  $tam = Join-Path (Split-Path $Hedef -Parent) ('CameraTranslator-' + $ETIKET + '.zip')
  Say 'Tam paket ZIP hazirlaniyor'
  Compress-Archive -Path $Hedef -DestinationPath $tam -Force
  # Guncelleme paketi: mevcut klasorun ustune cikarilir, ~11 MB
  $gecici = Join-Path $env:TEMP ('ct-guncelleme-' + $APP_SURUM)
  if (Test-Path $gecici) { Remove-Item $gecici -Recurse -Force }
  New-Item -ItemType Directory -Path $gecici -Force | Out-Null
  foreach ($f in $PROGRAM_DOSYALARI) {
    $src = Join-Path $Hedef $f
    if (Test-Path $src) { Copy-Item $src $gecici -Force }
  }
  if (Test-Path (Join-Path $Hedef 'assets')) { Copy-Item (Join-Path $Hedef 'assets') $gecici -Recurse -Force }
  if (Test-Path (Join-Path $Hedef 'CameraTranslator.exe')) { Copy-Item (Join-Path $Hedef 'CameraTranslator.exe') $gecici -Force }
  $gun = Join-Path (Split-Path $Hedef -Parent) ('CameraTranslator-' + $ETIKET + '-guncelleme.zip')
  Say 'Guncelleme ZIP hazirlaniyor'
  Compress-Archive -Path (Join-Path $gecici '*') -DestinationPath $gun -Force
  Remove-Item $gecici -Recurse -Force
  $ProgressPreference = $eski
  Write-Host ('  Tam ZIP        : ' + $tam + '  (' + [math]::Round((Get-Item $tam).Length / 1MB) + ' MB)') -ForegroundColor Green
  Write-Host ('  Guncelleme ZIP : ' + $gun + '  (' + [math]::Round((Get-Item $gun).Length / 1MB) + ' MB)') -ForegroundColor Green
}

$mb = [math]::Round(((Get-ChildItem $Hedef -Recurse -File | Measure-Object Length -Sum).Sum) / 1MB)
Write-Host ''
Write-Host ('  Klasor : ' + $Hedef + '  (' + $mb + ' MB, surum ' + $APP_SURUM + ')') -ForegroundColor Green
