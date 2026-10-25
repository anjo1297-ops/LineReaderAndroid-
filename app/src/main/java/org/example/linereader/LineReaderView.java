package org.example.linereader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessible reading surface. Accessibility SCROLL_FORWARD/BACKWARD means one text line,
 * so TalkBack's scrolling gesture can act like Down/Up Arrow in a desktop reader.
 */
public class LineReaderView extends View {
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String documentText = "Open a document to begin.";
    private final List<String> lines = new ArrayList<>();
    private int lineIndex = 0;
    private StaticLayout currentLayout;

    public LineReaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        textPaint.setTextSize(48f);
        textPaint.setTypeface(Typeface.DEFAULT);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setScreenReaderFocusable(true);
        }
        rebuildLines();
    }

    public void setDocumentText(String text) {
        documentText = (text == null || text.trim().isEmpty()) ? "No readable text was found." : text;
        lineIndex = 0;
        rebuildLines();
        announceCurrentLine();
    }

    public void nextLine() {
        if (lineIndex < lines.size() - 1) {
            lineIndex++;
            rebuildCurrentLayout();
            announceCurrentLine();
        } else {
            announceForAccessibility("End of document");
        }
    }

    public void previousLine() {
        if (lineIndex > 0) {
            lineIndex--;
            rebuildCurrentLayout();
            announceCurrentLine();
        } else {
            announceForAccessibility("Beginning of document");
        }
    }

    private String currentLine() {
        return lines.isEmpty() ? "" : lines.get(Math.max(0, Math.min(lineIndex, lines.size() - 1)));
    }

    /**
     * Announce only the changed line. Do not replace the View content description and do not
     * emit TYPE_VIEW_TEXT_CHANGED here: both can cause TalkBack to treat the reader as a newly
     * changed accessibility node and move accessibility focus to the neighbouring buttons.
     */
    private void announceCurrentLine() {
        invalidate();
        announceForAccessibility(currentLine());
    }

    private void rebuildLines() {
        lines.clear();
        int width = Math.max(320, getWidth() - getPaddingLeft() - getPaddingRight());
        String normalized = documentText.replace("\r\n", "\n").replace('\r', '\n');
        for (String paragraph : normalized.split("\n", -1)) {
            String p = paragraph.trim();
            if (p.isEmpty()) continue;
            StaticLayout layout = StaticLayout.Builder.obtain(p, 0, p.length(), textPaint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build();
            for (int i = 0; i < layout.getLineCount(); i++) {
                int start = layout.getLineStart(i);
                int end = layout.getLineEnd(i);
                String line = p.substring(start, end).trim();
                if (!line.isEmpty()) lines.add(line);
            }
        }
        if (lines.isEmpty()) lines.add("No readable text was found.");
        if (lineIndex >= lines.size()) lineIndex = lines.size() - 1;
        rebuildCurrentLayout();
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
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.ScrollView");
        info.setScrollable(lines.size() > 1);
        info.setText(currentLine());
        info.setContentDescription("Line " + (lineIndex + 1) + " of " + lines.size() + ". " + currentLine());
        if (lineIndex < lines.size() - 1) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        }
        if (lineIndex > 0) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            nextLine();
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            previousLine();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
