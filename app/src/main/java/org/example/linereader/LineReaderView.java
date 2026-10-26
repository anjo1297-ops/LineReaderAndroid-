package org.example.linereader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessibility-first reading surface for FlexiReader.
 *
 * Every visual line is a virtual accessibility child so normal TalkBack next/previous-item
 * navigation remains line-by-line. Larger navigation units are exposed through standard
 * movement granularities and contextual accessibility actions instead of intercepting
 * TalkBack's two-finger scrolling gestures.
 */
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

    private static final int ACCESSIBILITY_WINDOW_SIZE = 401;
    private static final int ACCESSIBILITY_WINDOW_MARGIN = 40;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> lines = new ArrayList<>();
    private final List<Integer> paragraphStarts = new ArrayList<>();
    private final List<Integer> sentenceStarts = new ArrayList<>();
    private final List<Integer> headingStarts = new ArrayList<>();
    private final LineNodeProvider nodeProvider = new LineNodeProvider();

    private String documentText = "Open a document to begin.";
    private int lineIndex = 0;
    private int accessibilityFocusedLine = -1;
    private int windowStart = 0;
    private int windowEndExclusive = 0;
    private StaticLayout currentLayout;

    public LineReaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        textPaint.setTextSize(48f);
        textPaint.setTypeface(Typeface.DEFAULT);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(false);
        setFocusableInTouchMode(false);
        rebuildLines();
    }

    public void setDocumentText(String text) {
        documentText = (text == null || text.trim().isEmpty())
                ? "No readable text was found." : text;
        lineIndex = 0;
        accessibilityFocusedLine = -1;
        rebuildLines();
        resetAccessibilityWindow(0);
        invalidate();
    }

    private void nextLine() { moveToLine(Math.min(lineIndex + 1, lines.size() - 1)); }
    private void previousLine() { moveToLine(Math.max(lineIndex - 1, 0)); }
    private void nextPage() { moveToLine(Math.min(lineIndex + linesPerPage(), lines.size() - 1)); }
    private void previousPage() { moveToLine(Math.max(lineIndex - linesPerPage(), 0)); }
    private void nextParagraph() { moveToBoundary(paragraphStarts, true); }
    private void previousParagraph() { moveToBoundary(paragraphStarts, false); }
    private void nextSentence() { moveToBoundary(sentenceStarts, true); }
    private void previousSentence() { moveToBoundary(sentenceStarts, false); }
    private void nextHeading() { moveToBoundary(headingStarts, true); }
    private void previousHeading() { moveToBoundary(headingStarts, false); }

    private void moveToBoundary(List<Integer> boundaries, boolean forward) {
        if (boundaries.isEmpty()) return;
        if (forward) {
            for (int value : boundaries) {
                if (value > lineIndex) {
                    moveToLine(value);
                    return;
                }
            }
            moveToLine(lines.size() - 1);
        } else {
            for (int i = boundaries.size() - 1; i >= 0; i--) {
                int value = boundaries.get(i);
                if (value < lineIndex) {
                    moveToLine(value);
                    return;
                }
            }
            moveToLine(0);
        }
    }

    private int linesPerPage() {
        float lineHeight = Math.max(1f,
                textPaint.getFontMetrics().bottom - textPaint.getFontMetrics().top);
        int usableHeight = Math.max(1,
                getHeight() - getPaddingTop() - getPaddingBottom());
        return Math.max(5, (int) (usableHeight / lineHeight));
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
            sendVirtualEvent(oldFocus,
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
        }

        boolean windowChanged = ensureAccessibilityWindowContains(newIndex);
        accessibilityFocusedLine = newIndex;
        lineIndex = newIndex;
        rebuildCurrentLayout();
        invalidate();

        if (windowChanged) sendHostContentChanged();
        sendVirtualEvent(newIndex, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    }

    private String currentLine() {
        return lines.isEmpty() ? "" : lines.get(
                Math.max(0, Math.min(lineIndex, lines.size() - 1)));
    }

    private void rebuildLines() {
        lines.clear();
        paragraphStarts.clear();
        sentenceStarts.clear();
        headingStarts.clear();

        int width = Math.max(320, getWidth() - getPaddingLeft() - getPaddingRight());
        String normalized = documentText.replace("\r\n", "\n").replace('\r', '\n').trim();
        String[] paragraphs = normalized.split("\\n\\s*\\n+");

        for (String paragraph : paragraphs) {
            String p = paragraph.trim();
            if (p.isEmpty()) continue;

            int paragraphStart = lines.size();
            paragraphStarts.add(paragraphStart);
            sentenceStarts.add(paragraphStart);
            if (looksLikeHeading(p.replace('\n', ' '))) headingStarts.add(paragraphStart);

            for (String sourceLine : p.split("\\n")) {
                String source = sourceLine.trim();
                if (source.isEmpty()) continue;

                StaticLayout layout = StaticLayout.Builder.obtain(
                                source, 0, source.length(), textPaint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .build();

                for (int i = 0; i < layout.getLineCount(); i++) {
                    String line = source.substring(
                            layout.getLineStart(i), layout.getLineEnd(i)).trim();
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

        if (lines.isEmpty()) {
            lines.add("No readable text was found.");
            paragraphStarts.add(0);
            sentenceStarts.add(0);
        }

        while (!sentenceStarts.isEmpty()
                && sentenceStarts.get(sentenceStarts.size() - 1) >= lines.size()) {
            sentenceStarts.remove(sentenceStarts.size() - 1);
        }

        if (lineIndex >= lines.size()) lineIndex = lines.size() - 1;
        resetAccessibilityWindow(lineIndex);
        rebuildCurrentLayout();
    }

    private boolean endsSentence(String line) {
        String value = line.trim();
        if (value.isEmpty()) return false;
        char last = value.charAt(value.length() - 1);
        return last == '.' || last == '?' || last == '!';
    }

    private boolean looksLikeHeading(String paragraph) {
        String p = paragraph.trim();
        if (p.length() > 90 || p.endsWith(".") || p.endsWith("?") || p.endsWith("!")) {
            return false;
        }
        return p.split("\\s+").length <= 12;
    }

    private void rebuildCurrentLayout() {
        int width = Math.max(320, getWidth() - getPaddingLeft() - getPaddingRight());
        String value = currentLine();
        currentLayout = StaticLayout.Builder.obtain(value, 0, value.length(), textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();
        invalidate();
    }

    private void resetAccessibilityWindow(int anchor) {
        if (lines.isEmpty()) {
            windowStart = 0;
            windowEndExclusive = 0;
            return;
        }
        int half = ACCESSIBILITY_WINDOW_SIZE / 2;
        int start = Math.max(0, anchor - half);
        int end = Math.min(lines.size(), start + ACCESSIBILITY_WINDOW_SIZE);
        start = Math.max(0, end - ACCESSIBILITY_WINDOW_SIZE);
        windowStart = start;
        windowEndExclusive = end;
    }

    private boolean ensureAccessibilityWindowContains(int line) {
        if (windowEndExclusive <= windowStart) {
            resetAccessibilityWindow(line);
            return true;
        }
        boolean nearStart = line < windowStart + ACCESSIBILITY_WINDOW_MARGIN;
        boolean nearEnd = line >= windowEndExclusive - ACCESSIBILITY_WINDOW_MARGIN;
        if ((nearStart && windowStart > 0)
                || (nearEnd && windowEndExclusive < lines.size())
                || line < windowStart || line >= windowEndExclusive) {
            int oldStart = windowStart;
            int oldEnd = windowEndExclusive;
            resetAccessibilityWindow(line);
            return oldStart != windowStart || oldEnd != windowEndExclusive;
        }
        return false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) rebuildLines();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        if (currentLayout != null) currentLayout.draw(canvas);
        canvas.restore();
    }

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return nodeProvider;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.view.ViewGroup");
        info.setPackageName(getContext().getPackageName());
        info.setContentDescription("FlexiReader document. " + lines.size() + " lines.");
        info.setFocusable(false);
        info.setScreenReaderFocusable(false);
        info.setScrollable(false);
        for (int i = windowStart; i < windowEndExclusive; i++) {
            info.addChild(this, i);
        }
    }

    private AccessibilityNodeInfo createHostNode() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(this);
        onInitializeAccessibilityNodeInfo(info);
        return info;
    }

    private AccessibilityNodeInfo createLineNode(int virtualId) {
        if (virtualId < 0 || virtualId >= lines.size()) return null;

        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.setSource(this, virtualId);
        info.setParent(this);
        info.setPackageName(getContext().getPackageName());
        info.setClassName("android.widget.TextView");
        info.setText(lines.get(virtualId));
        info.setContentDescription(lines.get(virtualId));
        info.setFocusable(true);
        info.setVisibleToUser(isShown());
        info.setEnabled(isEnabled());

        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());
        info.setBoundsInParent(new Rect(0, 0, width, height));
        int[] location = new int[2];
        getLocationOnScreen(location);
        info.setBoundsInScreen(new Rect(
                location[0], location[1], location[0] + width, location[1] + height));

        boolean focused = virtualId == accessibilityFocusedLine;
        info.setAccessibilityFocused(focused);
        info.addAction(focused
                ? AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);

        int granularities = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH;
        info.setMovementGranularities(granularities);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);

        boolean isHeading = headingStarts.contains(virtualId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.setHeading(isHeading);
        }

        if (virtualId < lines.size() - 1) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_NEXT_PARAGRAPH, "Next paragraph"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_NEXT_SENTENCE, "Next sentence"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_NEXT_PAGE, "Next page"));
        }
        if (virtualId > 0) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_PREVIOUS_PARAGRAPH, "Previous paragraph"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_PREVIOUS_SENTENCE, "Previous sentence"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_PREVIOUS_PAGE, "Previous page"));
        }
        if (!headingStarts.isEmpty()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_NEXT_HEADING, "Next heading"));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_PREVIOUS_HEADING, "Previous heading"));
        }

        return info;
    }

    private boolean performMovementGranularity(Bundle arguments, boolean forward) {
        int granularity = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE;
        if (arguments != null) {
            granularity = arguments.getInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE);
        }

        if (granularity == AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH) {
            if (forward) nextParagraph(); else previousParagraph();
            return true;
        }
        if (granularity == AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE) {
            if (forward) nextLine(); else previousLine();
            return true;
        }
        return false;
    }

    private void sendVirtualEvent(int virtualId, int eventType) {
        if (virtualId < 0 || virtualId >= lines.size() || !isShown()) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
        event.setPackageName(getContext().getPackageName());
        event.setClassName("android.widget.TextView");
        event.setSource(this, virtualId);
        event.getText().add(lines.get(virtualId));
        event.setContentDescription(lines.get(virtualId));
        ViewParent parent = getParent();
        if (parent != null) parent.requestSendAccessibilityEvent(this, event);
    }

    private void sendHostContentChanged() {
        if (!isShown()) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setPackageName(getContext().getPackageName());
        event.setClassName("android.view.ViewGroup");
        event.setSource(this);
        event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        ViewParent parent = getParent();
        if (parent != null) parent.requestSendAccessibilityEvent(this, event);
    }

    private final class LineNodeProvider extends AccessibilityNodeProvider {
        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            return virtualViewId == HOST_VIEW_ID
                    ? createHostNode() : createLineNode(virtualViewId);
        }

        @Override
        public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            if (virtualViewId == HOST_VIEW_ID) {
                return LineReaderView.this.performAccessibilityAction(action, arguments);
            }
            if (virtualViewId < 0 || virtualViewId >= lines.size()) return false;

            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                focusVirtualLine(virtualViewId);
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocusedLine == virtualViewId) {
                    accessibilityFocusedLine = -1;
                    sendVirtualEvent(virtualViewId,
                            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                }
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY) {
                return performMovementGranularity(arguments, true);
            }
            if (action == AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY) {
                return performMovementGranularity(arguments, false);
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

        @Override
        public AccessibilityNodeInfo findFocus(int focus) {
            return focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
                    && accessibilityFocusedLine >= 0
                    ? createLineNode(accessibilityFocusedLine) : null;
        }
    }
}
