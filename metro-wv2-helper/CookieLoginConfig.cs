using System.Text.RegularExpressions;

namespace MetroWv2Helper;

public sealed record CookieLoginConfig(string url, string cookieUrl, string cookieField,
    List<string> requiredCookies, List<string> allowedHosts)
{
    public bool AcceptsNavigation(string value) => Uri.TryCreate(value, UriKind.Absolute, out var uri)
        && uri.Scheme == "https" && uri.Port == 443 && uri.UserInfo.Length == 0
        && allowedHosts.Contains(uri.Host, StringComparer.Ordinal);

    public void Validate()
    {
        if (url.Length > 4096 || cookieUrl.Length > 4096 || allowedHosts.Count is < 1 or > 12
            || !AcceptsNavigation(url) || !AcceptsNavigation(cookieUrl)
            || !Regex.IsMatch(cookieField, "^[A-Za-z0-9._-]{1,64}$")
            || requiredCookies.Count is < 1 or > 12
            || requiredCookies.Any(name => !Regex.IsMatch(name, "^[A-Za-z0-9_-]{1,128}$")))
            throw new ArgumentException("Invalid browser login request");
    }

    public Dictionary<string, string>? Capture(IReadOnlyDictionary<string, string> cookies)
    {
        if (!requiredCookies.Any(name => cookies.TryGetValue(name, out var value) && !string.IsNullOrWhiteSpace(value))) return null;
        var header = string.Join("; ", cookies.Select(pair => $"{pair.Key}={pair.Value}"));
        if (header.Length > 16384 || header.Contains('\r') || header.Contains('\n')) return null;
        return new Dictionary<string, string> { [cookieField] = header };
    }
}
