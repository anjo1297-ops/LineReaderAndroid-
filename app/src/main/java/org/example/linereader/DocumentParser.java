package org.example.linereader;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class DocumentParser {
    private static final int MIN_EXTRACTED_CHARACTERS_PER_PAGE = 24;
    private static final float OCR_RENDER_SCALE = 1.5f;

    private DocumentParser() {}

    public static String parse(Context context, Uri uri) throws Exception {
        String name = getFileName(context, uri).toLowerCase();
        String mime = context.getContentResolver().getType(uri);

        if (name.endsWith(".pdf") || "application/pdf".equals(mime)) {
            return parsePdf(context, uri);
        }

        byte[] data = readAll(context.getContentResolver(), uri);
        if (name.endsWith(".docx") || (mime != null && mime.contains("wordprocessingml"))) {
            return parseDocx(data);
        }
        if (name.endsWith(".epub") || "application/epub+zip".equals(mime)) {
            return parseEpub(data);
        }
        if (name.endsWith(".html") || name.endsWith(".htm") || "text/html".equals(mime)) {
            return htmlToText(new String(data, StandardCharsets.UTF_8));
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Reads PDFs page by page. Native PDF text is preferred. If a page contains little or no
     * extractable text, that page alone is rendered and passed to ML Kit OCR. The bitmap is then
     * recycled before moving to the next page, keeping memory bounded for large scanned books.
     */
    private static String parsePdf(Context context, Uri uri) throws Exception {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("Cannot open the selected PDF.");

            try (PDDocument doc = PDDocument.load(in)) {
                PDFRenderer renderer = new PDFRenderer(doc);
                TextRecognizer recognizer = TextRecognition.getClient(
                        TextRecognizerOptions.DEFAULT_OPTIONS);
                StringBuilder out = new StringBuilder();

                try {
                    for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
                        String pageText = extractPdfPage(doc, pageIndex);
                        if (hasUsefulText(pageText)) {
                            appendPage(out, normalizePdfPageText(pageText));
                            continue;
                        }

                        Bitmap bitmap = null;
                        try {
                            bitmap = renderer.renderImage(pageIndex, OCR_RENDER_SCALE);
                            InputImage image = InputImage.fromBitmap(bitmap, 0);
                            Text result = Tasks.await(recognizer.process(image));
                            appendPage(out, ocrResultToText(result));
                        } finally {
                            if (bitmap != null && !bitmap.isRecycled()) {
                                bitmap.recycle();
                            }
                        }
                    }
                } finally {
                    recognizer.close();
                }

                String result = out.toString().trim();
                return result.isEmpty()
                        ? "No readable text was found in this PDF."
                        : result;
            }
        }
    }

    private static String extractPdfPage(PDDocument doc, int zeroBasedPage) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(zeroBasedPage + 1);
        stripper.setEndPage(zeroBasedPage + 1);
        return stripper.getText(doc);
    }

    private static boolean hasUsefulText(String text) {
        if (text == null) return false;
        return text.replaceAll("\\s+", "").length() >= MIN_EXTRACTED_CHARACTERS_PER_PAGE;
    }

    private static String normalizePdfPageText(String text) {
        if (text == null) return "";
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        // Preserve explicit blank lines as paragraph separators. Single line breaks remain line
        // breaks, allowing the reader to keep visual-line navigation distinct from paragraphs.
        normalized = normalized.replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return normalized;
    }

    private static String ocrResultToText(Text result) {
        StringBuilder out = new StringBuilder();
        for (Text.TextBlock block : result.getTextBlocks()) {
            if (out.length() > 0) out.append("\n\n");
            List<Text.Line> lines = block.getLines();
            for (int i = 0; i < lines.size(); i++) {
                String value = lines.get(i).getText().trim();
                if (!value.isEmpty()) out.append(value);
                if (i < lines.size() - 1) out.append('\n');
            }
        }
        return out.toString().trim();
    }

    private static void appendPage(StringBuilder out, String pageText) {
        if (pageText == null || pageText.trim().isEmpty()) return;
        if (out.length() > 0) out.append("\n\n");
        out.append(pageText.trim());
    }

    private static String parseDocx(byte[] data) throws Exception {
        Map<String, byte[]> files = unzip(data);
        byte[] xml = files.get("word/document.xml");
        if (xml == null) return "No Word document body was found.";
        return parseWordXml(xml);
    }

    private static String parseWordXml(byte[] xml) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
        StringBuilder out = new StringBuilder();
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "t".equals(parser.getName())) {
                out.append(parser.nextText());
            } else if (event == XmlPullParser.END_TAG && "p".equals(parser.getName())) {
                out.append("\n\n");
            } else if (event == XmlPullParser.START_TAG
                    && ("tab".equals(parser.getName()) || "br".equals(parser.getName()))) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    private static String parseEpub(byte[] data) throws Exception {
        Map<String, byte[]> files = unzip(data);
        String opfPath = null;
        byte[] container = files.get("META-INF/container.xml");
        if (container != null) {
            String c = new String(container, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("full-path\\s*=\\s*[\"']([^\"']+)[\"']")
                    .matcher(c);
            if (m.find()) opfPath = m.group(1);
        }
        if (opfPath == null || !files.containsKey(opfPath)) {
            StringBuilder fallback = new StringBuilder();
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                String k = e.getKey().toLowerCase();
                if (k.endsWith(".xhtml") || k.endsWith(".html") || k.endsWith(".htm")) {
                    if (fallback.length() > 0) fallback.append("\n\n");
                    fallback.append(htmlToText(
                            new String(e.getValue(), StandardCharsets.UTF_8)));
                }
            }
            return fallback.toString();
        }

        String opf = new String(files.get(opfPath), StandardCharsets.UTF_8);
        String base = opfPath.contains("/")
                ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        Map<String, String> manifest = new HashMap<>();
        java.util.regex.Matcher item = java.util.regex.Pattern.compile(
                "<item\\b[^>]*id=[\"']([^\"']+)[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*/?>",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(opf);
        while (item.find()) manifest.put(item.group(1), item.group(2));

        List<String> spine = new ArrayList<>();
        java.util.regex.Matcher ref = java.util.regex.Pattern.compile(
                "<itemref\\b[^>]*idref=[\"']([^\"']+)[\"'][^>]*/?>",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(opf);
        while (ref.find()) spine.add(ref.group(1));

        StringBuilder out = new StringBuilder();
        for (String id : spine) {
            String href = manifest.get(id);
            if (href == null) continue;
            String path = normalizePath(base + href.split("#", 2)[0]);
            byte[] chapter = files.get(path);
            if (chapter != null) {
                if (out.length() > 0) out.append("\n\n");
                out.append(htmlToText(new String(chapter, StandardCharsets.UTF_8)));
            }
        }
        return out.toString();
    }

    private static String normalizePath(String path) {
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        for (String p : path.split("/")) {
            if (p.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else if (!p.equals(".") && !p.isEmpty()) {
                parts.addLast(p);
            }
        }
        return String.join("/", parts);
    }

    private static String htmlToText(String html) {
        return html
                .replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("(?i)</(p|div|h[1-6]|li|blockquote|tr)>", "\n\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static Map<String, byte[]> unzip(byte[] data) throws Exception {
        Map<String, byte[]> map = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buffer)) > 0) out.write(buffer, 0, n);
                map.put(entry.getName(), out.toByteArray());
            }
        }
        return map;
    }

    private static byte[] readAll(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalArgumentException("Cannot open the selected file.");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static String getFileName(Context context, Uri uri) {
        String result = "document";
        try (android.database.Cursor cursor = context.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) result = cursor.getString(i);
            }
        } catch (Exception ignored) {}
        return result == null ? "document" : result;
    }
}
