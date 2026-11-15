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
Console.WriteLine("SDK browser validation checks passed.");
