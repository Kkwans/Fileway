# -*- coding: utf-8 -*-
; Unsigned, current-user development preview. Payload and uninstall lists are generated externally.
Unicode true
RequestExecutionLevel user
ManifestDPIAware true
ManifestDPIAwareness PerMonitorV2
; Local development packages favor short packaging time over maximum compression.
SetCompressor /SOLID zlib
AllowSkipFiles off
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"
!define SETTINGS_KEY "Software\Fileway\Preview"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\FilewayPreview-${PACKAGE_VERSION}"
Var ProductLanguage
Var StartMenuLink
Var DesktopLink
Var CreatedVersion
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

Name "$(ProductName) ${PACKAGE_VERSION}"
OutFile "${OUTPUT_INSTALLER}"
InstallDir "$LOCALAPPDATA\Programs\Fileway Preview"
InstallDirRegKey HKCU "${SETTINGS_KEY}" "InstallRoot"
BrandingText "$(ProductName)"
VIProductVersion "${FILE_VERSION}"
VIAddVersionKey /LANG=2052 "ProductName" "栖卷"
VIAddVersionKey /LANG=2052 "FileDescription" "栖卷安装程序"
VIAddVersionKey /LANG=2052 "FileVersion" "${PACKAGE_VERSION}"
VIAddVersionKey /LANG=2052 "LegalCopyright" "Fileway contributors"
VIAddVersionKey /LANG=1033 "ProductName" "Fileway"
VIAddVersionKey /LANG=1033 "FileDescription" "Fileway Setup"
VIAddVersionKey /LANG=1033 "FileVersion" "${PACKAGE_VERSION}"
VIAddVersionKey /LANG=1033 "LegalCopyright" "Fileway contributors"

!define MUI_ABORTWARNING
!define MUI_ICON "${PAYLOAD_DIR}\Assets\AppIcon.ico"
!define MUI_UNICON "${PAYLOAD_DIR}\Assets\AppIcon.ico"
!define MUI_LANGDLL_ALWAYSSHOW
!define MUI_LANGDLL_WINDOWTITLE "Language / 语言"
!define MUI_LANGDLL_INFO "Choose your language. / 请选择语言。"
!define MUI_LANGDLL_REGISTRY_ROOT "HKCU"
!define MUI_LANGDLL_REGISTRY_KEY "${UNINSTALL_KEY}"
!define MUI_LANGDLL_REGISTRY_VALUENAME "InstallerLanguage"
!define MUI_WELCOMEPAGE_TITLE "$(WelcomeTitle)"
!define MUI_WELCOMEPAGE_TEXT "$(WelcomeText)"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE VerifyInstallDirectory
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_TITLE "$(FinishTitle)"
!define MUI_FINISHPAGE_TEXT "$(FinishText)"
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

