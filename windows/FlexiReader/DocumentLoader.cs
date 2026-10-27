using System.IO;
using System.IO.Compression;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using UglyToad.PdfPig;
using UglyToad.PdfPig.DocumentLayoutAnalysis.TextExtractor;

namespace FlexiReader;

internal static class DocumentLoader
{
    public static string Load(string path)
    {
        string ext = Path.GetExtension(path).ToLowerInvariant();
        return ext switch
        {
            ".txt" => File.ReadAllText(path),
            ".pdf" => LoadPdf(path),
            ".docx" => LoadDocx(path),
            ".epub" => LoadEpub(path),
            ".html" or ".htm" => HtmlToText(File.ReadAllText(path)),
            _ => File.ReadAllText(path)
        };
    }

    private static string LoadPdf(string path)
    {
        var sb = new StringBuilder();
        using var document = PdfDocument.Open(path);
        for (int p = 1; p <= document.NumberOfPages; p++)
        {
            var page = document.GetPage(p);
            string pageText = ContentOrderTextExtractor.GetText(page);
            if (!string.IsNullOrWhiteSpace(pageText))
            {
                if (sb.Length > 0)
                    sb.AppendLine().AppendLine();
                sb.Append(pageText.Trim());
            }
        }
        return sb.ToString();
    }

    private static string LoadDocx(string path)
    {
        using var archive = ZipFile.OpenRead(path);
        var entry = archive.GetEntry("word/document.xml")
            ?? throw new InvalidDataException("The DOCX document does not contain word/document.xml.");

        using var stream = entry.Open();
        var doc = XDocument.Load(stream);
        XNamespace w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

        var sb = new StringBuilder();
        foreach (var paragraph in doc.Descendants(w + "p"))
        {
            var line = new StringBuilder();
            foreach (var node in paragraph.Descendants())
            {
                if (node.Name == w + "t")
                    line.Append(node.Value);
                else if (node.Name == w + "tab")
                    line.Append('\t');
                else if (node.Name == w + "br" || node.Name == w + "cr")
                    line.AppendLine();
            }

            if (sb.Length > 0)
                sb.AppendLine();
            sb.Append(line.ToString().TrimEnd());
        }
        return sb.ToString();
    }

    private static string LoadEpub(string path)
    {
        using var archive = ZipFile.OpenRead(path);
        string? rootFile = GetEpubRootFile(archive);
        if (rootFile is null)
            return LoadEpubFallback(archive);

        var opfEntry = archive.GetEntry(rootFile.Replace('\\', '/'));
        if (opfEntry is null)
            return LoadEpubFallback(archive);

        XDocument opf;
        using (var stream = opfEntry.Open())
            opf = XDocument.Load(stream);

        XNamespace opfNs = opf.Root?.Name.Namespace ?? XNamespace.None;
        string baseDir = Path.GetDirectoryName(rootFile)?.Replace('\\', '/') ?? string.Empty;

        var manifest = opf.Descendants(opfNs + "item")
            .Where(x => x.Attribute("id") is not null && x.Attribute("href") is not null)
            .ToDictionary(x => x.Attribute("id")!.Value, x => x.Attribute("href")!.Value);

        var sb = new StringBuilder();
        foreach (var itemRef in opf.Descendants(opfNs + "itemref"))
        {
            string? id = itemRef.Attribute("idref")?.Value;
            if (id is null || !manifest.TryGetValue(id, out string? href))
                continue;

            string entryPath = CombineZipPath(baseDir, Uri.UnescapeDataString(href.Split('#')[0]));
            var contentEntry = archive.GetEntry(entryPath);
            if (contentEntry is null)
                continue;

            using var reader = new StreamReader(contentEntry.Open(), Encoding.UTF8, true);
            string text = HtmlToText(reader.ReadToEnd());
            AppendSection(sb, text);
        }
        return sb.ToString();
    }

    private static string? GetEpubRootFile(ZipArchive archive)
    {
        var container = archive.GetEntry("META-INF/container.xml");
        if (container is null)
            return null;
        using var stream = container.Open();
        var xml = XDocument.Load(stream);
        return xml.Descendants().FirstOrDefault(x => x.Name.LocalName == "rootfile")?.Attribute("full-path")?.Value;
    }

    private static string LoadEpubFallback(ZipArchive archive)
    {
        var sb = new StringBuilder();
        foreach (var entry in archive.Entries
                     .Where(e => e.FullName.EndsWith(".xhtml", StringComparison.OrdinalIgnoreCase)
                              || e.FullName.EndsWith(".html", StringComparison.OrdinalIgnoreCase)
                              || e.FullName.EndsWith(".htm", StringComparison.OrdinalIgnoreCase))
                     .OrderBy(e => e.FullName, StringComparer.OrdinalIgnoreCase))
        {
            using var reader = new StreamReader(entry.Open(), Encoding.UTF8, true);
            AppendSection(sb, HtmlToText(reader.ReadToEnd()));
        }
        return sb.ToString();
    }

    private static string CombineZipPath(string baseDir, string relative)
    {
        var parts = (string.IsNullOrEmpty(baseDir) ? relative : baseDir + "/" + relative)
            .Split('/', StringSplitOptions.RemoveEmptyEntries);
        var stack = new Stack<string>();
        foreach (var part in parts)
        {
            if (part == ".") continue;
            if (part == "..")
            {
                if (stack.Count > 0) stack.Pop();
            }
            else stack.Push(part);
        }
        return string.Join('/', stack.Reverse());
    }

    private static void AppendSection(StringBuilder sb, string text)
    {
        if (string.IsNullOrWhiteSpace(text))
            return;
        if (sb.Length > 0)
            sb.AppendLine().AppendLine();
        sb.Append(text.Trim());
    }

    private static string HtmlToText(string html)
    {
        if (string.IsNullOrEmpty(html))
            return string.Empty;

        string s = Regex.Replace(html, @"<(script|style)\b[^>]*>.*?</\1>", "", RegexOptions.IgnoreCase | RegexOptions.Singleline);
        s = Regex.Replace(s, @"<\s*br\s*/?\s*>", "\n", RegexOptions.IgnoreCase);
        s = Regex.Replace(s, @"</\s*(p|div|section|article|header|footer|h[1-6]|li|tr|blockquote)\s*>", "\n", RegexOptions.IgnoreCase);
        s = Regex.Replace(s, @"<\s*li\b[^>]*>", "• ", RegexOptions.IgnoreCase);
        s = Regex.Replace(s, @"<[^>]+>", " ");
        s = WebUtility.HtmlDecode(s);
        s = Regex.Replace(s, @"[ \t\f\v]+", " ");
        s = Regex.Replace(s, @" *\n *", "\n");
        return s;
    }
}
