using MetroFuse.PluginSdk;

var request = new CookieLoginConfig("https://accounts.example.com/login", "https://music.example.com", "session",
    new() { "AUTH", "SECURE_AUTH" }, new() { "accounts.example.com", "music.example.com" });
request.Validate();
foreach (var url in new[] { "http://music.example.com", "https://evil.test", "https://music.example.com.evil.test",
    "https://user@music.example.com", "https://music.example.com:8443", "file:///tmp/session" })
    if (request.AcceptsNavigation(url)) throw new Exception("Accepted unsafe navigation");
if (request.Capture(new Dictionary<string, string> { ["OTHER"] = "fixture" }) != null)
    throw new Exception("Accepted missing session");
if (request.Capture(new Dictionary<string, string> { ["AUTH"] = "fixture\r\nInjected: value" }) != null)
    throw new Exception("Accepted header injection");
var capture = request.Capture(new Dictionary<string, string> { ["SECURE_AUTH"] = "fixture" });
if (capture?["session"] != "SECURE_AUTH=fixture") throw new Exception("Lost session field");
if (!request.CanCapture("https://accounts.example.com/challenge/phone"))
    throw new Exception("Waited for the final redirect despite a declared service session cookie");
if (request.CanCapture("https://accounts.example.com.evil.test/challenge/phone"))
    throw new Exception("Allowed capture on a foreign origin");
var callback = request with { requiredCookies = new(), completionPath = "/" };
callback.Validate();
if (callback.CanCapture("https://accounts.example.com/") || callback.CanCapture("https://music.example.com/login")
    || !callback.CanCapture("https://music.example.com/"))
    throw new Exception("Lost the explicit completion origin and path restriction");
if ((request with { completionPath = "/" }).CanCapture("https://accounts.example.com/"))
    throw new Exception("Ignored an explicit completion path when session cookies were declared");
var dotted = request with { requiredCookies = new() { "web.sid.example.com" } };
dotted.Validate();
if (dotted.Capture(new Dictionary<string, string> { ["tracking"] = "fixture" }) != null
    || dotted.Capture(new Dictionary<string, string> { ["web.sid.example.com"] = "fixture" })?["session"] != "web.sid.example.com=fixture")
    throw new Exception("Lost the dotted session cookie requirement");
Console.WriteLine("SDK browser validation checks passed.");

foreach (var url in new[] { "https://user:secret@accounts.example.com/challenge?token=secret#secret",
    "https://accounts.example.com:443/challenge?code=secret", "https://accounts.example.com/challenge/account-secret" })
    if (BrowserDiagnostics.Origin(url) != "https://accounts.example.com")
        throw new Exception("Browser diagnostics exposed private URL data");
if (BrowserDiagnostics.Origin("file:///C:/private/session") != "file"
    || BrowserDiagnostics.Origin("data:text/plain,private-secret") != "data")
    throw new Exception("Browser diagnostics exposed private document data");
Console.WriteLine("SDK browser diagnostic redaction checks passed.");