LangString ProductName ${LANG_SIMPCHINESE} "栖卷"
LangString ProductName ${LANG_ENGLISH} "Fileway"
LangString WelcomeTitle ${LANG_SIMPCHINESE} "安装栖卷"
LangString WelcomeTitle ${LANG_ENGLISH} "Install Fileway"
LangString WelcomeText ${LANG_SIMPCHINESE} "版本 ${PACKAGE_VERSION}$\r$\n$\r$\n选择安装文件夹和快捷方式，然后开始安装。请先正常关闭栖卷。$\r$\n$\r$\n已有版本、下载和用户数据会保留。"
LangString WelcomeText ${LANG_ENGLISH} "Version ${PACKAGE_VERSION}$\r$\n$\r$\nChoose an installation folder and shortcuts, then install. Please close Fileway normally first.$\r$\n$\r$\nExisting versions, downloads and user data are kept."
LangString FinishTitle ${LANG_SIMPCHINESE} "栖卷已安装"
LangString FinishTitle ${LANG_ENGLISH} "Fileway is installed"
LangString FinishText ${LANG_SIMPCHINESE} "从开始菜单或已选择创建的桌面快捷方式打开栖卷。$\r$\n$\r$\n版本 ${PACKAGE_VERSION}"
LangString FinishText ${LANG_ENGLISH} "Open Fileway from the Start menu, or from the desktop shortcut if selected.$\r$\n$\r$\nVersion ${PACKAGE_VERSION}"
LangString DesktopShortcutLabel ${LANG_SIMPCHINESE} "创建桌面快捷方式"
LangString DesktopShortcutLabel ${LANG_ENGLISH} "Create a desktop shortcut"
LangString ApplicationDescription ${LANG_SIMPCHINESE} "安装应用并创建此版本的开始菜单快捷方式。"
LangString ApplicationDescription ${LANG_ENGLISH} "Install the application and a Start menu shortcut for this version."
LangString DesktopDescription ${LANG_SIMPCHINESE} "在当前用户的桌面上创建此版本的快捷方式，可取消。"
LangString DesktopDescription ${LANG_ENGLISH} "Create a shortcut for this version on your desktop. This is optional."
LangString CheckRunningFailed ${LANG_SIMPCHINESE} "无法检查运行中的应用。请正常关闭栖卷后重试。"
LangString CheckRunningFailed ${LANG_ENGLISH} "Unable to check running applications. Please close Fileway normally and try again."
LangString AppRunning ${LANG_SIMPCHINESE} "栖卷或本地核心正在运行。请先正常关闭栖卷，再继续安装或卸载。"
LangString AppRunning ${LANG_ENGLISH} "Fileway or its local core is running. Please close Fileway normally before installing or uninstalling."
LangString CloseRunning ${LANG_SIMPCHINESE} "目标安装目录中的栖卷正在运行（可能在后台）。是否自动正常退出并继续？$\r$\n$\r$\n进行中的传输将停止；不会强制结束进程。"
LangString CloseRunning ${LANG_ENGLISH} "Fileway is running from the selected folder, possibly in the background. Exit it normally and continue?$\r$\n$\r$\nActive transfers will stop. Processes will not be force-killed."
LangString CloseTimedOut ${LANG_SIMPCHINESE} "已请求退出，但应用或本地核心仍未结束。请从托盘右键退出；旧版本或无响应进程可在任务管理器中查看。其他目录的开发实例不会阻止安装。"
LangString CloseTimedOut ${LANG_ENGLISH} "Exit was requested but the app or local core is still running. Exit from the tray, or inspect old or unresponsive processes in Task Manager. Instances in other folders do not block installation."
LangString RequiresX64 ${LANG_SIMPCHINESE} "此版本需要 x64 Windows。"
LangString RequiresX64 ${LANG_ENGLISH} "This version requires x64 Windows."
LangString VersionExists ${LANG_SIMPCHINESE} "此版本已安装，或目标版本目录已存在。请先正常卸载同版本，或安装新的版本；不会覆盖现有文件。"
LangString VersionExists ${LANG_ENGLISH} "This version is already installed, or its destination already exists. Uninstall this version normally or install a newer version. Existing files will not be overwritten."
LangString LanguageWriteFailed ${LANG_SIMPCHINESE} "无法保存安装语言。请检查所选文件夹的写入权限后重试。"
LangString LanguageWriteFailed ${LANG_ENGLISH} "Unable to save the installation language. Check write access to the selected folder and try again."
LangString DirectoryNotWritable ${LANG_SIMPCHINESE} "无法写入所选安装文件夹，请选择当前用户有写入权限的位置。"
LangString DirectoryNotWritable ${LANG_ENGLISH} "The selected installation folder is not writable. Choose a location this user can write to."
LangString VersionCreateFailed ${LANG_SIMPCHINESE} "无法创建此版本的独立文件夹。目标可能已存在或不可写；已有文件不会被覆盖。"
LangString VersionCreateFailed ${LANG_ENGLISH} "Unable to create a new folder for this version. It may already exist or be unwritable. Existing files will not be overwritten."
LangString InstallFailed ${LANG_SIMPCHINESE} "安装未完成，已有版本和用户数据未被替换。$\r$\n$\r$\n未完成目录：$INSTDIR\versions\${PACKAGE_VERSION}$\r$\n如该目录内存在 uninstall.exe，可运行它清理本次文件；也可选择另一个安装目录重试。"
LangString InstallFailed ${LANG_ENGLISH} "Installation did not complete. Existing versions and user data were not replaced.$\r$\n$\r$\nIncomplete folder: $INSTDIR\versions\${PACKAGE_VERSION}$\r$\nIf uninstall.exe exists there, it can remove this attempt's files. You can also choose another installation folder and retry."

