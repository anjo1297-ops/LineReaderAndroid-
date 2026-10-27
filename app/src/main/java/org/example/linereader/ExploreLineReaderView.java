package org.example.linereader;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

/**
 * Adds Explore-by-Touch hit testing to LineReaderView without changing its
 * virtual-line navigation model. TalkBack turns finger exploration into hover
 * events; this view maps each hover coordinate to the virtual line whose
 * reported bounds contain that point and requests accessibility focus for it.
 */
public class ExploreLineReaderView extends LineReaderView {
    private static final int HIT_TEST_RADIUS_LINES = 100;

    private final AccessibilityManager accessibilityManager;
    private int hoveredLine = -1;

    public ExploreLineReaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        accessibilityManager = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        if (accessibilityManager != null && accessibilityManager.isTouchExplorationEnabled()) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
                int target = findVirtualLineAt(event.getX(), event.getY());
                if (target >= 0) {
                    if (target != hoveredLine) {
                        hoveredLine = target;
                        AccessibilityNodeProvider provider = getAccessibilityNodeProvider();
                        if (provider != null) {
                            provider.performAction(target,
                                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                                    (Bundle) null);
                        }
                    }
                    return true;
                }
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                hoveredLine = -1;
                return true;
            }
        }
        return super.dispatchHoverEvent(event);
    }

    private int findVirtualLineAt(float x, float y) {
        AccessibilityNodeProvider provider = getAccessibilityNodeProvider();
        if (provider == null || getLineCount() <= 0) return -1;

        int current = Math.max(0, Math.min(getCurrentLineIndex(), getLineCount() - 1));
        int start = Math.max(0, current - HIT_TEST_RADIUS_LINES);
        int end = Math.min(getLineCount() - 1, current + HIT_TEST_RADIUS_LINES);
        Rect bounds = new Rect();

        for (int i = start; i <= end; i++) {
            AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(i);
            if (node == null) continue;
            try {
                node.getBoundsInParent(bounds);
                if (bounds.contains((int) x, (int) y)) return i;
            } finally {
                node.recycle();
            }
        }
        return -1;
    }
}
