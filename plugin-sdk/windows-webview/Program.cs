using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using System.Text.Json;
using MetroFuse.PluginSdk;

// The SDK helper has no provider URLs, account parsing, or authentication policy.
internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Length != 3) return;
        var request = JsonSerializer.Deserialize<CookieLoginConfig>(args[0]);
        if (request == null) return;
        request.Validate();
        ApplicationConfiguration.Initialize();
        Application.Run(new BrowserForm(request, args[1], args[2]));
    }
}

internal sealed class BrowserForm : Form
{
    private readonly WebView2 browser = new() { Dock = DockStyle.Fill };
    private readonly CookieLoginConfig request;
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 500 };
    private readonly string profile;
    private bool extracting;

    public BrowserForm(CookieLoginConfig request, string title, string profile)
    {
        this.request = request;
        this.profile = profile;
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
                browser.CoreWebView2.NavigationStarting += (_, args) => args.Cancel = !request.AcceptsNavigation(args.Uri);
                browser.CoreWebView2.NewWindowRequested += (_, args) =>
                {
                    args.Handled = true;
                    if (request.AcceptsNavigation(args.Uri)) browser.CoreWebView2.Navigate(args.Uri);
                };
                timer.Tick += async (_, _) => await CaptureSession();
                timer.Start();
                browser.CoreWebView2.Navigate(request.url);
            }
            catch
            {
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
            if (!request.AcceptsNavigation(source) || new Uri(source).Host != new Uri(request.cookieUrl).Host) return;
            var cookies = await browser.CoreWebView2.CookieManager.GetCookiesAsync(request.cookieUrl);
            var values = request.Capture(cookies.GroupBy(cookie => cookie.Name).ToDictionary(group => group.Key, group => group.Last().Value));
            if (values == null) return;
            using var output = new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true };
            output.Write(JsonSerializer.Serialize(new { values }));
            browser.CoreWebView2.CookieManager.DeleteAllCookies();
            Close();
        }
        catch { }
        finally { extracting = false; }
    }
}
