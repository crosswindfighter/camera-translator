' CameraTranslator PC baslatici.
' Amaci: PowerShell'i siyah konsol penceresi acmadan calistirmak.
' Not: uygulama penceresi app.ps1 icinde ShowWindow ile zorla gosteriliyor,
' bu yuzden burada gizli baslatmak pencereyi gizlemiyor.

Dim shell, fso, here, script
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

here = fso.GetParentFolderName(WScript.ScriptFullName)
script = here & "\app.ps1"

If Not fso.FileExists(script) Then
  MsgBox "app.ps1 bulunamadi:" & vbCrLf & script, 16, "CameraTranslator PC"
  WScript.Quit 1
End If

shell.CurrentDirectory = here
shell.Run "powershell.exe -ExecutionPolicy Bypass -STA -NoProfile -File """ & script & """", 0, False
