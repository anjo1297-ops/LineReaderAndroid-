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

    public static final String PAGE_MARKER_PREFIX = "[[[FLEXIREADER_PAGE:";
    public static final String PAGE_MARKER_SUFFIX = "]]]";
    public static final String HEADING_MARKER = "[[[FLEXIREADER_HEADING]]]";

    public interface ProgressListener {
        void onProgress(String message, int current, int total);
    }

    private DocumentParser() {}

    public static String parse(Context context, Uri uri) throws Exception {
        return parse(context, uri, null);
    }

    public static String parse(Context context, Uri uri, ProgressListener listener) throws Exception {
        String name = getFileName(context, uri).toLowerCase();
        String mime = context.getContentResolver().getType(uri);

        if (name.endsWith(".pdf") || "application/pdf".equals(mime)) {
            return parsePdf(context, uri, listener);
        }

        notifyProgress(listener, "Reading document", 0, 0);
        byte[] data = readAll(context.getContentResolver(), uri);
        if (name.endsWith(".docx") || (mime != null && mime.contains("wordprocessingml"))) {
            notifyProgress(listener, "Processing Word document", 0, 0);
            return parseDocx(data);
        }
        if (name.endsWith(".epub") || "application/epub+zip".equals(mime)) {
            notifyProgress(listener, "Processing EPUB", 0, 0);
            return parseEpub(data);
        }
        if (name.endsWith(".html") || name.endsWith(".htm") || "text/html".equals(mime)) {
            return htmlToText(new String(data, StandardCharsets.UTF_8));
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String parsePdf(Context context, Uri uri, ProgressListener listener) throws Exception {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        notifyProgress(listener, "Opening PDF", 0, 0);

        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("Cannot open the selected PDF.");

            try (PDDocument doc = PDDocument.load(in)) {
                int totalPages = doc.getNumberOfPages();
                PDFRenderer renderer = null;
                TextRecognizer recognizer = null;
                StringBuilder out = new StringBuilder();

                try {
                    for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
                        int pageNumber = pageIndex + 1;
                        notifyProgress(listener,
                                "Reading page " + pageNumber + " of " + totalPages,
                                pageNumber, totalPages);

                        appendPageMarker(out, pageNumber);

                        String pageText = "";
                        try {
                            pageText = extractPdfPage(doc, pageIndex);
                        } catch (Exception ignored) {}

                        if (hasUsefulText(pageText)) {
                            appendPageText(out, normalizePdfPageText(pageText));
                            continue;
                        }

                        notifyProgress(listener,
                                "Scanning page " + pageNumber + " of " + totalPages,
                                pageNumber, totalPages);

                        Bitmap bitmap = null;
                        try {
                            if (renderer == null) renderer = new PDFRenderer(doc);
                            if (recognizer == null) {
                                recognizer = TextRecognition.getClient(
                                        TextRecognizerOptions.DEFAULT_OPTIONS);
                            }

                            bitmap = renderer.renderImage(pageIndex, OCR_RENDER_SCALE);
                            InputImage image = InputImage.fromBitmap(bitmap, 0);
                            Text result = Tasks.await(recognizer.process(image));
                            String ocrText = ocrResultToText(result);
                            if (!ocrText.isEmpty()) appendPageText(out, ocrText);
                        } catch (Exception ignored) {
                        } finally {
                            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                        }
                    }
                } finally {
                    if (recognizer != null) recognizer.close();
                }

                String result = out.toString().trim();
                if (result.replaceAll("\\[\\[\\[FLEXIREADER_PAGE:\\d+]]]", "")
                        .trim().isEmpty()) {
                    return PAGE_MARKER_PREFIX + "1" + PAGE_MARKER_SUFFIX
                            + "\nNo readable text was found in this PDF.";
                }
                return result;
            }
        }
    }

    private static void appendPageMarker(StringBuilder out, int pageNumber) {
        if (out.length() > 0) out.append("\n\n");
        out.append(PAGE_MARKER_PREFIX).append(pageNumber).append(PAGE_MARKER_SUFFIX);
    }

    private static void appendPageText(StringBuilder out, String pageText) {
        if (pageText == null || pageText.trim().isEmpty()) return;
        out.append('\n').append(pageText.trim());
    }

    private static String extractPdfPage(PDDocument doc, int zeroBasedPage) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(zeroBasedPage + 1);
        stripper.setEndPage(zeroBasedPage + 1);
        return stripper.getText(doc);
    }

    private static boolean hasUsefulText(String text) {
        return text != null
                && text.replaceAll("\\s+", "").length() >= MIN_EXTRACTED_CHARACTERS_PER_PAGE;
    }

    private static void notifyProgress(ProgressListener listener,
                                       String message, int current, int total) {
        if (listener != null) listener.onProgress(message, current, total);
    }

    private static String normalizePdfPageText(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n");
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
        StringBuilder paragraph = new StringBuilder();
        boolean paragraphHeading = false;
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "p".equals(parser.getName())) {
                paragraph.setLength(0);
                paragraphHeading = false;
            } else if (event == XmlPullParser.START_TAG && "pStyle".equals(parser.getName())) {
                String style = parser.getAttributeValue(null, "val");
                if (style == null) {
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        if ("val".equals(parser.getAttributeName(i))) {
                            style = parser.getAttributeValue(i);
                            break;
                        }
                    }
                }
                if (style != null && style.toLowerCase().startsWith("heading")) {
                    paragraphHeading = true;
                }
            } else if (event == XmlPullParser.START_TAG && "t".equals(parser.getName())) {
                paragraph.append(parser.nextText());
            } else if (event == XmlPullParser.START_TAG
                    && ("tab".equals(parser.getName()) || "br".equals(parser.getName()))) {
                paragraph.append(' ');
            } else if (event == XmlPullParser.END_TAG && "p".equals(parser.getName())) {
                String value = paragraph.toString().trim();
                if (!value.isEmpty()) {
                    if (out.length() > 0) out.append("\n\n");
                    if (paragraphHeading) out.append(HEADING_MARKER).append('\n');
                    out.append(value);
                }
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
                    fallback.append(htmlToText(new String(e.getValue(), StandardCharsets.UTF_8)));
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
                .replaceAll("(?i)<h[1-6][^>]*>", HEADING_MARKER + "\n")
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

    public static String getFileName(Context context, Uri uri) {
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
