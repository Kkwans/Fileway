using Microsoft.Web.WebView2.Core;

namespace Fileway.App.Views;

internal static class DocumentWebView
{
    internal static Task<CoreWebView2Environment> CreateEnvironmentAsync(string dataFolder) =>
        CoreWebView2Environment.CreateWithOptionsAsync(null, Path.Combine(App.DataDirectory(), dataFolder), null).AsTask();

    internal static void UseLocalAssets(CoreWebView2 core, string host, string page, string assets)
    {
        core.Settings.AreHostObjectsAllowed = false;
        core.Settings.AreDevToolsEnabled = false;
        core.Settings.AreDefaultScriptDialogsEnabled = false;
        core.SetVirtualHostNameToFolderMapping(host, assets, CoreWebView2HostResourceAccessKind.Deny);
        core.NavigationStarting += (_, args) => args.Cancel = args.Uri != page;
        core.FrameNavigationStarting += (_, args) => args.Cancel = true;
        core.NewWindowRequested += (_, args) => args.Handled = true;
    }
}