; Check the selected install root, not every process sharing a development executable name.
; The bundled helper requests normal shutdown and never terminates processes.
!macro DefineCheckRunning Prefix
Function ${Prefix}CheckRunning
  InitPluginsDir
  File /oname=$PLUGINSDIR\Fileway-RequestExit.ps1 "${__FILEDIR__}\Request-AppExit.ps1"
  nsExec::ExecToStack '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\Fileway-RequestExit.ps1" -InstallRoot "$INSTDIR"'
  Pop $0
  Pop $1
  ${If} $0 == 0
    Return
  ${EndIf}
  ${If} $0 != 10
    MessageBox MB_OK|MB_ICONSTOP "$(CheckRunningFailed)" /SD IDOK
    Abort
  ${EndIf}
  MessageBox MB_YESNO|MB_ICONQUESTION "$(CloseRunning)" /SD IDNO IDYES request_close
  Abort
  request_close:
    nsExec::ExecToStack '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\Fileway-RequestExit.ps1" -InstallRoot "$INSTDIR" -RequestClose'
    Pop $0
    Pop $1
    ${If} $0 != 0
      MessageBox MB_OK|MB_ICONSTOP "$(CloseTimedOut)" /SD IDOK
      Abort
    ${EndIf}
FunctionEnd
!macroend
!insertmacro DefineCheckRunning ""
!insertmacro DefineCheckRunning "un."

Function .onInit
  SetShellVarContext current
  StrCpy $CreatedVersion 0
  StrCpy $LANGUAGE ${LANG_ENGLISH}
  System::Call 'kernel32::GetUserDefaultUILanguage() i.r0'
  IntOp $0 $0 & 0x3ff
  StrCpy $1 ""
  System::Call 'kernel32::GetUserDefaultGeoName(w .r1, i ${NSIS_MAX_STRLEN}) i.r2'
  ${If} $0 == 4
  ${OrIf} $1 == "CN"
    StrCpy $LANGUAGE ${LANG_SIMPCHINESE}
  ${EndIf}
  ${If} $0 == 0
  ${AndIf} $1 == ""
    StrCpy $LANGUAGE ${LANG_SIMPCHINESE}
  ${EndIf}
  !insertmacro MUI_LANGDLL_DISPLAY
  StrCpy $ProductLanguage "en"
  ${If} $LANGUAGE == ${LANG_SIMPCHINESE}
    StrCpy $ProductLanguage "zh-CN"
  ${EndIf}
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "$(RequiresX64)" /SD IDOK
    Abort
  ${EndIf}
FunctionEnd

Function un.onInit
  SetShellVarContext current
  !insertmacro MUI_UNGETLANGUAGE
  Call un.CheckRunning
FunctionEnd

Function VerifyInstallDirectory
  ReadRegStr $0 HKCU "${UNINSTALL_KEY}" "InstallLocation"
  ${If} $0 != ""
    MessageBox MB_OK|MB_ICONSTOP "$(VersionExists)" /SD IDOK
    Abort
  ${EndIf}
  IfFileExists "$INSTDIR\versions\${PACKAGE_VERSION}" 0 new_version
    MessageBox MB_OK|MB_ICONSTOP "$(VersionExists)" /SD IDOK
    Abort
  new_version:
  ; MUI's directory validation checks paths/space, not user write access.
  StrCpy $4 0
  IfFileExists "$INSTDIR\*.*" probe_write_access
  ClearErrors
  CreateDirectory "$INSTDIR"
  IfErrors directory_not_writable
  StrCpy $4 1
  probe_write_access:
  System::Call 'kernel32::GetTempFileNameW(w "$INSTDIR", w "FWY", i 0, w .r0) i.r1'
  ${If} $1 == 0
    ${If} $4 == 1
      RMDir "$INSTDIR"
    ${EndIf}
    Goto directory_not_writable
  ${EndIf}
  Delete "$0" ; Only the unique probe file created by GetTempFileNameW.
  ${If} $4 == 1
    RMDir "$INSTDIR" ; Nonrecursive: keep anything else created in this folder.
  ${EndIf}
  Return
  directory_not_writable:
    MessageBox MB_OK|MB_ICONSTOP "$(DirectoryNotWritable)" /SD IDOK
    Abort
FunctionEnd

Function .onInstFailed
  ${If} $CreatedVersion == 1
    MessageBox MB_OK|MB_ICONSTOP "$(InstallFailed)" /SD IDOK
  ${EndIf}
FunctionEnd

; Never overwrite an existing shortcut. Record its timestamp so an edited or
; replaced link is retained when uninstalling this version.
!macro CreateOwnedShortcut Link RegistryValue
  IfFileExists "${Link}" shortcut_done_${RegistryValue}
  ClearErrors
  CreateShortcut "${Link}" "$INSTDIR\versions\${PACKAGE_VERSION}\Fileway.App.exe" "" "$INSTDIR\versions\${PACKAGE_VERSION}\Assets\AppIcon.ico"
  IfErrors shortcut_done_${RegistryValue}
  WriteRegStr HKCU "${UNINSTALL_KEY}" "${RegistryValue}" "${Link}"
  GetFileTime "${Link}" $0 $1
  WriteRegStr HKCU "${UNINSTALL_KEY}" "${RegistryValue}Stamp" "$0:$1"
  shortcut_done_${RegistryValue}:
