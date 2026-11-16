namespace MetroFuse.PluginSdk;

internal static class BrowserDiagnostics
{
    private static readonly System.Diagnostics.Stopwatch elapsed = System.Diagnostics.Stopwatch.StartNew();
    internal static TextWriter Output { get; set; } = Console.Error;

    internal static string Origin(string? value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)) return "unknown";
        if (uri.Scheme is not ("http" or "https")) return uri.Scheme;
        var port = uri.IsDefaultPort ? "" : $":{uri.Port}";
        return $"{uri.Scheme}://{uri.IdnHost}{port}";
    }

    internal static void Write(string eventName, string? url = null, string? status = null)
    {
        // Auth URLs and exception messages can contain secrets. Keep only origins and known status values.
        var origin = url == null ? "" : $" origin={Origin(url)}";
        var detail = status == null ? "" : $" status={status}";
        Output.WriteLine($"METROFUSE_BROWSER elapsed_ms={elapsed.ElapsedMilliseconds} event={eventName}{origin}{detail}");
    }

    internal static void Failure(string eventName, Exception error) =>
        Write(eventName, status: $"{error.GetType().Name}:0x{error.HResult:X8}");
}
