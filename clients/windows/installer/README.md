# Windows local preview packaging

`../eng/Package-Preview.ps1` consumes an existing self-contained publish directory;
it does not build, publish, sign, install or launch the application.

```powershell
./clients/windows/eng/Package-Preview.ps1 `
  -PublisherInput <artifacts>/publish/Release/win-x64 `
  -ArtifactsRoot <artifacts> -ToolsRoot <isolated-tools> `
  -Version 0.1.1-preview.1
```

Outputs are an unsigned ZIP, a current-user NSIS installer, and a SHA-256 receipt
under the external `dist` directory. Existing delivery files are never replaced.
The script requires the app, Go Host, media runtime lock, libmpv, and nonempty media
license evidence. It rejects reparse points and common data/signing files.

NSIS 3.12 is fixed to its [official portable release](https://sourceforge.net/projects/nsis/files/NSIS%203/3.12/).
When absent, the archive is fetched into the isolated tools directory and checked
against the recorded SHA-256 from the reviewed official download. This is a local
content pin, not a claim of an upstream signature or published upstream SHA-256.

The interactive installer includes a language picker, components page and folder
picker. It defaults to Simplified Chinese when the user's Windows UI language is
Chinese or their geographical region is China; otherwise it defaults to English.
If both preferences are unavailable, it defaults to Chinese.
The selected language sets the visible application name to **栖卷** or **Fileway**
for shortcuts and the uninstall entry. Internal executable names, registry IDs
and version directories remain stable.

The default root is `%LOCALAPPDATA%\Programs\Fileway Preview`. The directory page
allows another writable folder and remembers the last successful root in
`HKCU\Software\Fileway\Preview\InstallRoot`. Each version is installed below
`<root>\versions\<version>`; an existing version directory or registered copy of
that version is never overwritten. The saved root is retained after uninstall as
the user's installation preference.

Leaving the folder page probes write access with a uniquely created temporary
file. Installation then atomically claims a new version directory; extraction
cannot silently skip failed writes. The uninstaller must be created before
payload extraction, and registration happens only after successful extraction
and language persistence. An interrupted attempt retains its partial directory
and, when available, its uninstaller for recovery; it never replaces an older
version or recursively removes the chosen root.

The desktop shortcut component is checked by default and can be disabled. Start
menu and desktop shortcuts include the version, so a new installation does not
replace older versions' shortcuts. Existing links are left intact; only newly
created paths and their timestamps are recorded under this version's HKCU
`FilewayPreview-<version>` uninstall key. Uninstall only removes recorded links
in the expected desktop/Start menu location whose timestamp is unchanged.

The installer writes `product-language.txt` next to `Fileway.App.exe`, containing
the ASCII value `zh-CN` or `en` followed by a line ending. The application uses
this explicit installation choice before the local UI language/region. ZIP
packages do not create this file and use the application's local-language policy.
Uninstall uses the language saved with that installed version.

Install/uninstall checks only process names through Windows Toolhelp and asks
the user to close the application normally. It never kills a process. No file
association, startup entry or service is configured.

Uninstall deletes the exact packaged file list and the installed language file,
then removes only empty directories. Other versions, extra user files, edited
shortcuts and downloads are retained. There is no recursive directory deletion.
The installer does not automatically run the application.

These artifacts are **unsigned local development previews**. The presence of
license files does not establish redistribution completeness. G5 license review,
installation acceptance and public-release approval remain separate gates.
