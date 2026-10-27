using DocumentFormat.OpenXml.Packaging;
using HtmlAgilityPack;
using Microsoft.Win32;
using System.Collections.ObjectModel;
using System.IO.Compression;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using UglyToad.PdfPig;

namespace FlexiReader.Windows;

public partial class MainWindow : Window
{
    public ObservableCollection<ReaderLine> Lines { get; } = new();
    public ICommand OpenCommand { get; }
    public ICommand SearchCommand { get; }
    public ICommand BookmarkCommand { get; }
    public ICommand PositionCommand { get; }

    private readonly AppStateStore stateStore = new();
    private AppState state;
    private string? currentPath;
    private string currentSearch = string.Empty;
    private bool suppressSave;

    public MainWindow()
    {
        InitializeComponent();
        state = stateStore.Load();
        ReaderList.ItemsSource = Lines;
        DataContext = this;

        OpenCommand = new RelayCommand(_ => OpenDocument());
        SearchCommand = new RelayCommand(_ => SearchDocument());
        BookmarkCommand = new RelayCommand(_ => AddBookmark());
        PositionCommand = new RelayCommand(_ => AnnouncePosition());

        Loaded += (_, _) => OpenButton.Focus();
        Closing += (_, _) => SaveCurrentPosition();
    }

    private void OpenButton_Click(object sender, RoutedEventArgs e) => OpenDocument();
    private void SearchButton_Click(object sender, RoutedEventArgs e) => SearchDocument();
    private void AddBookmarkButton_Click(object sender, RoutedEventArgs e) => AddBookmark();
    private void BookmarksButton_Click(object sender, RoutedEventArgs e) => ShowBookmarks();
    private void RecentButton_Click(object sender, RoutedEventArgs e) => ShowRecent();
    private void PositionButton_Click(object sender, RoutedEventArgs e) => AnnouncePosition();

    private void OpenDocument()
    {
        var dialog = new OpenFileDialog
        {
            Title = "Open document in FlexiReader",
            Filter = "Supported documents|*.pdf;*.docx;*.epub;*.txt;*.html;*.htm|PDF files|*.pdf|Word documents|*.docx|EPUB books|*.epub|Text files|*.txt|HTML files|*.html;*.htm|All files|*.*"
        };
        if (dialog.ShowDialog(this) == true) OpenPath(dialog.FileName, true);
    }

    private void OpenPath(string path, bool addRecent)
    {
        try
        {
            SaveCurrentPosition();
            StatusText.Text = "Opening " + System.IO.Path.GetFileName(path) + "...";
            var document = DocumentLoader.Load(path);
            suppressSave = true;
            Lines.Clear();
            foreach (var line in document.Lines) Lines.Add(line);
            currentPath = path;
            DocumentTitle.Text = System.IO.Path.GetFileName(path);

            if (addRecent)
            {
                state.Recent.RemoveAll(p => string.Equals(p, path, StringComparison.OrdinalIgnoreCase));
                state.Recent.Insert(0, path);
                if (state.Recent.Count > 10) state.Recent.RemoveRange(10, state.Recent.Count - 10);
            }

            int saved = state.Positions.TryGetValue(path, out var pos) ? pos : 0;
            saved = Math.Clamp(saved, 0, Math.Max(0, Lines.Count - 1));
            ReaderList.SelectedIndex = saved;
            ReaderList.ScrollIntoView(ReaderList.SelectedItem);
            suppressSave = false;
            ReaderList.Focus();
            UpdateStatus();
            stateStore.Save(state);
        }
        catch (Exception ex)
        {
            suppressSave = false;
            MessageBox.Show(this, ex.Message, "Unable to open document", MessageBoxButton.OK, MessageBoxImage.Error);
            StatusText.Text = "Unable to open document.";
        }
    }

