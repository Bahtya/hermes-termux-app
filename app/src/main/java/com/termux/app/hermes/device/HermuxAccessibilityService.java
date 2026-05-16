package com.termux.app.hermes.device;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class HermuxAccessibilityService extends AccessibilityService {

    private static final String TAG = "HermuxA11y";
    private static volatile HermuxAccessibilityService sInstance;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public static HermuxAccessibilityService getInstance() {
        return sInstance;
    }

    public static boolean isRunning() {
        return sInstance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "Accessibility service connected");
        DeviceControlServer.start(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op: we only act on demand, not reactively
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        DeviceControlServer.stop();
        sInstance = null;
        super.onDestroy();
        Log.i(TAG, "Accessibility service destroyed");
    }

    // --- Public API ---

    /**
     * Run an a11y callable on the main thread and wait for the result.
     * Callers (HTTP handlers) are on the server executor thread.
     */
    public JSONObject callOnMainThread(Callable<JSONObject> callable) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return callable.call();
        }
        final Exception[] error = {null};
        final JSONObject[] result = {null};
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            try {
                result[0] = callable.call();
            } catch (Exception e) {
                error[0] = e;
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new RuntimeException("main thread call timed out");
        }
        if (error[0] != null) throw error[0];
        return result[0];
    }

    public JSONObject clickByText(String text) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorResult("No active window");
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes.isEmpty()) {
            root.recycle();
            return errorResult("No node found with text: " + text);
        }
        AccessibilityNodeInfo target = findClickableAncestor(nodes.get(0));
        boolean clicked = target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (target != null && target != nodes.get(0)) target.recycle();
        for (AccessibilityNodeInfo n : nodes) n.recycle();
        root.recycle();
        return clicked ? okResult("clicked") : errorResult("click failed for text: " + text);
    }

    public JSONObject clickById(String id) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorResult("No active window");
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        if (nodes.isEmpty()) {
            root.recycle();
            return errorResult("No node found with id: " + id);
        }
        AccessibilityNodeInfo target = findClickableAncestor(nodes.get(0));
        boolean clicked = target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (target != null && target != nodes.get(0)) target.recycle();
        for (AccessibilityNodeInfo n : nodes) n.recycle();
        root.recycle();
        return clicked ? okResult("clicked") : errorResult("click failed for id: " + id);
    }

    public JSONObject clickAt(int x, int y) throws Exception {
        boolean ok = dispatchTap(x, y);
        return ok ? okResult("clicked at (" + x + "," + y + ")") : errorResult("tap dispatch failed");
    }

    public JSONObject typeText(String text) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorResult("No active window");

        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) {
            root.recycle();
            return errorResult("No focused input field");
        }

        boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, buildSetTextBundle(text));
        focus.recycle();
        root.recycle();
        return ok ? okResult("typed") : errorResult("type failed");
    }

    public JSONObject swipe(int x1, int y1, int x2, int y2, int durationMs) throws Exception {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
        boolean ok = dispatchGestureAndWait(builder.build());
        return ok ? okResult("swiped") : errorResult("swipe dispatch failed");
    }

    public JSONObject pressKey(int keyCode) throws Exception {
        boolean ok = performGlobalAction(keyCode);
        return ok ? okResult("key pressed: " + keyCode) : errorResult("key press failed: " + keyCode);
    }

    public JSONObject dumpUI() throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorResult("No active window");
        JSONObject tree = nodeToJson(root, 0);
        root.recycle();
        JSONObject result = okResult("ok");
        result.put("ui", tree);
        return result;
    }

    public JSONObject getCurrentApp() throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorResult("No active window");
        JSONObject info = new JSONObject();
        info.put("packageName", root.getPackageName());
        info.put("className", root.getClassName());
        info.put("bounds", boundsToString(root));
        root.recycle();
        JSONObject result = okResult("ok");
        result.put("app", info);
        return result;
    }

    public JSONObject scrollForward() throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorResult("No active window");
        AccessibilityNodeInfo scrollable = findScrollableNode(root);
        if (scrollable == null) {
            root.recycle();
            return errorResult("No scrollable node found");
        }
        boolean ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        scrollable.recycle();
        root.recycle();
        return ok ? okResult("scrolled forward") : errorResult("scroll forward failed");
    }

    public JSONObject scrollBackward() throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorResult("No active window");
        AccessibilityNodeInfo scrollable = findScrollableNode(root);
        if (scrollable == null) {
            root.recycle();
            return errorResult("No scrollable node found");
        }
        boolean ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        scrollable.recycle();
        root.recycle();
        return ok ? okResult("scrolled backward") : errorResult("scroll backward failed");
    }

    // --- Internal helpers ---

    private boolean dispatchTap(int x, int y) throws Exception {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path path = new Path();
        path.moveTo(x, y);
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        return dispatchGestureAndWait(builder.build());
    }

    private boolean dispatchGestureAndWait(GestureDescription gesture) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = {false};
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                result[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                latch.countDown();
            }
        }, mHandler);
        return latch.await(3, TimeUnit.SECONDS) && result[0];
    }

    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable()) {
                AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(current);
                if (current != node) current.recycle();
                return result;
            }
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) {
                if (current != node) current.recycle();
                return null;
            }
            if (current != node) current.recycle();
            current = parent;
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        findNodesByAction(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, result);
        return result.isEmpty() ? null : result.get(0);
    }

    private void findNodesByAction(AccessibilityNodeInfo node, int action, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.getActionList() != null) {
            for (AccessibilityNodeInfo.AccessibilityAction a : node.getActionList()) {
                if (a.getId() == action) {
                    out.add(AccessibilityNodeInfo.obtain(node));
                    return;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findNodesByAction(child, action, out);
                child.recycle();
            }
        }
    }

    private android.os.Bundle buildSetTextBundle(String text) {
        android.os.Bundle args = new android.os.Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return args;
    }

    private JSONObject nodeToJson(AccessibilityNodeInfo node, int depth) throws JSONException {
        if (node == null || depth > 30) return null;
        JSONObject obj = new JSONObject();
        obj.put("class", node.getClassName());
        obj.put("text", node.getText() != null ? node.getText().toString() : "");
        obj.put("contentDesc", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
        obj.put("viewId", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
        obj.put("bounds", boundsToString(node));
        obj.put("clickable", node.isClickable());
        obj.put("scrollable", node.isScrollable());
        obj.put("editable", node.isEditable());
        obj.put("checked", node.isChecked());
        obj.put("enabled", node.isEnabled());

        JSONArray children = new JSONArray();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = node.getChild(i);
            if (childNode != null) {
                JSONObject child = nodeToJson(childNode, depth + 1);
                if (child != null) children.put(child);
                childNode.recycle();
            }
        }
        obj.put("children", children);
        return obj;
    }

    private String boundsToString(AccessibilityNodeInfo node) {
        android.graphics.Rect r = new android.graphics.Rect();
        node.getBoundsInScreen(r);
        return r.left + "," + r.top + " - " + r.right + "," + r.bottom;
    }

    static JSONObject okResult(String msg) throws JSONException {
        return new JSONObject().put("ok", true).put("message", msg);
    }

    static JSONObject errorResult(String msg) throws JSONException {
        return new JSONObject().put("ok", false).put("error", msg);
    }
}
