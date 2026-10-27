package org.example.linereader;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "flexireader_state";
    private static final int MAX_RECENTS = 5;

    private LineReaderView readerView;
    private LinearLayout homeContainer;
    private LinearLayout recentContainer;
    private LinearLayout loadingContainer;
    private TextView loadingStatus;
    private ProgressBar loadingProgress;
    private Button openButton;
    private Button searchButton;
    private SharedPreferences prefs;

    private int lastAnnouncedPercent = -20;
    private boolean documentOpen = false;
    private Uri currentDocumentUri;
    private String currentDocumentName;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> openDocument = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    persistReadPermission(uri);
                    loadDocument(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        readerView = findViewById(R.id.readerView);
        homeContainer = findViewById(R.id.homeContainer);
        recentContainer = findViewById(R.id.recentContainer);
        loadingContainer = findViewById(R.id.loadingContainer);
        loadingStatus = findViewById(R.id.loadingStatus);
        loadingProgress = findViewById(R.id.loadingProgress);
        openButton = findViewById(R.id.openButton);
        searchButton = findViewById(R.id.searchButton);

        openButton.setOnClickListener(v -> openDocument.launch(new String[]{
                "application/pdf",
                "application/epub+zip",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain",
                "text/html",
                "application/octet-stream"
        }));
        searchButton.setOnClickListener(v -> showSearchDialog());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (documentOpen) {
                    returnToHome();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        showHome(false);
        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            loadDocument(intent.getData());
        }
    }

    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private void loadDocument(Uri uri) {
        saveCurrentPosition();
        showLoading("Loading document", 0, 0, true);
        executor.submit(() -> {
            try {
                String name = DocumentParser.getFileName(this, uri);
                String text = DocumentParser.parse(this, uri,
                        (message, current, total) -> runOnUiThread(() ->
                                showLoading(message, current, total, false)));

                int savedPosition = prefs.getInt(positionKey(uri), 0);
                runOnUiThread(() -> {
                    currentDocumentUri = uri;
                    currentDocumentName = name;
                    readerView.setDocumentText(text);
                    readerView.setCurrentLineIndex(savedPosition);
                    documentOpen = true;
                    int progress = currentProgressPercent();
                    prefs.edit().putInt(progressKey(uri), progress).apply();
                    addRecent(uri, name);
                    setDocumentMode(true);
                    hideLoading();

                    String page = readerView.getCurrentPageDescription();
                    String announcement = savedPosition > 0
                            ? "Document loaded. Resumed at " + progress + " percent"
                            : "Document loaded";
                    if (!page.isEmpty()) announcement += ". " + page;
                    readerView.announceForAccessibility(announcement);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    documentOpen = false;
                    currentDocumentUri = null;
                    currentDocumentName = null;
                    hideLoading();
                    showHome(false);
                    String detail = e.getMessage();
                    if (detail == null || detail.trim().isEmpty()) {
                        detail = e.getClass().getSimpleName();
                    }
                    Toast.makeText(this,
                            "Could not read this document: " + detail,
                            Toast.LENGTH_LONG).show();
                    openButton.announceForAccessibility("Could not read this document");
                });
            }
        });
    }

    private void setDocumentMode(boolean reading) {
        homeContainer.setVisibility(reading ? View.GONE : View.VISIBLE);
        searchButton.setVisibility(reading ? View.VISIBLE : View.GONE);
        readerView.setVisibility(View.VISIBLE);
    }

    private void showHome(boolean announce) {
        documentOpen = false;
        setDocumentMode(false);
        readerView.setDocumentText("Open a document to begin.");
        rebuildRecentButtons();
        if (announce) {
            openButton.post(() -> {
                openButton.requestFocus();
                openButton.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
                openButton.announceForAccessibility("FlexiReader home. Open document.");
            });
        }
    }

    private void returnToHome() {
        saveCurrentPosition();
        currentDocumentUri = null;
        currentDocumentName = null;
        hideLoading();
        showHome(true);
    }

    private void saveCurrentPosition() {
        if (!documentOpen || currentDocumentUri == null) return;
        int position = readerView.getCurrentLineIndex();
        int progress = currentProgressPercent();
        prefs.edit()
                .putInt(positionKey(currentDocumentUri), position)
                .putInt(progressKey(currentDocumentUri), progress)
                .apply();
    }

    private int currentProgressPercent() {
        int position = readerView.getCurrentLineIndex();
        int totalLines = getReaderLineCount();
        if (totalLines <= 1) return position > 0 ? 100 : 0;
        return Math.max(0, Math.min(100,
                Math.round((position * 100f) / (totalLines - 1))));
    }

    private int getReaderLineCount() {
        AccessibilityNodeInfo info = readerView.createAccessibilityNodeInfo();
        try {
            CharSequence description = info.getContentDescription();
            if (description == null) return 0;
            String value = description.toString();
            String marker = "FlexiReader document. ";
            int start = value.indexOf(marker);
            int end = value.indexOf(" lines", start + marker.length());
            if (start >= 0 && end > start) {
                String number = value.substring(start + marker.length(), end).trim();
                return Integer.parseInt(number);
            }
        } catch (Exception ignored) {
        } finally {
            info.recycle();
        }
        return 0;
    }

    private String positionKey(Uri uri) {
        return "position|" + uri.toString();
    }

    private String progressKey(Uri uri) {
        return "progress|" + uri.toString();
    }

    private void addRecent(Uri uri, String name) {
        String uriValue = uri.toString();
        List<String> uris = new ArrayList<>();
        List<String> names = new ArrayList<>();
        uris.add(uriValue);
        names.add(name);

        for (int i = 0; i < MAX_RECENTS; i++) {
            String oldUri = prefs.getString("recent_uri_" + i, null);
            String oldName = prefs.getString("recent_name_" + i, null);
            if (oldUri != null && !oldUri.equals(uriValue)) {
                uris.add(oldUri);
                names.add(oldName == null ? "Document" : oldName);
            }
            if (uris.size() >= MAX_RECENTS) break;
        }

        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < MAX_RECENTS; i++) {
            if (i < uris.size()) {
                editor.putString("recent_uri_" + i, uris.get(i));
                editor.putString("recent_name_" + i, names.get(i));
            } else {
                editor.remove("recent_uri_" + i);
                editor.remove("recent_name_" + i);
            }
        }
        editor.apply();
        rebuildRecentButtons();
    }

    private void rebuildRecentButtons() {
        if (recentContainer == null) return;
        recentContainer.removeAllViews();
        for (int i = 0; i < MAX_RECENTS; i++) {
            String uriValue = prefs.getString("recent_uri_" + i, null);
            if (uriValue == null) continue;
            String name = prefs.getString("recent_name_" + i, "Document");
            Uri uri = Uri.parse(uriValue);
            int position = prefs.getInt(positionKey(uri), 0);
            int progress = prefs.getInt(progressKey(uri), position > 0 ? 1 : 0);

            Button recent = new Button(this);
            String label = name;
            if (position > 0) label += ", resume at " + progress + " percent";
            recent.setText(label);
            recent.setContentDescription(label);
            recent.setOnClickListener(v -> loadDocument(uri));
            recentContainer.addView(recent, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void showSearchDialog() {
        if (!documentOpen) return;
        EditText input = new EditText(this);
        input.setHint(R.string.search_hint);
        input.setSingleLine(true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.search_document)
                .setView(input)
                .setPositiveButton(R.string.find_next, (d, which) ->
                        runSearch(input.getText().toString(), true))
                .setNeutralButton(R.string.find_previous, (d, which) ->
                        runSearch(input.getText().toString(), false))
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> input.requestFocus());
        dialog.show();
    }

    private void runSearch(String query, boolean forward) {
        if (query == null || query.trim().isEmpty()) {
            Toast.makeText(this, "Enter text to search for", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean found = forward ? readerView.findNext(query) : readerView.findPrevious(query);
        if (!found) {
            Toast.makeText(this, "Text not found", Toast.LENGTH_SHORT).show();
            readerView.announceForAccessibility("Text not found");
        }
    }

    private void showLoading(String message, int current, int total, boolean forceAnnouncement) {
        loadingContainer.setVisibility(View.VISIBLE);
        openButton.setEnabled(false);
        loadingStatus.setText(message);
        loadingProgress.setContentDescription(message);

        if (total > 0) {
            loadingProgress.setIndeterminate(false);
            int percent = Math.max(0, Math.min(100,
                    Math.round((current * 100f) / total)));
            loadingProgress.setProgress(percent);
            if (forceAnnouncement || percent >= lastAnnouncedPercent + 20
                    || message.toLowerCase().startsWith("scanning")) {
                loadingStatus.announceForAccessibility(message);
                lastAnnouncedPercent = percent;
            }
        } else {
            loadingProgress.setIndeterminate(true);
            if (forceAnnouncement) loadingStatus.announceForAccessibility(message);
        }
    }

    private void hideLoading() {
        loadingContainer.setVisibility(View.GONE);
        openButton.setEnabled(true);
        lastAnnouncedPercent = -20;
    }

    @Override
    protected void onPause() {
        saveCurrentPosition();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saveCurrentPosition();
        executor.shutdownNow();
        super.onDestroy();
    }
}