    private void ReaderList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ReaderList.SelectedIndex >= 0)
        {
            ReaderList.ScrollIntoView(ReaderList.SelectedItem);
            UpdateStatus();
            if (!suppressSave) SaveCurrentPosition();
        }
    }

    private void ReaderList_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Home && Keyboard.Modifiers == ModifierKeys.Control)
        {
            MoveTo(0); e.Handled = true;
        }
        else if (e.Key == Key.End && Keyboard.Modifiers == ModifierKeys.Control)
        {
            MoveTo(Lines.Count - 1); e.Handled = true;
        }
        else if (e.Key == Key.F3)
        {
            FindNext(); e.Handled = true;
        }
        else if (e.Key == Key.F3 && Keyboard.Modifiers == ModifierKeys.Shift)
        {
            FindPrevious(); e.Handled = true;
        }
    }

    private void MoveTo(int index)
    {
        if (Lines.Count == 0) return;
        ReaderList.SelectedIndex = Math.Clamp(index, 0, Lines.Count - 1);
        ReaderList.ScrollIntoView(ReaderList.SelectedItem);
        ReaderList.Focus();
    }

    private void SearchDocument()
    {
        if (Lines.Count == 0) return;
        string? value = PromptWindow.Ask(this, "Search document", "Search for:", currentSearch);
        if (string.IsNullOrWhiteSpace(value)) return;
        currentSearch = value;
        if (!FindNext()) MessageBox.Show(this, "No match found.", "Search", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private bool FindNext()
    {
        if (string.IsNullOrWhiteSpace(currentSearch) || Lines.Count == 0) return false;
        int start = Math.Max(0, ReaderList.SelectedIndex + 1);
        for (int pass = 0; pass < 2; pass++)
        {
            int from = pass == 0 ? start : 0;
            int to = pass == 0 ? Lines.Count : Math.Min(start, Lines.Count);
            for (int i = from; i < to; i++)
            {
                if (Lines[i].Text.Contains(currentSearch, StringComparison.OrdinalIgnoreCase)) { MoveTo(i); return true; }
            }
        }
        return false;
    }

    private bool FindPrevious()
    {
        if (string.IsNullOrWhiteSpace(currentSearch) || Lines.Count == 0) return false;
        int start = ReaderList.SelectedIndex <= 0 ? Lines.Count - 1 : ReaderList.SelectedIndex - 1;
        for (int i = start; i >= 0; i--)
            if (Lines[i].Text.Contains(currentSearch, StringComparison.OrdinalIgnoreCase)) { MoveTo(i); return true; }
        for (int i = Lines.Count - 1; i > start; i--)
            if (Lines[i].Text.Contains(currentSearch, StringComparison.OrdinalIgnoreCase)) { MoveTo(i); return true; }
        return false;
    }

    private void AddBookmark()
    {
        if (currentPath is null || ReaderList.SelectedIndex < 0) return;
        if (!state.Bookmarks.TryGetValue(currentPath, out var list))
        {
            list = new List<BookmarkEntry>();
            state.Bookmarks[currentPath] = list;
        }
        int index = ReaderList.SelectedIndex;
        if (list.Any(b => b.LineIndex == index))
        {
            StatusText.Text = "Bookmark already exists at this position.";
            return;
        }
        list.Add(new BookmarkEntry(index, Lines[index].Text));
        list.Sort((a, b) => a.LineIndex.CompareTo(b.LineIndex));
        stateStore.Save(state);
        StatusText.Text = "Bookmark added at " + GetPercent(index) + " percent.";
    }

    private void ShowBookmarks()
    {
        if (currentPath is null || !state.Bookmarks.TryGetValue(currentPath, out var list) || list.Count == 0)
        {
            MessageBox.Show(this, "There are no bookmarks in this document.", "Bookmarks", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        var choices = list.Select(b => $"{GetPercent(b.LineIndex)} percent — {TrimForLabel(b.Text)}").ToList();
        int selected = ChoiceWindow.Choose(this, "Bookmarks", choices);
        if (selected >= 0) MoveTo(list[selected].LineIndex);
    }

    private void ShowRecent()
    {
        var existing = state.Recent.Where(File.Exists).ToList();
        if (existing.Count == 0)
        {
            MessageBox.Show(this, "There are no recent documents.", "Recent documents", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        var choices = existing.Select(p => System.IO.Path.GetFileName(p)).ToList();
        int selected = ChoiceWindow.Choose(this, "Recent documents", choices);
        if (selected >= 0) OpenPath(existing[selected], true);
    }

    private void AnnouncePosition()
    {
        if (Lines.Count == 0) return;
        StatusText.Text = "Reading position: " + GetPercent(Math.Max(0, ReaderList.SelectedIndex)) + " percent.";
    }

    private int GetPercent(int index)
    {
        if (Lines.Count <= 1) return 0;
        return Math.Clamp((int)Math.Round(index * 100.0 / (Lines.Count - 1)), 0, 100);
    }

    private void UpdateStatus()
    {
        if (Lines.Count == 0) { StatusText.Text = "Open a document to begin."; return; }
        int index = Math.Max(0, ReaderList.SelectedIndex);
        StatusText.Text = $"{GetPercent(index)} percent through document. Line {index + 1} of {Lines.Count}.";
    }

    private void SaveCurrentPosition()
    {
        if (currentPath is null || ReaderList.SelectedIndex < 0) return;
        state.Positions[currentPath] = ReaderList.SelectedIndex;
        stateStore.Save(state);
    }

    private static string TrimForLabel(string text) => text.Length <= 80 ? text : text[..77] + "...";
}

public sealed record ReaderLine(string Text, bool IsHeading = false, int? PageNumber = null);
public sealed record BookmarkEntry(int LineIndex, string Text);

public sealed class AppState
{
    public List<string> Recent { get; set; } = new();
    public Dictionary<string, int> Positions { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public Dictionary<string, List<BookmarkEntry>> Bookmarks { get; set; } = new(StringComparer.OrdinalIgnoreCase);
}

public sealed class AppStateStore
{
    private readonly string path;
    public AppStateStore()
    {
        string folder = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FlexiReader");
        Directory.CreateDirectory(folder);
        path = System.IO.Path.Combine(folder, "state.json");
    }
    public AppState Load()
    {
        try { return File.Exists(path) ? JsonSerializer.Deserialize<AppState>(File.ReadAllText(path)) ?? new AppState() : new AppState(); }
        catch { return new AppState(); }
    }
    public void Save(AppState value)
    {
        try { File.WriteAllText(path, JsonSerializer.Serialize(value, new JsonSerializerOptions { WriteIndented = true })); } catch { }
    }
}

public static class DocumentLoader
{
    public static LoadedDocument Load(string path)
    {
        string ext = System.IO.Path.GetExtension(path).ToLowerInvariant();
        return ext switch
        {
            ".pdf" => LoadPdf(path),
            ".docx" => LoadDocx(path),
            ".epub" => LoadEpub(path),
            ".html" or ".htm" => LoadHtml(path),
            ".txt" => FromText(File.ReadAllText(path)),
            _ => throw new NotSupportedException("This file type is not supported yet.")
        };
    }

    private static LoadedDocument LoadPdf(string path)
    {
        var result = new List<ReaderLine>();
        using var pdf = PdfDocument.Open(path);
        foreach (var page in pdf.GetPages())
        {
            string text = page.Text;
            foreach (var line in NormalizeLines(text)) result.Add(new ReaderLine(line, false, page.Number));
        }
        return new LoadedDocument(result);
    }

    private static LoadedDocument LoadDocx(string path)
    {
        var result = new List<ReaderLine>();
        using var doc = WordprocessingDocument.Open(path, false);
        var body = doc.MainDocumentPart?.Document.Body;
        if (body is null) return new LoadedDocument(result);
        foreach (var p in body.Descendants<DocumentFormat.OpenXml.Wordprocessing.Paragraph>())
        {
            string text = p.InnerText?.Trim() ?? string.Empty;
            if (text.Length == 0) continue;
            string style = p.ParagraphProperties?.ParagraphStyleId?.Val?.Value ?? string.Empty;
            bool heading = style.StartsWith("Heading", StringComparison.OrdinalIgnoreCase);
            result.Add(new ReaderLine(text, heading));
        }
        return new LoadedDocument(result);
    }

    private static LoadedDocument LoadHtml(string path)
    {
        var html = new HtmlDocument();
        html.Load(path);
        return FromHtmlDocument(html);
    }

    private static LoadedDocument LoadEpub(string path)
    {
        var result = new List<ReaderLine>();
        using var zip = ZipFile.OpenRead(path);
        foreach (var entry in zip.Entries.Where(e => e.FullName.EndsWith(".xhtml", StringComparison.OrdinalIgnoreCase) || e.FullName.EndsWith(".html", StringComparison.OrdinalIgnoreCase) || e.FullName.EndsWith(".htm", StringComparison.OrdinalIgnoreCase)).OrderBy(e => e.FullName))
        {
            using var stream = entry.Open();
            using var reader = new StreamReader(stream, Encoding.UTF8, true);
            var html = new HtmlDocument();
            html.LoadHtml(reader.ReadToEnd());
            result.AddRange(FromHtmlDocument(html).Lines);
        }
        return new LoadedDocument(result);
    }

    private static LoadedDocument FromHtmlDocument(HtmlDocument html)
    {
        var result = new List<ReaderLine>();
        var nodes = html.DocumentNode.SelectNodes("//h1|//h2|//h3|//h4|//h5|//h6|//p|//li|//blockquote|//pre");
        if (nodes is null) return FromText(HtmlEntity.DeEntitize(html.DocumentNode.InnerText));
        foreach (var node in nodes)
        {
            string text = HtmlEntity.DeEntitize(node.InnerText).Trim();
            if (text.Length == 0) continue;
            bool heading = node.Name.StartsWith("h", StringComparison.OrdinalIgnoreCase) && node.Name.Length == 2 && char.IsDigit(node.Name[1]);
            result.Add(new ReaderLine(text, heading));
        }
        return new LoadedDocument(result);
    }

    private static LoadedDocument FromText(string text) => new(NormalizeLines(text).Select(x => new ReaderLine(x)).ToList());

    private static IEnumerable<string> NormalizeLines(string text)
    {
        using var reader = new StringReader(text.Replace("\r\n", "\n").Replace('\r', '\n'));
        string? line;
        while ((line = reader.ReadLine()) is not null)
        {
            string value = line.Trim();
            if (value.Length > 0) yield return value;
        }
    }
}

public sealed record LoadedDocument(List<ReaderLine> Lines);

public sealed class RelayCommand : ICommand
{
    private readonly Action<object?> execute;
    public RelayCommand(Action<object?> execute) => this.execute = execute;
    public bool CanExecute(object? parameter) => true;
    public void Execute(object? parameter) => execute(parameter);
    public event EventHandler? CanExecuteChanged { add { } remove { } }
}

public static class PromptWindow
{
    public static string? Ask(Window owner, string title, string label, string initial)
    {
        var window = new Window { Title = title, Width = 440, Height = 170, Owner = owner, WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize };
        var panel = new StackPanel { Margin = new Thickness(14) };
        panel.Children.Add(new TextBlock { Text = label, Margin = new Thickness(0, 0, 0, 6) });
        var box = new TextBox { Text = initial, Margin = new Thickness(0, 0, 0, 10) };
        panel.Children.Add(box);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var ok = new Button { Content = "OK", Width = 80, IsDefault = true, Margin = new Thickness(0, 0, 8, 0) };
        var cancel = new Button { Content = "Cancel", Width = 80, IsCancel = true };
        ok.Click += (_, _) => { window.DialogResult = true; window.Close(); };
        buttons.Children.Add(ok); buttons.Children.Add(cancel); panel.Children.Add(buttons); window.Content = panel;
        window.Loaded += (_, _) => { box.Focus(); box.SelectAll(); };
        return window.ShowDialog() == true ? box.Text : null;
    }
}

public static class ChoiceWindow
{
    public static int Choose(Window owner, string title, IReadOnlyList<string> items)
    {
        int chosen = -1;
        var window = new Window { Title = title, Width = 620, Height = 450, Owner = owner, WindowStartupLocation = WindowStartupLocation.CenterOwner };
        var panel = new DockPanel { Margin = new Thickness(12) };
        var list = new ListBox { ItemsSource = items };
        DockPanel.SetDock(list, Dock.Top); panel.Children.Add(list);
        var open = new Button { Content = "Open", Height = 34, Margin = new Thickness(0, 8, 0, 0), IsDefault = true };
        DockPanel.SetDock(open, Dock.Bottom); panel.Children.Add(open);
        open.Click += (_, _) => { chosen = list.SelectedIndex; if (chosen >= 0) { window.DialogResult = true; window.Close(); } };
        list.MouseDoubleClick += (_, _) => open.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        window.Content = panel;
        window.Loaded += (_, _) => { if (items.Count > 0) list.SelectedIndex = 0; list.Focus(); };
        window.ShowDialog();
        return chosen;
    }
}
