package org.example.linereader;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private LineReaderView readerView;
    private LinearLayout loadingContainer;
    private TextView loadingStatus;
    private ProgressBar loadingProgress;
    private Button openButton;
    private int lastAnnouncedPercent = -20;
    private boolean documentOpen = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> openDocument = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) loadDocument(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        readerView = findViewById(R.id.readerView);
        loadingContainer = findViewById(R.id.loadingContainer);
        loadingStatus = findViewById(R.id.loadingStatus);
        loadingProgress = findViewById(R.id.loadingProgress);
        openButton = findViewById(R.id.openButton);

        openButton.setOnClickListener(v -> openDocument.launch(new String[]{
                "application/pdf",
                "application/epub+zip",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain",
                "text/html",
                "application/octet-stream"
        }));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (documentOpen) {
                    returnToHome();
                    return;
                }

                // At the FlexiReader home screen, Back keeps the normal Android behaviour
                // and leaves the app.
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            loadDocument(intent.getData());
        }
    }

    private void loadDocument(Uri uri) {
        showLoading("Loading document", 0, 0, true);
        executor.submit(() -> {
            try {
                String text = DocumentParser.parse(this, uri,
                        (message, current, total) -> runOnUiThread(() ->
                                showLoading(message, current, total, false)));

                runOnUiThread(() -> {
                    readerView.setDocumentText(text);
                    documentOpen = true;
                    hideLoading();
                    readerView.announceForAccessibility("Document loaded");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    documentOpen = false;
                    hideLoading();
                    String detail = e.getMessage();
                    if (detail == null || detail.trim().isEmpty()) {
                        detail = e.getClass().getSimpleName();
                    }
                    Toast.makeText(this,
                            "Could not read this document: " + detail,
                            Toast.LENGTH_LONG).show();
                    readerView.announceForAccessibility("Could not read this document");
                });
            }
        });
    }

    private void returnToHome() {
        documentOpen = false;
        readerView.setDocumentText("Open a document to begin.");
        hideLoading();

        // Put TalkBack back on the main action rather than leaving accessibility focus on
        // a virtual document line that no longer exists.
        openButton.post(() -> {
            openButton.requestFocus();
            openButton.sendAccessibilityEvent(
                    android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
            openButton.announceForAccessibility("FlexiReader home. Open document.");
        });
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
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
