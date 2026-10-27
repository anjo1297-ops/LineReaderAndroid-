using System.Windows;

namespace FlexiReader;

public partial class FindDialog : Window
{
    public string SearchText => SearchBox.Text;

    public FindDialog(string initialText)
    {
        InitializeComponent();
        SearchBox.Text = initialText;
        Loaded += (_, _) =>
        {
            SearchBox.Focus();
            SearchBox.SelectAll();
        };
    }

    private void Find_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(SearchBox.Text))
            return;
        DialogResult = true;
    }
}
