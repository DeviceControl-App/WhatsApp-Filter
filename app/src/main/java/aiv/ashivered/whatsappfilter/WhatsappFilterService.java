package aiv.ashivered.whatsappfilter;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

public class WhatsappFilterService extends AccessibilityService {

    private String currentActivity = "";
    private long lastBlockTime = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (packageName == null || !packageName.toString().equals("com.whatsapp")) return;

        // שליפת הגדרות מהסוויצ'ים - הוספנו את block_status
        SharedPreferences prefs = getSharedPreferences("FilterPrefs", MODE_PRIVATE);
        boolean blockUpdates = prefs.getBoolean("block_updates", false);
        boolean blockChannels = prefs.getBoolean("block_channels", false);
        boolean blockVideo = prefs.getBoolean("block_video", false);
        boolean blockStatus = prefs.getBoolean("block_status", false); // הסוויץ' החדש

        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getClassName() != null) {
                currentActivity = event.getClassName().toString();
            }

            // 1. חסימת נגן הסטטוסים - מופעל ע"י הסוויץ' החדש blockStatus
            if (blockStatus) {
                if (currentActivity.equals("com.whatsapp.status.playback.StatusPlaybackActivity") ||
                        currentActivity.contains("StatusStoryActivity")) {
                    performBlockAction();
                    return;
                }
            }

            // 2. חסימת ספריות וערוצים
            if (blockChannels) {
                if (currentActivity.contains("NewsletterDirectory") || currentActivity.equals("X.DLc")) {
                    performBlockAction();
                    return;
                }

                if (currentActivity.equals("com.whatsapp.Conversation")) {
                    AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                    if (rootNode != null && isChannelByStructure(rootNode)) {
                        performBlockAction();
                        return;
                    }
                }
            }
        }

        // 3. חסימת וידאו (מבוסס SeekBar)
        if (blockVideo && currentActivity.equals("com.whatsapp.mediaview.MediaViewActivity")) {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null && isActualVideoShowing(rootNode)) {
                performBlockAction();
            }
        }

        // 4. חסימת לחצן העדכונים - מופעל ע"י blockUpdates בלבד
        if (blockUpdates && eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo node = event.getSource();
            if (node != null && "android.widget.FrameLayout".equals(node.getClassName().toString())) {
                AccessibilityNodeInfo parent = node.getParent();

                if (parent != null && parent.getChildCount() > 3) {
                    int layoutDir = getResources().getConfiguration().getLayoutDirection();
                    int targetIndex = (layoutDir == View.LAYOUT_DIRECTION_RTL) ? 2 : 1;

                    AccessibilityNodeInfo targetChild = parent.getChild(targetIndex);
                    if (targetChild != null && targetChild.equals(node)) {
                        performBlockAction();
                    }
                }
            }
        }
    }

    private boolean isActualVideoShowing(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if ("android.widget.SeekBar".equals(node.getClassName().toString())) {
            return node.isVisibleToUser();
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (isActualVideoShowing(node.getChild(i))) return true;
        }
        return false;
    }

    private boolean isChannelByStructure(AccessibilityNodeInfo node) {
        return countClickableIconsInHeader(node) < 2;
    }

    private int countClickableIconsInHeader(AccessibilityNodeInfo node) {
        if (node == null) return 0;
        int count = 0;
        if (node.isClickable() && "android.widget.ImageView".equals(node.getClassName().toString())) {
            android.graphics.Rect rect = new android.graphics.Rect();
            node.getBoundsInScreen(rect);
            if (rect.top < 300) count++;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            count += countClickableIconsInHeader(node.getChild(i));
        }
        return count;
    }

    private void performBlockAction() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBlockTime < 10) return;

        lastBlockTime = currentTime;
        performGlobalAction(GLOBAL_ACTION_BACK);
        Toast.makeText(getApplicationContext(), R.string.blocked_toast_message, Toast.LENGTH_SHORT).show();
    }

    @Override public void onInterrupt() {}
}