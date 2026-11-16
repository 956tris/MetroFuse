using Microsoft.Web.WebView2.Core;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace MetroFuse.PluginSdk;

internal static class BrowserNetworkDiagnostics
{
    internal static async Task Attach(CoreWebView2 browser)
    {
        var origins = new Dictionary<string, string>();
        browser.GetDevToolsProtocolEventReceiver("Network.requestWillBeSent").DevToolsProtocolEventReceived += (_, args) =>
        {
            using var message = JsonDocument.Parse(args.ParameterObjectAsJson);
            var root = message.RootElement;
            var id = root.GetProperty("requestId").GetString();
            if (id == null) return;
            if (origins.Count >= 256) origins.Clear();
            origins[id] = BrowserDiagnostics.Origin(root.GetProperty("request").GetProperty("url").GetString());
        };
        browser.GetDevToolsProtocolEventReceiver("Network.loadingFailed").DevToolsProtocolEventReceived += (_, args) =>
        {
            using var message = JsonDocument.Parse(args.ParameterObjectAsJson);
            var root = message.RootElement;
            var id = root.GetProperty("requestId").GetString();
            var error = root.GetProperty("errorText").GetString() ?? "";
            var status = Regex.IsMatch(error, "\\Anet::ERR_[A-Z0-9_]+\\z") ? error : "network_error";
            BrowserDiagnostics.Write("network_failed", id != null && origins.TryGetValue(id, out var origin) ? origin : null, status);
            if (id != null) origins.Remove(id);
        };
        browser.GetDevToolsProtocolEventReceiver("Network.responseReceived").DevToolsProtocolEventReceived += (_, args) =>
        {
            using var message = JsonDocument.Parse(args.ParameterObjectAsJson);
            var response = message.RootElement.GetProperty("response");
            if (response.GetProperty("status").GetDouble() is var status && status >= 400)
                BrowserDiagnostics.Write("http_failed", response.GetProperty("url").GetString(), $"http_{status:F0}");
        };
        browser.GetDevToolsProtocolEventReceiver("Network.loadingFinished").DevToolsProtocolEventReceived += (_, args) =>
        {
            using var message = JsonDocument.Parse(args.ParameterObjectAsJson);
            var id = message.RootElement.GetProperty("requestId").GetString();
            if (id != null) origins.Remove(id);
        };
        try { await browser.CallDevToolsProtocolMethodAsync("Network.enable", "{}"); }
        catch (Exception error) { BrowserDiagnostics.Failure("network_diagnostics_unavailable", error); }
    }
}
