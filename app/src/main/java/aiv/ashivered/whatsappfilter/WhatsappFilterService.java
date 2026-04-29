package aiv.ashivered.whatsappfilter;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
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

        SharedPreferences prefs = getSharedPreferences("FilterPrefs", MODE_PRIVATE);
        boolean blockUpdates = prefs.getBoolean("block_updates", false);
        boolean blockChannels = prefs.getBoolean("block_channels", false);
        boolean blockVideo = prefs.getBoolean("block_video", false);
        boolean blockStatus = prefs.getBoolean("block_status", false);

        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getClassName() != null) {
                currentActivity = event.getClassName().toString();
            }

            // 1. חסימת נגן הסטטוסים
            if (blockStatus && (currentActivity.equals("com.whatsapp.status.playback.StatusPlaybackActivity") ||
                    currentActivity.contains("StatusStoryActivity"))) {
                performBlockAction();
                return;
            }

            // 2. חסימת ערוצים - אלגוריתם דטרמיניסטי (A-E)
            if (blockChannels && currentActivity.equals("com.whatsapp.Conversation")) {
                AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                if (rootNode != null) {
                    if (isChannelByStructure(rootNode)) {
                        performBlockAction();
                    }
                    rootNode.recycle();
                }
            }

            // חסימת ספריית הערוצים
            if (blockChannels && currentActivity.contains("NewsletterDirectory")) {
                performBlockAction();
                return;
            }
        }

        // 3. חסימת וידאו (SeekBar)
        if (blockVideo && currentActivity.equals("com.whatsapp.mediaview.MediaViewActivity")) {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                if (isActualVideoShowing(rootNode)) {
                    performBlockAction();
                }
                rootNode.recycle();
            }
        }

        // 4. חסימת טאב עדכונים (RTL/LTR)
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

    private boolean isChannelByStructure(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // 1. סימן היכר: אם יש שדה טקסט להקלדה - זה צ'אט רגיל או קבוצה פתוחה. פתח.
        if (hasNodeByClass(root, "android.widget.EditText")) {
            return false;
        }

        // 2. סימן היכר: ערוץ לא נעקב או ערוץ עם כפתור השתקה.
        // בקבוצות (גם סגורות) אין אובייקט מסוג android.widget.Button בגוף השיחה.
        // הודעת "רק מנהלים יכולים לשלוח הודעה" היא TextView, לא Button.
        // נחפש כפתור שנמצא מתחת לאזור הכותרת (כדי לא להתבלבל עם כפתור "חזור").
        if (hasButtonBelowHeader(root)) {
            return true;
        }

        // 3. אבחנה בין קבוצה סגורה לערוץ נעקב (כשאין EditText ואין Button):
        // בקבוצה סגורה, יש TextView בתחתית המסך (הודעת המנהלים).
        // בערוץ נעקב, ה-ListView (רשימת ההודעות) בדרך כלל תופס את כל שטח המסך התחתון.
        if (isClosedGroupBottomLabel(root)) {
            return false; // זו קבוצה סגורה - אל תחסום.
        }

        // 4. אם הגענו לכאן: אין שדה הקלדה, אין הודעת "רק מנהלים" של קבוצה,
        // ואין כפתורי פעולה של צ'אט - זהו ערוץ.
        return true;
    }

    // בודק אם קיים כפתור (Button) מתחת לכותרת
    private boolean hasButtonBelowHeader(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if ("android.widget.Button".equals(node.getClassName())) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            // אם הכפתור נמצא מתחת לכותרת (למשל מתחת ל-300 פיקסלים)
            if (rect.top > 300) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasButtonBelowHeader(node.getChild(i))) return true;
        }
        return false;
    }

    // מזהה את התווית "רק מנהלים יכולים לשלוח הודעה" של קבוצות סגורות בצורה מבנית
    private boolean isClosedGroupBottomLabel(AccessibilityNodeInfo root) {
        // נחפש TextView שנמצא ב-10% התחתונים של המסך
        // ושאינו חלק מרשימת ההודעות (ListView)
        return findBottomTextViewOutsideList(root, root);
    }

    private boolean findBottomTextViewOutsideList(AccessibilityNodeInfo root, AccessibilityNodeInfo node) {
        if (node == null) return false;

        if ("android.widget.TextView".equals(node.getClassName())) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            int screenHeight = getResources().getDisplayMetrics().heightPixels;

            // אם הטקסט נמצא ממש בתחתית המסך
            if (rect.bottom > (screenHeight * 0.85)) {
                // וודא שהוא לא חלק מה-ListView (הודעות בערוץ יכולות להגיע לתחתית)
                // קבוצות סגורות שמות את הטקסט במיכל נפרד שהוא אח של ה-ListView
                if (!isInsideListView(node)) {
                    return true;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (findBottomTextViewOutsideList(root, node.getChild(i))) return true;
        }
        return false;
    }

    private boolean isInsideListView(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.getClassName() != null &&
                    (parent.getClassName().toString().contains("ListView") ||
                            parent.getClassName().toString().contains("RecyclerView"))) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private boolean hasImageButtonWithDescContaining(AccessibilityNodeInfo node, String descFragment) {
        if (node == null) return false;
        if ("android.widget.ImageButton".equals(node.getClassName()) ||
                "android.widget.ImageView".equals(node.getClassName())) {
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().contains(descFragment)) {
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasImageButtonWithDescContaining(node.getChild(i), descFragment)) return true;
        }
        return false;
    }

    private boolean hasTextViewContaining(AccessibilityNodeInfo node, String textFragment) {
        if (node == null) return false;
        if ("android.widget.TextView".equals(node.getClassName())) {
            CharSequence text = node.getText();
            if (text != null && text.toString().contains(textFragment)) {
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasTextViewContaining(node.getChild(i), textFragment)) return true;
        }
        return false;
    }

    private boolean hasButtonWithTextContaining(AccessibilityNodeInfo node, String textFragment) {
        if (node == null) return false;
        if ("android.widget.Button".equals(node.getClassName())) {
            CharSequence text = node.getText();
            if (text != null && text.toString().contains(textFragment)) {
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasButtonWithTextContaining(node.getChild(i), textFragment)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findTopActionButton(AccessibilityNodeInfo node) {
        if (node == null) return null;

        if (node.isClickable() && (node.getClassName().toString().contains("Image") ||
                node.getClassName().toString().contains("Button"))) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;

            // לחצני פעולה (Call/Mute) נמצאים בכותרת (top < 300)
            // ובצד הנגדי ללחצן ה"חזור" (Back)
            boolean isRtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            boolean inActionArea = isRtl ? rect.right < (screenWidth * 0.4) : rect.left > (screenWidth * 0.6);

            if (rect.top < 300 && inActionArea) {
                // מוודאים שזה לא לחצן ה"תפריט שלוש נקודות" שבקצה הקיצוני
                if (isRtl ? rect.left > 100 : rect.right < screenWidth - 100) {
                    return node;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findTopActionButton(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private boolean hasActionButtonSibling(AccessibilityNodeInfo actionButton) {
        AccessibilityNodeInfo parent = actionButton.getParent();
        if (parent == null) return false;

        int clickableCount = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child != null && child.isClickable()) {
                clickableCount++;
            }
        }
        // בקבוצה סגורה יש בד"כ 2 לחצני פעולה (שיחה + וידאו) באותו קונטיינר. בערוץ יש 1 (Mute).
        return clickableCount >= 2;
    }

    private boolean hasBigBottomButton(AccessibilityNodeInfo node) {
        if (node == null) return false;

        if ("android.widget.Button".equals(node.getClassName())) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            // לחצן ה-Follow הוא לחצן Button גדול בחלק התחתון
            if (rect.top > (screenHeight * 0.7)) return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasBigBottomButton(node.getChild(i))) return true;
        }
        return false;
    }

    private boolean hasNodeByClass(AccessibilityNodeInfo node, String className) {
        if (node == null) return false;
        if (className.equals(node.getClassName())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasNodeByClass(node.getChild(i), className)) return true;
        }
        return false;
    }

    private boolean isActualVideoShowing(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if ("android.widget.SeekBar".equals(node.getClassName())) return node.isVisibleToUser();
        for (int i = 0; i < node.getChildCount(); i++) {
            if (isActualVideoShowing(node.getChild(i))) return true;
        }
        return false;
    }

    private void performBlockAction() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBlockTime < 150) return;
        lastBlockTime = currentTime;

        performGlobalAction(GLOBAL_ACTION_BACK);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(getApplicationContext(), R.string.blocked_toast_message, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    @Override public void onInterrupt() {}
}