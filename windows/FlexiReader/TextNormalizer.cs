using System.Text.RegularExpressions;

namespace FlexiReader;

internal static class TextNormalizer
{
    public static string NormalizeForReader(string? source)
    {
        if (string.IsNullOrEmpty(source))
            return string.Empty;

        string text = source.Replace("\r\n", "\n").Replace('\r', '\n');
        text = new string(text.Where(ch => ch == '\n' || ch == '\t' || ch >= ' ').ToArray());
        text = Regex.Replace(text, @"[ \t]+\n", "\n");
        text = Regex.Replace(text, @"\n[ \t]+", "\n");
        text = Regex.Replace(text, @"\n{4,}", "\n\n\n");
        return text.Trim().Replace("\n", "\r\n");
    }
}
