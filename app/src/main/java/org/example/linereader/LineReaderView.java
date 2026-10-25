package org.example.linereader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
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
 * Accessibility-first reading surface.
 *
 * Each visual text line is exposed as a virtual accessibility child. TalkBack therefore
 * moves between lines as real accessibility nodes instead of repeatedly changing one node
 * and trying to force accessibility focus back to it.
 */
public class LineReaderView extends View {
    private static final int HOST_VIEW_ID = -1;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> lines = new ArrayList<>();
    private final LineNodeProvider nodeProvider = new LineNodeProvider();

    private String documentText = "Open a document to begin.";
    private int lineIndex = 0;
    private int accessibilityFocusedLine = -1;
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
        invalidate();
    }

    public void nextLine() {
        moveToLine(Math.min(lineIndex + 1, lines.size() - 1));
    }

    public void previousLine() {
        moveToLine(Math.max(lineIndex - 1, 0));
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

        accessibilityFocusedLine = newIndex;
        lineIndex = newIndex;
        rebuildCurrentLayout();
        invalidate();
        sendVirtualEvent(newIndex, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    }

    private String currentLine() {
        return lines.isEmpty() ? "" : lines.get(Math.max(0, Math.min(lineIndex, lines.size() - 1)));
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
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return nodeProvider;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.view.ViewGroup");
        info.setPackageName(getContext().getPackageName());
        info.setContentDescription("Document reading area. " + lines.size() + " lines.");
        info.setFocusable(false);
        info.setScreenReaderFocusable(false);
        for (int i = 0; i < lines.size(); i++) {
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
        Rect parentBounds = new Rect(0, 0, width, height);
        info.setBoundsInParent(parentBounds);

        int[] location = new int[2];
        getLocationOnScreen(location);
        Rect screenBounds = new Rect(
                location[0], location[1],
                location[0] + width, location[1] + height);
        info.setBoundsInScreen(screenBounds);

        boolean focused = virtualId == accessibilityFocusedLine;
        info.setAccessibilityFocused(focused);
        if (focused) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
        } else {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        }

        if (virtualId < lines.size() - 1) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        }
        if (virtualId > 0) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }

        return info;
    }

    private void sendVirtualEvent(int virtualId, int eventType) {
        if (virtualId < 0 || virtualId >= lines.size()) return;
        if (!isShown()) return;

        AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
        event.setPackageName(getContext().getPackageName());
        event.setClassName("android.widget.TextView");
        event.setSource(this, virtualId);
        event.getText().add(lines.get(virtualId));
        event.setContentDescription(lines.get(virtualId));

        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestSendAccessibilityEvent(this, event);
        }
    }

    private final class LineNodeProvider extends AccessibilityNodeProvider {
        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            if (virtualViewId == HOST_VIEW_ID) {
                return createHostNode();
            }
            return createLineNode(virtualViewId);
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

            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                if (virtualViewId < lines.size() - 1) {
                    focusVirtualLine(virtualViewId + 1);
                }
                return true;
            }

            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                if (virtualViewId > 0) {
                    focusVirtualLine(virtualViewId - 1);
                }
                return true;
            }

            return false;
        }

        @Override
        public AccessibilityNodeInfo findFocus(int focus) {
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
                    && accessibilityFocusedLine >= 0) {
                return createLineNode(accessibilityFocusedLine);
            }
            return null;
        }
    }
}
