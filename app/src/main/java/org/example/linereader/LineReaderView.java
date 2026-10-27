package org.example.linereader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LineReaderView extends View {
    private static final int HOST_VIEW_ID = -1;
    private static final int ACTION_NEXT_SENTENCE = 0x01020001;
    private static final int ACTION_PREVIOUS_SENTENCE = 0x01020002;
    private static final int ACTION_NEXT_HEADING = 0x01020003;
    private static final int ACTION_PREVIOUS_HEADING = 0x01020004;
    private static final int ACTION_NEXT_PARAGRAPH = 0x01020005;
    private static final int ACTION_PREVIOUS_PARAGRAPH = 0x01020006;
    private static final int ACTION_NEXT_PAGE = 0x01020007;
    private static final int ACTION_PREVIOUS_PAGE = 0x01020008;
    private static final int ACTION_READING_POSITION = 0x01020009;
    private static final int ACTION_ADD_BOOKMARK = 0x0102000A;
    private static final int ACCESSIBILITY_BUFFER_LINES = 2;

    public interface ReaderActionListener {
        void onAddBookmark(int lineIndex, String lineText);
    }

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> lines = new ArrayList<>();
    private final List<Integer> paragraphStarts = new ArrayList<>();
    private final List<Integer> sentenceStarts = new ArrayList<>();
    private final List<Integer> headingStarts = new ArrayList<>();
    private final List<Integer> pageStarts = new ArrayList<>();
    private final List<Integer> pageNumbers = new ArrayList<>();
    private final LineNodeProvider nodeProvider = new LineNodeProvider();

    private String documentText = "Open a document to begin.";
    private int lineIndex = 0;
    private int accessibilityFocusedLine = -1;
    private int firstVisibleLine = 0;
    private float lastTouchY;
    private float dragRemainder;
    private ReaderActionListener actionListener;

    public LineReaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        textPaint.setTextSize(48f);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setColor(Color.BLACK);
        setBackgroundColor(Color.WHITE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(false);
        setFocusableInTouchMode(false);
        rebuildLines();
    }

    public void setReaderActionListener(ReaderActionListener listener) {
        actionListener = listener;
    }

    public void setReaderTextSize(float px) {
        textPaint.setTextSize(Math.max(32f, Math.min(px, 72f)));
        rebuildLines();
    }

    public void setDarkReadingTheme(boolean dark) {
        textPaint.setColor(dark ? Color.WHITE : Color.BLACK);
        setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
        invalidate();
    }

    public void setDocumentText(String text) {
        documentText = (text == null || text.trim().isEmpty())
                ? "No readable text was found." : text;
        lineIndex = 0;
        accessibilityFocusedLine = -1;
        firstVisibleLine = 0;
        rebuildLines();
        invalidate();
    }

    public int getCurrentLineIndex() { return lineIndex; }
    public int getLineCount() { return lines.size(); }
    public String getCurrentLineText() {
        return lines.isEmpty() ? "" : lines.get(Math.max(0, Math.min(lineIndex, lines.size() - 1)));
    }

    public void setCurrentLineIndex(int index) {
        if (lines.isEmpty()) return;
        focusVirtualLine(Math.max(0, Math.min(index, lines.size() - 1)));
    }

    public String getCurrentPageDescription() {
        if (pageStarts.isEmpty()) return "";
        int slot = currentPageSlot();
        return "Page " + pageNumbers.get(slot) + " of " + pageNumbers.get(pageNumbers.size() - 1);
    }

    public String getReadingPositionDescription() {
        int percent;
        if (lines.size() <= 1) percent = lineIndex > 0 ? 100 : 0;
        else percent = Math.max(0, Math.min(100,
                Math.round((lineIndex * 100f) / (lines.size() - 1))));
        String value = percent + " percent through document";
        String page = getCurrentPageDescription();
        if (!page.isEmpty()) value += ". " + page;
        return value;
    }

    public boolean findNext(String query) { return find(query, true); }
    public boolean findPrevious(String query) { return find(query, false); }

    private boolean find(String query, boolean forward) {
        if (query == null || query.trim().isEmpty() || lines.isEmpty()) return false;
        String needle = query.toLowerCase(Locale.ROOT);
        if (forward) {
            for (int i = lineIndex + 1; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) { focusVirtualLine(i); return true; }
            }
            for (int i = 0; i <= lineIndex; i++) {
                if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) { focusVirtualLine(i); return true; }
            }
        } else {
            for (int i = lineIndex - 1; i >= 0; i--) {
                if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) { focusVirtualLine(i); return true; }
            }
            for (int i = lines.size() - 1; i >= lineIndex; i--) {
                if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) { focusVirtualLine(i); return true; }
            }
        }
        return false;
    }

    private float lineHeight() {
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        return Math.max(1f, fm.bottom - fm.top);
    }

    private int visibleLineCount() {
        int usableHeight = Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom());
        return Math.max(1, (int) Math.floor(usableHeight / lineHeight()));
    }

    private int lastVisibleLineExclusive() {
        return Math.min(lines.size(), firstVisibleLine + visibleLineCount());
    }

    private void nextLine() { moveToLine(Math.min(lineIndex + 1, lines.size() - 1)); }
    private void previousLine() { moveToLine(Math.max(lineIndex - 1, 0)); }

    private void nextPage() {
        if (!pageStarts.isEmpty()) {
            int slot = currentPageSlot();
            if (slot < pageStarts.size() - 1) {
                int target = pageStarts.get(slot + 1);
                while (slot + 1 < pageStarts.size() - 1 && target <= lineIndex) {
                    slot++;
                    target = pageStarts.get(slot + 1);
                }
                moveToLine(Math.min(target, lines.size() - 1));
                announcePage();
            }
            return;
        }
        moveToLine(Math.min(lineIndex + visibleLineCount(), lines.size() - 1));
    }

    private void previousPage() {
        if (!pageStarts.isEmpty()) {
            int slot = currentPageSlot();
            if (slot > 0) {
                moveToLine(Math.max(0, pageStarts.get(slot - 1)));
                announcePage();
            }
            return;
        }
        moveToLine(Math.max(lineIndex - visibleLineCount(), 0));
    }

    private void announcePage() {
        String description = getCurrentPageDescription();
        if (!description.isEmpty()) announceForAccessibility(description);
    }

    private int currentPageSlot() {
        int slot = 0;
        for (int i = 0; i < pageStarts.size(); i++) {
            if (pageStarts.get(i) <= lineIndex) slot = i; else break;
        }
        return slot;
    }

    private void nextParagraph() { moveToBoundary(paragraphStarts, true); }
    private void previousParagraph() { moveToBoundary(paragraphStarts, false); }
    private void nextSentence() { moveToBoundary(sentenceStarts, true); }
    private void previousSentence() { moveToBoundary(sentenceStarts, false); }
    private void nextHeading() { moveToBoundary(headingStarts, true); }
    private void previousHeading() { moveToBoundary(headingStarts, false); }

    private void moveToBoundary(List<Integer> boundaries, boolean forward) {
        if (boundaries.isEmpty()) return;
        if (forward) {
            for (int value : boundaries) if (value > lineIndex) { moveToLine(value); return; }
            moveToLine(lines.size() - 1);
        } else {
            for (int i = boundaries.size() - 1; i >= 0; i--) {
                int value = boundaries.get(i);
                if (value < lineIndex) { moveToLine(value); return; }
            }
            moveToLine(0);
        }
    }

    private void moveToLine(int newIndex) {
        if (lines.isEmpty()) return;
        newIndex = Math.max(0, Math.min(newIndex, lines.size() - 1));
        if (newIndex == lineIndex && accessibilityFocusedLine == newIndex) return;
        focusVirtualLine(newIndex);
    }

    private void focusVirtualLine(int newIndex) {
        if (newIndex < 0 || newIndex >= lines.size()) return;
        int oldFocus = accessibilityFocusedLine;
        if (oldFocus >= 0 && oldFocus != newIndex) {
            sendVirtualEvent(oldFocus, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
        }
        boolean viewportChanged = ensureLineVisible(newIndex);
        accessibilityFocusedLine = newIndex;
        lineIndex = newIndex;
        invalidate();
        if (viewportChanged) sendHostContentChanged();
        sendVirtualEvent(newIndex, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    }

    private boolean ensureLineVisible(int target) {
        int count = visibleLineCount();
        int oldFirst = firstVisibleLine;
        if (target < firstVisibleLine) firstVisibleLine = target;
        else if (target >= firstVisibleLine + count) firstVisibleLine = target - count + 1;
        clampFirstVisibleLine();
        return oldFirst != firstVisibleLine;
    }

    private void clampFirstVisibleLine() {
        int maxFirst = Math.max(0, lines.size() - visibleLineCount());
        firstVisibleLine = Math.max(0, Math.min(firstVisibleLine, maxFirst));
    }

    private void scrollByLines(int delta) {
        if (delta == 0 || lines.isEmpty()) return;
        int oldFirst = firstVisibleLine;
        firstVisibleLine += delta;
        clampFirstVisibleLine();
        if (oldFirst != firstVisibleLine) {
            lineIndex = Math.max(firstVisibleLine, Math.min(lineIndex, lastVisibleLineExclusive() - 1));
            invalidate();
            sendHostContentChanged();
        }
    }

    private void rebuildLines() {
        lines.clear(); paragraphStarts.clear(); sentenceStarts.clear(); headingStarts.clear(); pageStarts.clear(); pageNumbers.clear();
        int width = Math.max(320, getWidth() - getPaddingLeft() - getPaddingRight());
        String normalized = documentText.replace("\r\n", "\n").replace('\r', '\n').trim();
        String[] paragraphs = normalized.split("\\n\\s*\\n+");

        for (String paragraph : paragraphs) {
            String p = paragraph.trim();
            if (p.isEmpty()) continue;
            String[] sourceLines = p.split("\\n");
            int firstContent = 0;
            while (firstContent < sourceLines.length && isPageMarker(sourceLines[firstContent].trim())) {
                int pageNumber = parsePageMarker(sourceLines[firstContent].trim());
                if (pageNumber > 0) { pageStarts.add(lines.size()); pageNumbers.add(pageNumber); }
                firstContent++;
            }
            boolean structuredHeading = false;
            if (firstContent < sourceLines.length
                    && DocumentParser.HEADING_MARKER.equals(sourceLines[firstContent].trim())) {
                structuredHeading = true;
                firstContent++;
            }
            if (firstContent >= sourceLines.length) continue;
            int paragraphStart = lines.size();
            paragraphStarts.add(paragraphStart); sentenceStarts.add(paragraphStart);
            StringBuilder headingCandidate = new StringBuilder();
            for (int i = firstContent; i < sourceLines.length; i++) {
                if (headingCandidate.length() > 0) headingCandidate.append(' ');
                headingCandidate.append(sourceLines[i].trim());
            }
            if (structuredHeading || looksLikeHeading(headingCandidate.toString())) headingStarts.add(paragraphStart);

            for (int s = firstContent; s < sourceLines.length; s++) {
                String source = sourceLines[s].trim();
                if (source.isEmpty() || isPageMarker(source)
                        || DocumentParser.HEADING_MARKER.equals(source)) continue;
                StaticLayout layout = StaticLayout.Builder.obtain(source, 0, source.length(), textPaint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
                for (int i = 0; i < layout.getLineCount(); i++) {
                    String line = source.substring(layout.getLineStart(i), layout.getLineEnd(i)).trim();
                    if (!line.isEmpty()) {
                        lines.add(line);
                        if (endsSentence(line)) {
                            int next = lines.size();
                            if (!sentenceStarts.contains(next)) sentenceStarts.add(next);
                        }
                    }
                }
            }
        }

        if (lines.isEmpty()) { lines.add("No readable text was found."); paragraphStarts.add(0); sentenceStarts.add(0); }
        while (!sentenceStarts.isEmpty() && sentenceStarts.get(sentenceStarts.size() - 1) >= lines.size()) {
            sentenceStarts.remove(sentenceStarts.size() - 1);
        }
        if (lineIndex >= lines.size()) lineIndex = lines.size() - 1;
        clampFirstVisibleLine();
        ensureLineVisible(lineIndex);
        invalidate();
    }

    private boolean isPageMarker(String value) {
        return value.startsWith(DocumentParser.PAGE_MARKER_PREFIX) && value.endsWith(DocumentParser.PAGE_MARKER_SUFFIX);
    }

    private int parsePageMarker(String value) {
        try {
            String number = value.substring(DocumentParser.PAGE_MARKER_PREFIX.length(), value.length() - DocumentParser.PAGE_MARKER_SUFFIX.length());
            return Integer.parseInt(number);
        } catch (Exception e) { return -1; }
    }

    private boolean endsSentence(String line) {
        String value = line.trim();
        if (value.isEmpty()) return false;
        char last = value.charAt(value.length() - 1);
        return last == '.' || last == '?' || last == '!';
    }

    private boolean looksLikeHeading(String paragraph) {
        String p = paragraph.trim();
        if (p.length() > 90 || p.endsWith(".") || p.endsWith("?") || p.endsWith("!")) return false;
        return p.split("\\s+").length <= 12;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) rebuildLines();
        else { clampFirstVisibleLine(); ensureLineVisible(lineIndex); }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lines.isEmpty()) return;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float height = lineHeight();
        float baselineOffset = -fm.top;
        float x = getPaddingLeft();
        int end = lastVisibleLineExclusive();
        for (int i = firstVisibleLine; i < end; i++) {
            float top = getPaddingTop() + (i - firstVisibleLine) * height;
            canvas.drawText(lines.get(i), x, top + baselineOffset, textPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (lines.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchY = event.getY(); dragRemainder = 0f; return true;
            case MotionEvent.ACTION_MOVE:
                float delta = lastTouchY - event.getY();
                lastTouchY = event.getY();
                dragRemainder += delta;
                int lineDelta = (int) (dragRemainder / lineHeight());
                if (lineDelta != 0) {
                    scrollByLines(lineDelta);
                    dragRemainder -= lineDelta * lineHeight();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return nodeProvider; }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.view.ViewGroup");
        info.setPackageName(getContext().getPackageName());
        info.setContentDescription(null);
        info.setFocusable(false);
        info.setScreenReaderFocusable(false);
        info.setScrollable(false);
        int start = Math.max(0, firstVisibleLine - ACCESSIBILITY_BUFFER_LINES);
        int end = Math.min(lines.size(), lastVisibleLineExclusive() + ACCESSIBILITY_BUFFER_LINES);
        for (int i = start; i < end; i++) info.addChild(this, i);
    }

    private AccessibilityNodeInfo createHostNode() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(this);
        onInitializeAccessibilityNodeInfo(info);
        return info;
    }

    private Rect lineBoundsInParent(int virtualId) {
        float height = lineHeight();
        int top = Math.round(getPaddingTop() + (virtualId - firstVisibleLine) * height);
        int bottom = Math.round(top + height);
        return new Rect(getPaddingLeft(), top, Math.max(getPaddingLeft() + 1, getWidth() - getPaddingRight()), bottom);
    }

    private AccessibilityNodeInfo createLineNode(int virtualId) {
        if (virtualId < 0 || virtualId >= lines.size()) return null;
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSource(this, virtualId); info.setParent(this); info.setPackageName(getContext().getPackageName());
        info.setClassName("android.widget.TextView");
        info.setText(lines.get(virtualId)); info.setContentDescription(lines.get(virtualId));
        info.setFocusable(true); info.setEnabled(isEnabled());
        Rect parentBounds = lineBoundsInParent(virtualId);
        info.setBoundsInParent(parentBounds);
        int[] location = new int[2]; getLocationOnScreen(location);
        Rect screenBounds = new Rect(parentBounds); screenBounds.offset(location[0], location[1]);
        info.setBoundsInScreen(screenBounds);
        info.setVisibleToUser(isShown());

        boolean focused = virtualId == accessibilityFocusedLine;
        info.setAccessibilityFocused(focused);
        info.addAction(focused ? AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        info.setMovementGranularities(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.setHeading(headingStarts.contains(virtualId));

        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_READING_POSITION, "Reading position"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_ADD_BOOKMARK, "Add bookmark"));
        if (virtualId < lines.size() - 1) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_NEXT_PARAGRAPH, "Next paragraph"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_NEXT_SENTENCE, "Next sentence"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_NEXT_PAGE, pageStarts.isEmpty() ? "Next page" : "Next PDF page"));
        }
        if (virtualId > 0) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_PREVIOUS_PARAGRAPH, "Previous paragraph"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_PREVIOUS_SENTENCE, "Previous sentence"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_PREVIOUS_PAGE, pageStarts.isEmpty() ? "Previous page" : "Previous PDF page"));
        }
        if (!headingStarts.isEmpty()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_NEXT_HEADING, "Next heading"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_PREVIOUS_HEADING, "Previous heading"));
        }
        return info;
    }

    private boolean performMovementGranularity(Bundle arguments, boolean forward) {
        int granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE;
        if (arguments != null) {
            granularity = arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE);
        }
        if (granularity == AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH) {
            if (forward) nextParagraph(); else previousParagraph(); return true;
        }
        if (granularity == AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE) {
            if (forward) nextLine(); else previousLine(); return true;
        }
        return false;
    }

    private void sendVirtualEvent(int virtualId, int eventType) {
        if (virtualId < 0 || virtualId >= lines.size() || !isShown()) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
        event.setPackageName(getContext().getPackageName()); event.setClassName("android.widget.TextView");
        event.setSource(this, virtualId); event.getText().add(lines.get(virtualId)); event.setContentDescription(lines.get(virtualId));
        ViewParent parent = getParent(); if (parent != null) parent.requestSendAccessibilityEvent(this, event);
    }

    private void sendHostContentChanged() {
        if (!isShown()) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setPackageName(getContext().getPackageName()); event.setClassName("android.view.ViewGroup");
        event.setSource(this); event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        ViewParent parent = getParent(); if (parent != null) parent.requestSendAccessibilityEvent(this, event);
    }

    private final class LineNodeProvider extends AccessibilityNodeProvider {
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            return virtualViewId == HOST_VIEW_ID ? createHostNode() : createLineNode(virtualViewId);
        }
        @Override public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            if (virtualViewId == HOST_VIEW_ID) return LineReaderView.this.performAccessibilityAction(action, arguments);
            if (virtualViewId < 0 || virtualViewId >= lines.size()) return false;
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) { focusVirtualLine(virtualViewId); return true; }
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocusedLine == virtualViewId) {
                    accessibilityFocusedLine = -1;
                    sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                }
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY) return performMovementGranularity(arguments, true);
            if (action == AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY) return performMovementGranularity(arguments, false);
            if (action == ACTION_READING_POSITION) { announceForAccessibility(getReadingPositionDescription()); return true; }
            if (action == ACTION_ADD_BOOKMARK) {
                focusVirtualLine(virtualViewId);
                if (actionListener != null) actionListener.onAddBookmark(virtualViewId, lines.get(virtualViewId));
                return true;
            }
            if (action == ACTION_NEXT_PARAGRAPH) { nextParagraph(); return true; }
            if (action == ACTION_PREVIOUS_PARAGRAPH) { previousParagraph(); return true; }
            if (action == ACTION_NEXT_SENTENCE) { nextSentence(); return true; }
            if (action == ACTION_PREVIOUS_SENTENCE) { previousSentence(); return true; }
            if (action == ACTION_NEXT_HEADING) { nextHeading(); return true; }
            if (action == ACTION_PREVIOUS_HEADING) { previousHeading(); return true; }
            if (action == ACTION_NEXT_PAGE) { nextPage(); return true; }
            if (action == ACTION_PREVIOUS_PAGE) { previousPage(); return true; }
            return false;
        }
        @Override public AccessibilityNodeInfo findFocus(int focus) {
            return focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && accessibilityFocusedLine >= 0
                    ? createLineNode(accessibilityFocusedLine) : null;
        }
    }
}
