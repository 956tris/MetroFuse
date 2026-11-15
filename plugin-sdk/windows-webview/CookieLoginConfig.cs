using System.Text.Json;
using System.Text.RegularExpressions;

namespace MetroFuse.PluginSdk;

public sealed record CookieLoginConfig(string url, string cookieUrl, string cookieField,
    List<string> requiredCookies, List<string> allowedHosts, string? completionPath = null)
{
    public static CookieLoginConfig ReadFromFile(string path)
    {
        using var input = File.OpenRead(path);
        if (input.Length > 65536) throw new ArgumentException("Browser login request exceeded the size limit");
        var request = JsonSerializer.Deserialize<CookieLoginConfig>(input)
            ?? throw new ArgumentException("Missing browser login request");
        request.Validate();
        return request;
    }

    public bool AcceptsNavigation(string value) => Uri.TryCreate(value, UriKind.Absolute, out var uri)
        && uri.Scheme == "https" && uri.Port == 443 && uri.UserInfo.Length == 0
        && allowedHosts.Contains(uri.Host, StringComparer.Ordinal);

    public void Validate()
    {
        if (url.Length > 4096 || cookieUrl.Length > 4096 || allowedHosts.Count is < 1 or > 12
            || !AcceptsNavigation(url) || !AcceptsNavigation(cookieUrl)
            || !Regex.IsMatch(cookieField, "^[A-Za-z0-9._-]{1,64}$")
            || requiredCookies.Count > 12 || (requiredCookies.Count == 0 && completionPath == null)
            || (completionPath != null && (!Regex.IsMatch(completionPath, "\\A/[A-Za-z0-9/_-]{0,255}\\z")
                || completionPath.StartsWith("//", StringComparison.Ordinal) || new Uri(url).AbsolutePath == completionPath))
            || requiredCookies.Any(name => !Regex.IsMatch(name, "^[A-Za-z0-9_-]{1,128}$")))
            throw new ArgumentException("Invalid browser login request");
    }

    public bool CanCapture(string value) => AcceptsNavigation(value) && new Uri(value).Host == new Uri(cookieUrl).Host
        && (completionPath == null || new Uri(value).AbsolutePath == completionPath);

    public Dictionary<string, string>? Capture(IReadOnlyDictionary<string, string> cookies)
    {
        if (!cookies.Values.Any(value => !string.IsNullOrWhiteSpace(value)) || (requiredCookies.Count > 0
            && !requiredCookies.Any(name => cookies.TryGetValue(name, out var value) && !string.IsNullOrWhiteSpace(value)))) return null;
        var header = string.Join("; ", cookies.Select(pair => $"{pair.Key}={pair.Value}"));
        if (header.Length > 16384 || header.Contains('\r') || header.Contains('\n')) return null;
        return new Dictionary<string, string> { [cookieField] = header };
    }
}
