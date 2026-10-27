using Microsoft.Win32;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;

namespace FlexiReader;

public partial class MainWindow : Window
{
    private string? _currentFile;
    private string _findText = string.Empty;

    public MainWindow()
    {
        InitializeComponent();
        PreviewKeyDown += MainWindow_PreviewKeyDown;
        Loaded += (_, _) => Reader.Focus();
    }

    private void MainWindow_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        // Deliberately do NOT intercept bare Arrow, Home, End, PageUp or PageDown.
        // JAWS and WPF must be allowed to move the native TextBox caret themselves.
        if ((Keyboard.Modifiers & ModifierKeys.Control) != 0 && e.Key == Key.O)
        {
            OpenDocument();
            e.Handled = true;
        }
        else if ((Keyboard.Modifiers & ModifierKeys.Control) != 0 && e.Key == Key.F)
        {
            PromptFind();
            e.Handled = true;
        }
        else if (e.Key == Key.F3)
        {
            if ((Keyboard.Modifiers & ModifierKeys.Shift) != 0)
                FindPrevious();
            else
                FindNext();
            e.Handled = true;
        }
    }

    private void Open_Click(object sender, RoutedEventArgs e) => OpenDocument();

    private void OpenDocument()
    {
        var dialog = new OpenFileDialog
        {
            Title = "Open document",
            Filter = "Supported documents|*.txt;*.pdf;*.docx;*.epub;*.html;*.htm|Text files|*.txt|PDF files|*.pdf|Word documents|*.docx|EPUB books|*.epub|HTML files|*.html;*.htm|All files|*.*"
        };

        if (dialog.ShowDialog(this) != true)
            return;

        try
        {
            StatusText.Text = "Opening document...";
            var text = DocumentLoader.Load(dialog.FileName);
            text = TextNormalizer.NormalizeForReader(text);

            Reader.Text = text;
            _currentFile = dialog.FileName;
            Title = $"FlexiReader 0.1.3 - {Path.GetFileName(dialog.FileName)}";

            Dispatcher.BeginInvoke(() =>
            {
                Reader.Focus();
                Keyboard.Focus(Reader);
                Reader.CaretIndex = 0;
                Reader.Select(0, 0);
                Reader.ScrollToHome();
                UpdateStatus();
            }, DispatcherPriority.ContextIdle);
        }
        catch (Exception ex)
        {
            StatusText.Text = "Could not open document.";
            MessageBox.Show(this, ex.Message, "FlexiReader - Open failed", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Start_Click(object sender, RoutedEventArgs e)
    {
        Reader.Focus();
        Reader.CaretIndex = 0;
        Reader.Select(0, 0);
        Reader.ScrollToHome();
    }

    private void End_Click(object sender, RoutedEventArgs e)
    {
        Reader.Focus();
        Reader.CaretIndex = Reader.Text.Length;
        Reader.Select(Reader.CaretIndex, 0);
        Reader.ScrollToEnd();
    }

    private void Find_Click(object sender, RoutedEventArgs e) => PromptFind();
    private void FindNext_Click(object sender, RoutedEventArgs e) => FindNext();
    private void FindPrevious_Click(object sender, RoutedEventArgs e) => FindPrevious();

    private void PromptFind()
    {
        var dialog = new FindDialog(_findText) { Owner = this };
        if (dialog.ShowDialog() == true)
        {
            _findText = dialog.SearchText;
            FindNext(fromCurrentSelection: false);
        }
        else
        {
            Reader.Focus();
        }
    }

    private void FindNext(bool fromCurrentSelection = true)
    {
        if (string.IsNullOrWhiteSpace(_findText))
        {
            PromptFind();
            return;
        }

        int start = fromCurrentSelection
            ? Math.Min(Reader.SelectionStart + Math.Max(Reader.SelectionLength, 1), Reader.Text.Length)
            : Reader.SelectionStart;

        int index = Reader.Text.IndexOf(_findText, start, StringComparison.CurrentCultureIgnoreCase);
        if (index < 0 && start > 0)
            index = Reader.Text.IndexOf(_findText, 0, StringComparison.CurrentCultureIgnoreCase);

        SelectSearchResult(index);
    }

    private void FindPrevious()
    {
        if (string.IsNullOrWhiteSpace(_findText))
        {
            PromptFind();
            return;
        }

        int start = Math.Max(Reader.SelectionStart - 1, 0);
        int index = Reader.Text.LastIndexOf(_findText, start, StringComparison.CurrentCultureIgnoreCase);
        if (index < 0 && Reader.Text.Length > 0)
            index = Reader.Text.LastIndexOf(_findText, Reader.Text.Length - 1, StringComparison.CurrentCultureIgnoreCase);

        SelectSearchResult(index);
    }

    private void SelectSearchResult(int index)
    {
        if (index < 0)
        {
            System.Media.SystemSounds.Beep.Play();
            StatusText.Text = $"Not found: {_findText}";
            Reader.Focus();
            return;
        }

        Reader.Focus();
        Reader.Select(index, _findText.Length);
        int line = Reader.GetLineIndexFromCharacterIndex(index);
        if (line >= 0)
            Reader.ScrollToLine(line);
        UpdateStatus();
    }

    private void Reader_SelectionChanged(object sender, RoutedEventArgs e) => UpdateStatus();

    private void UpdateStatus()
    {
        if (Reader.Text.Length == 0)
        {
            StatusText.Text = _currentFile is null ? "Ready. Press Ctrl+O to open a document." : "Document is empty.";
            return;
        }

        int caret = Math.Clamp(Reader.CaretIndex, 0, Reader.Text.Length);
        int line = Reader.GetLineIndexFromCharacterIndex(Math.Min(caret, Math.Max(0, Reader.Text.Length - 1)));
        int totalLines = Math.Max(1, Reader.LineCount);
        int percent = (int)Math.Round((double)caret / Reader.Text.Length * 100.0);
        StatusText.Text = $"Line {Math.Max(1, line + 1)} of {totalLines}; {percent}%";
    }

    private void Exit_Click(object sender, RoutedEventArgs e) => Close();

    private void About_Click(object sender, RoutedEventArgs e)
    {
        MessageBox.Show(this,
            "FlexiReader 0.1.3\n\nAccessibility test build. The document reader is a native read-only multiline WPF TextBox so JAWS can use ordinary caret navigation.",
            "About FlexiReader", MessageBoxButton.OK, MessageBoxImage.Information);
    }
}