!macroend

!macro DeleteOwnedShortcut RegistryValue Directory
  ReadRegStr $0 HKCU "${UNINSTALL_KEY}" "${RegistryValue}"
  ReadRegStr $1 HKCU "${UNINSTALL_KEY}" "${RegistryValue}Stamp"
  ${If} $0 == "${Directory}\栖卷 ${PACKAGE_VERSION}.lnk"
  ${OrIf} $0 == "${Directory}\Fileway ${PACKAGE_VERSION}.lnk"
    ClearErrors
    GetFileTime $0 $2 $3
    ${IfNot} ${Errors}
      StrCpy $2 "$2:$3"
      ${If} $1 == $2
        Delete $0
      ${EndIf}
    ${EndIf}
  ${EndIf}
!macroend

Section "$(ProductName)" SEC_APP
  SectionIn RO
  ; NSIS activates the selected language table after .onInit returns.
  ; Resolve localized shortcut names here, not in the language-picker callback.
  StrCpy $StartMenuLink "$SMPROGRAMS\$(ProductName) ${PACKAGE_VERSION}.lnk"
  StrCpy $DesktopLink "$DESKTOP\$(ProductName) ${PACKAGE_VERSION}.lnk"
  Call CheckRunning
  Call VerifyInstallDirectory
  ClearErrors
  CreateDirectory "$INSTDIR\versions"
  IfErrors version_create_failed
  ; Claim an empty version directory atomically before enabling normal File errors.
  System::Call 'kernel32::CreateDirectoryW(w "$INSTDIR\versions\${PACKAGE_VERSION}", p 0) i.r0'
  ${If} $0 == 0
    Goto version_create_failed
  ${EndIf}
  StrCpy $CreatedVersion 1
  ClearErrors
  SetOutPath "$INSTDIR\versions\${PACKAGE_VERSION}"
  IfErrors installation_failed
  WriteUninstaller "$OUTDIR\uninstall.exe"
  IfErrors installation_failed
  ; This directory was just created by this attempt; no existing version is overwritten.
  SetOverwrite on
  ClearErrors
  File /r "${PAYLOAD_DIR}\*"
  IfErrors installation_failed
  ClearErrors
  FileOpen $0 "$OUTDIR\product-language.txt" w
  IfErrors language_write_failed
  FileWrite $0 "$ProductLanguage$\r$\n"
  FileClose $0
  IfErrors language_write_failed
  !insertmacro CreateOwnedShortcut "$StartMenuLink" StartMenuShortcut
  WriteRegStr HKCU "${SETTINGS_KEY}" "InstallRoot" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallerLanguage" "$LANGUAGE"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "$(ProductName) ${PACKAGE_VERSION}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayVersion" "${PACKAGE_VERSION}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayIcon" "$OUTDIR\Assets\AppIcon.ico"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallLocation" "$OUTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '$\"$OUTDIR\uninstall.exe$\"'
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
  Goto application_done
  language_write_failed:
    MessageBox MB_OK|MB_ICONSTOP "$(LanguageWriteFailed)" /SD IDOK
    Abort
  version_create_failed:
    MessageBox MB_OK|MB_ICONSTOP "$(VersionCreateFailed)" /SD IDOK
    Abort
  installation_failed:
    Abort
  application_done:
SectionEnd

Section "$(DesktopShortcutLabel)" SEC_DESKTOP
  !insertmacro CreateOwnedShortcut "$DesktopLink" DesktopShortcut
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_APP} "$(ApplicationDescription)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_DESKTOP} "$(DesktopDescription)"
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  Call un.CheckRunning
  !insertmacro DeleteOwnedShortcut DesktopShortcut "$DESKTOP"
  !insertmacro DeleteOwnedShortcut StartMenuShortcut "$SMPROGRAMS"
  !include "${UNINSTALL_FILES}"
  Delete "$INSTDIR\product-language.txt"
  Delete "$INSTDIR\uninstall.exe"
  ; Nonrecursive removal only succeeds when no extra user files remain.
  RMDir "$INSTDIR"
  RMDir "$INSTDIR\.."
  DeleteRegKey HKCU "${UNINSTALL_KEY}"
SectionEnd
