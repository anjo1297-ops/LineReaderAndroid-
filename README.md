# Line Reader Android prototype

Line Reader is an accessibility-first Android document reader intended to emulate desktop screen-reader line navigation.

## Core interaction

Inside the reading area:
- Accessibility Scroll Forward = next visual text line.
- Accessibility Scroll Backward = previous visual text line.
- The newly selected line is announced.
- Previous line and Next line buttons provide an always-available fallback.

With TalkBack, a two-finger vertical scroll on the reading area should invoke the standard accessibility scrolling action on supported configurations. Because TalkBack gesture mappings can vary by device/version, the explicit accessible buttons remain available.

## Formats in this prototype

- PDF: text-based PDFs through PDFBox-Android 2.0.27.0.
- EPUB: reads the EPUB OPF spine and extracts XHTML/HTML text.
- DOCX: extracts paragraphs from word/document.xml.
- TXT: UTF-8 text.
- HTML/HTM: simple HTML-to-text extraction.

Image-only/scanned PDFs do not yet have OCR.

## Build requirements

- Android Studio compatible with Android Gradle Plugin 9.4.
- JDK 17.
- Android SDK 37.
- Gradle 9.6 (Android Studio can create/download the wrapper if required).

## Accessibility design

The custom `LineReaderView` exposes `ACTION_SCROLL_FORWARD` and `ACTION_SCROLL_BACKWARD` and interprets each as exactly one line. Lines are calculated with Android `StaticLayout`, so they correspond to wrapped visual lines for the current screen width rather than arbitrary character counts.

## Important prototype limitations

1. TalkBack gesture behaviour must be tested on real devices. If a two-finger swipe is handled differently on a specific TalkBack version, use the accessible Previous/Next buttons or map TalkBack's scroll action accordingly.
2. EPUB parsing handles ordinary EPUB 2/3 spine-based books but not DRM-protected books.
3. DOCX extraction is text-focused and does not yet expose tables, footnotes, headers, comments, or semantic structure.
4. PDF reading order depends on the PDF's internal text structure. Multi-column and badly tagged PDFs may need layout heuristics.
5. OCR for scanned PDFs is not yet implemented.

## Next features worth adding

- OCR for scanned PDFs.
- Navigation by line, sentence, paragraph, heading and page.
- Search and Go to page/heading.
- Bookmarks and remembered reading position.
- Adjustable speech/line granularity.
- Bluetooth keyboard arrow-key support.
- Optional vibration at page/heading boundaries.

## Automatic APK build with GitHub Actions

This project includes `.github/workflows/build-apk.yml`.

1. Upload/push this project to a GitHub repository.
2. Open the repository's **Actions** tab.
3. Choose **Build Line Reader APK**.
4. Choose **Run workflow**.
5. When the build completes, download the **LineReader-debug-apk** artifact.
6. The artifact contains `app-debug.apk`, which can be installed on an Android device after allowing installation from the relevant source.

The workflow uses Android API 37, Build Tools 36.0.0, Java 17 and Gradle 9.6.0 to match Android Gradle Plugin 9.4.0.
