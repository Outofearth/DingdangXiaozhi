@echo off
REM =====================================================================
REM  Run this script AS ADMIN on the music PC (replace with your own PC name / IP)
REM  Usage:  enable-smb-guest.bat "C:\path\to\your\mp3\folder"
REM  Example: enable-smb-guest.bat "D:\Music\mp3"
REM  It only loosens the local SMB share permissions for guest/anonymous read.
REM =====================================================================
setlocal EnableExtensions
if "%~1"=="" (
  echo Usage: %~nx0 "folder-path"
  echo Example: %~nx0 "D:\Music\mp3"
  pause
  exit /b 1
)
set "FOLDER=%~1"

echo [1/7] Enable Guest account...
net user guest /active:yes
echo [1b] Grant Guest the "log on from network" right (secedit: add Guests to allow, clear deny list)...
set "GUESTINF=%TEMP%\guest_logon.inf"
set "GUESTDB=%TEMP%\guest_logon.sdb"
(
echo [Unicode]
echo Unicode=yes
echo [Version]
echo signature="$CHICAGO$"
echo Revision=1
echo [Privilege Rights]
echo SeNetworkLogonRight = *S-1-5-32-544,*S-1-5-32-545,*S-1-5-32-546
echo SeDenyNetworkLogonRight =
) > "%GUESTINF%"
secedit /configure /db "%GUESTDB%" /cfg "%GUESTINF%" /areas USER_RIGHTS

echo [2/7] Allow insecure guest SMB logons (disabled by default on Win10/11)...
reg add "HKLM\SYSTEM\CurrentControlSet\Services\LanmanWorkstation\Parameters" /v AllowInsecureGuestAuth /t REG_DWORD /d 1 /f
echo [3/7] Set local-account model to Guest-only (forceguest=1; REQUIRED so empty creds map to Guest)...
reg add "HKLM\SYSTEM\CurrentControlSet\Control\Lsa" /v forceguest /t REG_DWORD /d 1 /f

echo [4/7] Open Windows Firewall for File and Printer Sharing (SMB 445)...
netsh advfirewall firewall set rule group="file and printer sharing" new enable=yes

echo [5/7] NTFS permission: grant Everyone READ on the folder and files...
icacls "%FOLDER%" /grant:r Everyone:(OI)(CI)(RX) /T

echo [6/7] (Re)create the 'mp3' share, Everyone READ...
net share mp3 /delete /y 2>nul
net share mp3="%FOLDER%" /GRANT:Everyone,READ

echo [7/7] Verify what was applied:
reg query "HKLM\SYSTEM\CurrentControlSet\Services\LanmanWorkstation\Parameters" /v AllowInsecureGuestAuth
reg query "HKLM\SYSTEM\CurrentControlSet\Control\Lsa" /v forceguest
net user guest | findstr /i "Account active"
net share mp3
echo Done. On the speaker, use Guest (empty user + empty password) to reach \\< YOUR-PC-IP >\< share name >
pause
