package org.example.linereader;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private LineReaderView readerView;
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
        Button open = findViewById(R.id.openButton);
        Button previous = findViewById(R.id.previousButton);
        Button next = findViewById(R.id.nextButton);

        open.setOnClickListener(v -> openDocument.launch(new String[]{
                "application/pdf",
                "application/epub+zip",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain",
                "text/html",
                "application/octet-stream"
        }));
        previous.setOnClickListener(v -> readerView.previousLine());
        next.setOnClickListener(v -> readerView.nextLine());

        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            loadDocument(intent.getData());
        }
    }

    private void loadDocument(Uri uri) {
        readerView.announceForAccessibility("Loading document");
        executor.submit(() -> {
            try {
                String text = DocumentParser.parse(this, uri);
                runOnUiThread(() -> readerView.setDocumentText(text));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Could not read this document: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    readerView.announceForAccessibility("Could not read this document");
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
