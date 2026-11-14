using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Timer = System.Windows.Forms.Timer;

namespace MetroWv2Helper
{
    static class Program
    {
        private static string? _outputPath;
        private static string _provider = "youtube";
        private static string? _loginUrl;
        private static CookieLoginConfig? _cookieLogin;
        private static string? _title;

        [STAThread]
        static void Main(string[] args)
        {
            // Parse arguments
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == "--output" && i + 1 < args.Length)
                {
                    _outputPath = args[i + 1];
                }
                else if (args[i] == "--login-url" && i + 1 < args.Length)
                {
                    var uri = new Uri(args[i + 1]);
                    if (uri.Scheme != "https" || (uri.Host != "music.youtube.com" && uri.Host != "www.youtube.com" && uri.Host != "accounts.google.com"))
                        throw new ArgumentException("Unsupported login URL");
                    _loginUrl = uri.AbsoluteUri;
                }
                else if (args[i] == "--provider" && i + 1 < args.Length)
                {
                    _provider = args[i + 1].ToLowerInvariant();
                }
                else if (args[i] == "--cookie-config" && i + 1 < args.Length)
                {
                    _cookieLogin = JsonSerializer.Deserialize<CookieLoginConfig>(args[i + 1]) ?? throw new ArgumentException("Missing login request");
                    _cookieLogin.Validate();
                }
                else if (args[i] == "--title" && i + 1 < args.Length)
                {
                    _title = args[i + 1];
                }
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new LoginForm(_outputPath, _provider, _loginUrl, _cookieLogin, _title));
        }
    }

    public class LoginForm : Form
    {
        private WebView2 _webView;
        private readonly string? _outputPath;
        private string _provider;
        private readonly string? _loginUrl;
        private readonly CookieLoginConfig? _cookieLogin;
        private readonly LoginCompletion _completion = new();
        private readonly Timer _extractionTimer = new() { Interval = 500 };
        private bool _extracting;
        private string? _userDataFolder;

        public LoginForm(string? outputPath, string provider, string? loginUrl, CookieLoginConfig? cookieLogin = null, string? title = null)
        {
            _outputPath = outputPath;
            _provider = provider;
            _loginUrl = loginUrl;
            _cookieLogin = cookieLogin;
            Text = title ?? (provider == "spotify" ? "Metrolist login for Spotify" : "Metrolist login for YouTube Music");
            Size = new System.Drawing.Size(800, 800);
            StartPosition = FormStartPosition.CenterScreen;

            _webView = new WebView2 { Dock = DockStyle.Fill };
            Controls.Add(_webView);

            InitializeAsync();
        }

        private async void InitializeAsync()
        {
            try
            {
                string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                TryDeleteDirectory(Path.Combine(localAppData, "Metrolist", "spotify-login-helper"));
                TryDeleteDirectory(Path.Combine(localAppData, "Metrolist", "login-helper"));
                _userDataFolder = Path.Combine(Path.GetTempPath(), "Metrolist-login-" + Guid.NewGuid().ToString("N"));

                var env = await CoreWebView2Environment.CreateAsync(null, _userDataFolder);
                var controllerOptions = env.CreateCoreWebView2ControllerOptions();
                controllerOptions.IsInPrivateModeEnabled = true;
                await _webView.EnsureCoreWebView2Async(env, controllerOptions);

                _webView.CoreWebView2.SourceChanged += OnSourceChanged;
                if (_cookieLogin != null)
                {
                    _webView.CoreWebView2.NavigationStarting += (_, args) => args.Cancel = !_cookieLogin.AcceptsNavigation(args.Uri);
                    _webView.CoreWebView2.NewWindowRequested += (_, args) =>
                    {
                        args.Handled = true;
                        if (_cookieLogin.AcceptsNavigation(args.Uri)) _webView.CoreWebView2.Navigate(args.Uri);
                    };
                }
                _extractionTimer.Tick += async (_, _) => await ExtractForCurrentSourceAsync();
                _extractionTimer.Start();
                string loginUrl = _cookieLogin?.url ?? (_provider == "spotify"
                    ? "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
                    : _loginUrl ?? "https://accounts.google.com/ServiceLogin?continue=https://music.youtube.com");
                _webView.CoreWebView2.Navigate(loginUrl);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Login initialization failed: " + ex.Message);
                Close();
            }
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            _extractionTimer.Dispose();
            _webView.Dispose();
            if (_userDataFolder != null) TryDeleteDirectory(_userDataFolder);
            base.OnFormClosed(e);
        }

        private static void TryDeleteDirectory(string path)
        {
            for (int attempt = 0; attempt < 5 && Directory.Exists(path); attempt++)
            {
                try
                {
                    Directory.Delete(path, true);
                }
                catch
                {
                    System.Threading.Thread.Sleep(200);
                }
            }
        }

        private async void OnSourceChanged(object? sender, CoreWebView2SourceChangedEventArgs e)
        {
            await ExtractForCurrentSourceAsync();
        }

        private async Task ExtractForCurrentSourceAsync()
        {
            if (_completion.IsCompleted || _extracting || IsDisposed) return;
            _extracting = true;
            try
            {
                var uri = new Uri(_webView.CoreWebView2.Source);
                if (uri.Scheme != "https") return;
                if (_cookieLogin != null)
                {
                    if (uri.Host == new Uri(_cookieLogin.cookieUrl).Host) await TryExtractCookieLoginAsync();
                }
                else if (_provider == "spotify" && uri.Host == "open.spotify.com") await TryExtractSpotifyAsync();
                else if (_provider != "spotify" && uri.Host == "music.youtube.com") await TryExtractAsync();
            }
            finally
            {
                _extracting = false;
            }
        }

        private async Task TryExtractSpotifyAsync()
        {
            try
            {
                var cookies = await _webView.CoreWebView2.CookieManager.GetCookiesAsync("https://open.spotify.com");
                var sessionCookie = cookies.FirstOrDefault(cookie => cookie.Name == "sp_dc")?.Value;
                if (string.IsNullOrWhiteSpace(sessionCookie)) return;

                var result = new { spotifyCookie = sessionCookie };
                if (WriteResult(result))
                {
                    _webView.CoreWebView2.CookieManager.DeleteAllCookies();
                    Close();
                }
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Spotify extraction failed: " + ex.Message);
            }
        }

        private async Task TryExtractCookieLoginAsync()
        {
            try
            {
                var config = _cookieLogin!;
                var cookies = await _webView.CoreWebView2.CookieManager.GetCookiesAsync(config.cookieUrl);
                var values = config.Capture(cookies.GroupBy(cookie => cookie.Name).ToDictionary(group => group.Key, group => group.Last().Value));
                if (values != null && WriteResult(new { values }))
                {
                    _webView.CoreWebView2.CookieManager.DeleteAllCookies();
                    Close();
                }
            }
            catch
            {
                // Browser state and cookies must never appear in diagnostics.
            }
        }

        private async Task TryExtractAsync()
        {
            try
            {
                var cookieManager = _webView.CoreWebView2.CookieManager;

                var musicCookies = await cookieManager.GetCookiesAsync("https://music.youtube.com");
                var youtubeCookies = await cookieManager.GetCookiesAsync("https://www.youtube.com");

                var allCookies = new Dictionary<string, string>();
                foreach (var c in youtubeCookies) allCookies[c.Name] = c.Value;
                foreach (var c in musicCookies) allCookies[c.Name] = c.Value;

                // Read one page snapshot; cookies can arrive before ytcfg is populated.
                string snapshot = await _webView.CoreWebView2.ExecuteScriptAsync(@"(() => {
                    const yt = window.yt?.config_;
                    const cfg = window.ytcfg?.data_;
                    const index = yt?.SESSION_INDEX ?? cfg?.SESSION_INDEX;
                    return {
                        visitorData: yt?.VISITOR_DATA || cfg?.VISITOR_DATA,
                        dataSyncId: yt?.DATASYNC_ID || cfg?.DATASYNC_ID,
                        authUser: index == null ? null : String(index)
                    };
                })()");
                var result = LoginCompletion.ReadYouTubeCredentials(allCookies, snapshot);
                if (result != null && WriteResult(result))
                {
                    cookieManager.DeleteAllCookies();
                    Close();
                }
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Extraction failed: " + ex.Message);
            }
        }

        private bool WriteResult<T>(T result) => _completion.TryWrite(result, payload =>
        {
            string json = JsonSerializer.Serialize(payload);
            if (_outputPath == null)
            {
                Console.Out.Write(json);
                Console.Out.Flush();
            }
            else
            {
                File.WriteAllText(_outputPath, json);
            }
        });
    }
}
