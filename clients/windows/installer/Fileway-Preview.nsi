# -*- coding: utf-8 -*-
; Unsigned, current-user development preview. Payload and uninstall lists are generated externally.
Unicode true
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 32
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"
!ifndef PAYLOAD_DIR
!error "PAYLOAD_DIR must point to an externally staged publish directory."
!endif
!ifndef PACKAGE_VERSION
!error "PACKAGE_VERSION must be supplied by Package-Preview.ps1."
!endif
!ifndef OUTPUT_INSTALLER
!error "OUTPUT_INSTALLER must be supplied."
!endif
!ifndef UNINSTALL_FILES
!error "UNINSTALL_FILES must point to the generated exact file list."
!endif

Name "Fileway Preview ${PACKAGE_VERSION} (unsigned)"
OutFile "${OUTPUT_INSTALLER}"
InstallDir "$LOCALAPPDATA\Programs\Fileway Preview"
BrandingText "Fileway — 未签名本机开发预览"
VIProductVersion "${FILE_VERSION}"
VIAddVersionKey /LANG=2052 "ProductName" "Fileway Preview"
VIAddVersionKey /LANG=2052 "FileDescription" "未签名本机开发预览安装程序"
VIAddVersionKey /LANG=2052 "FileVersion" "${PACKAGE_VERSION}"
VIAddVersionKey /LANG=2052 "LegalCopyright" "Fileway contributors"

!define MUI_ABORTWARNING
!define MUI_ICON "${PAYLOAD_DIR}\Assets\AppIcon.ico"
!define MUI_UNICON "${PAYLOAD_DIR}\Assets\AppIcon.ico"
!define MUI_WELCOMEPAGE_TITLE "Fileway 本机开发预览"
!define MUI_WELCOMEPAGE_TEXT "版本 ${PACKAGE_VERSION}$\r$\n$\r$\n这是未签名、尚未完成正式分发审核的本机开发预览。$\r$\n$\r$\n仅安装到当前用户目录；不会设置文件关联、自启动或系统服务。请先正常关闭正在运行的 Fileway。$\r$\n$\r$\n卸载保留下载和用户数据。可分发许可证完整性仍属于后续 G5 门槛。"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_TITLE "预览程序已安装"
!define MUI_FINISHPAGE_TEXT "从开始菜单打开 Fileway Preview ${PACKAGE_VERSION}。$\r$\n$\r$\n安装程序不会自动启动应用。此产物仅供本机开发验收，不代表已签名或已获准公开发布。"
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

; NSIS uses a 32-bit installer stub, so PROCESSENTRY32W is 556 bytes and szExeFile begins at 36.
; Toolhelp only reads process names. It never terminates or sends close messages to an application.
!macro DefineCheckRunning Prefix
Function ${Prefix}CheckRunning
  System::Call 'kernel32::CreateToolhelp32Snapshot(i 2, i 0) p.r0'
  ${If} $0 == -1
    MessageBox MB_OK|MB_ICONSTOP "无法检查运行中的应用。请正常关闭 Fileway 后重试。" /SD IDOK
    Abort
  ${EndIf}
  System::Alloc 556
  Pop $1
  ${If} $1 == 0
    System::Call 'kernel32::CloseHandle(p r0)'
    Abort
  ${EndIf}
  System::Call '*$1(i 556)'
  System::Call 'kernel32::Process32FirstW(p r0, p r1) i.r2'
  loop:
    ${If} $2 == 0
      Goto finished
    ${EndIf}
    IntOp $3 $1 + 36
    System::Call '*$3(&w260 .r4)'
    System::Call 'kernel32::lstrcmpiW(w r4, w "Fileway.App.exe") i.r5'
    ${If} $5 != 0
      System::Call 'kernel32::lstrcmpiW(w r4, w "fileway-host.exe") i.r5'
    ${EndIf}
    ${If} $5 == 0
      System::Free $1
      System::Call 'kernel32::CloseHandle(p r0)'
      MessageBox MB_OK|MB_ICONSTOP "Fileway 应用或本地核心正在运行。请先正常关闭 Fileway，然后重新运行安装或卸载程序。" /SD IDOK
      Abort
    ${EndIf}
    System::Call 'kernel32::Process32NextW(p r0, p r1) i.r2'
    Goto loop
  finished:
    System::Free $1
    System::Call 'kernel32::CloseHandle(p r0)'
FunctionEnd
!macroend
!insertmacro DefineCheckRunning ""
!insertmacro DefineCheckRunning "un."

Function .onInit
  SetShellVarContext current
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "此预览需要 x64 Windows。" /SD IDOK
    Abort
  ${EndIf}
  Call CheckRunning
FunctionEnd

Function un.onInit
  SetShellVarContext current
  Call un.CheckRunning
FunctionEnd

Section "Fileway Preview" SEC_APP
  Call CheckRunning
  ; Never overwrite an existing version directory: it may contain user-created files.
  IfFileExists "$INSTDIR\versions\${PACKAGE_VERSION}\*.*" 0 new_version
    MessageBox MB_OK|MB_ICONSTOP "此预览版本目录已存在。请先正常卸载同版本，或使用新的预览版本；安装程序不会覆盖现有目录。" /SD IDOK
    Abort
  new_version:
  SetOutPath "$INSTDIR\versions\${PACKAGE_VERSION}"
  SetOverwrite off
  File /r "${PAYLOAD_DIR}\*"
  WriteUninstaller "$OUTDIR\uninstall.exe"
  CreateShortcut "$SMPROGRAMS\Fileway Preview ${PACKAGE_VERSION}.lnk" "$OUTDIR\Fileway.App.exe" "" "$OUTDIR\Assets\AppIcon.ico"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\FilewayPreview-${PACKAGE_VERSION}" "DisplayName" "Fileway Preview ${PACKAGE_VERSION} (unsigned)"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\FilewayPreview-${PACKAGE_VERSION}" "DisplayVersion" "${PACKAGE_VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\FilewayPreview-${PACKAGE_VERSION}" "DisplayIcon" "$OUTDIR\Assets\AppIcon.ico"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\FilewayPreview-${PACKAGE_VERSION}" "InstallLocation" "$OUTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\FilewayPreview-${PACKAGE_VERSION}" "UninstallString" '$\"$OUTDIR\uninstall.exe$\"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\FilewayPreview-${PACKAGE_VERSION}" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\FilewayPreview-${PACKAGE_VERSION}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  Call un.CheckRunning
  !include "${UNINSTALL_FILES}"
  Delete "$INSTDIR\uninstall.exe"
  ; Nonrecursive removal only succeeds when no extra user files remain.
  RMDir "$INSTDIR"
  RMDir "$INSTDIR\.."
  Delete "$SMPROGRAMS\Fileway Preview ${PACKAGE_VERSION}.lnk"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\FilewayPreview-${PACKAGE_VERSION}"
SectionEnd
