using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using System.Text.Json;
using MetroFuse.PluginSdk;

// The SDK helper has no provider URLs, account parsing, or authentication policy.
internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length != 3 && (args.Length != 4 || args[3] != "--validate-request")) return 1;
        try
        {
            var request = CookieLoginConfig.ReadFromFile(args[0]);
            if (args.Length == 4)
            {
                using var output = new StreamWriter(Console.OpenStandardOutput());
                output.Write(JsonSerializer.Serialize(request));
                return 0;
            }
            ApplicationConfiguration.Initialize();
            Application.Run(new BrowserForm(request, args[1], args[2]));
            return Environment.ExitCode;
        }
        catch (Exception error)
        {
            BrowserDiagnostics.Failure("startup_failed", error);
            // Invalid requests and startup failures return to the SDK's retry UI.
            return 1;
        }
    }
}

internal sealed class BrowserForm : Form
{
    private readonly WebView2 browser = new() { Dock = DockStyle.Fill };
    private readonly CookieLoginConfig request;
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 500 };
    private readonly string profile;
    private readonly Action<Dictionary<string, string>>? resultSink;
    private bool extracting;
    private string? captureState;

    public BrowserForm(CookieLoginConfig request, string title, string profile,
        Action<Dictionary<string, string>>? resultSink = null)
    {
        this.request = request;
        this.profile = profile;
        this.resultSink = resultSink;
        Text = title;
        Size = new System.Drawing.Size(800, 800);
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(browser);
        Load += async (_, _) =>
        {
            try
            {
                var environment = await CoreWebView2Environment.CreateAsync(userDataFolder: profile);
                var options = environment.CreateCoreWebView2ControllerOptions();
                options.IsInPrivateModeEnabled = true;
                await browser.EnsureCoreWebView2Async(environment, options);
                await BrowserNetworkDiagnostics.Attach(browser.CoreWebView2);
                browser.CoreWebView2.NavigationStarting += (_, args) =>
                {
                    args.Cancel = !request.AcceptsNavigation(args.Uri);
                    BrowserDiagnostics.Write(args.Cancel ? "navigation_blocked" : "navigation_started", args.Uri);
                };
                browser.CoreWebView2.SourceChanged += (_, _) =>
                    BrowserDiagnostics.Write("source_changed", browser.CoreWebView2.Source);
                browser.CoreWebView2.NavigationCompleted += (_, args) =>
                    BrowserDiagnostics.Write("navigation_completed", browser.CoreWebView2.Source,
                        args.IsSuccess ? "success" : args.WebErrorStatus.ToString());
                browser.CoreWebView2.ProcessFailed += (_, args) =>
                    BrowserDiagnostics.Write("browser_process_failed", status: args.ProcessFailedKind.ToString());
                browser.CoreWebView2.NewWindowRequested += (_, args) =>
                {
                    args.Handled = true;
                    BrowserDiagnostics.Write(request.AcceptsNavigation(args.Uri) ? "popup_redirected" : "popup_blocked", args.Uri);
                    if (request.AcceptsNavigation(args.Uri)) browser.CoreWebView2.Navigate(args.Uri);
                };
                BrowserDiagnostics.Write("browser_ready", status: environment.BrowserVersionString);
                timer.Tick += async (_, _) => await CaptureSession();
                timer.Start();
                browser.CoreWebView2.Navigate(request.url);
            }
            catch (Exception error)
            {
                BrowserDiagnostics.Failure("browser_initialization_failed", error);
                // SDK callers receive a failed/cancelled result, never native exception text.
                Environment.ExitCode = 1;
                Close();
            }
        };
        FormClosed += (_, _) =>
        {
            timer.Stop();
            timer.Dispose();
            browser.Dispose();
        };
    }

    private async Task CaptureSession()
    {
        if (extracting || browser.CoreWebView2 == null) return;
        extracting = true;
        try
        {
            var source = browser.CoreWebView2.Source;
            if (!request.AcceptsNavigation(source)) return;
            var cookies = await browser.CoreWebView2.CookieManager.GetCookiesAsync(request.cookieUrl);
            var values = request.Capture(cookies.GroupBy(cookie => cookie.Name).ToDictionary(group => group.Key, group => group.Last().Value));
            var state = $"cookie_ready={values != null},capture_allowed={request.CanCapture(source)}";
            if (captureState != state)
            {
                captureState = state;
                BrowserDiagnostics.Write("session_state", source, state);
            }
            if (values == null || !request.CanCapture(source)) return;
            if (resultSink != null) resultSink(values);
            else
            {
                using var output = new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true };
                output.Write(JsonSerializer.Serialize(new { values }));
            }
            BrowserDiagnostics.Write("session_captured", source);
            browser.CoreWebView2.CookieManager.DeleteAllCookies();
            Close();
        }
        catch (Exception error) { BrowserDiagnostics.Failure("cookie_capture_failed", error); }
        finally { extracting = false; }
    }
}
