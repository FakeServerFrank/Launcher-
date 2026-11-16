package com.example.launcherios;

import android.app.Notification;
import android.app.PendingIntent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;

public class NLService extends NotificationListenerService {
    static NLService inst;

    @Override public void onListenerConnected() {
        inst = this;
        MainActivity.js("if(window.refreshNotifs)refreshNotifs()");
    }

    @Override public void onListenerDisconnected() {
        inst = null;
    }

    @Override public void onNotificationPosted(StatusBarNotification s) {
        inst = this;
        if (!ok(s)) return;
        try {
            MainActivity.js("if(window.onNotif)onNotif(" + jo(s).toString() + ")");
        } catch (Exception e) { }
    }

    @Override public void onNotificationRemoved(StatusBarNotification s) {
        MainActivity.js("if(window.refreshNotifs)refreshNotifs()");
    }

    boolean ok(StatusBarNotification s) {
        Notification n = s.getNotification();
        return !s.isOngoing()
                && !s.getPackageName().equals(getPackageName())
                && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0;
    }

    static JSONObject jo(StatusBarNotification s) throws Exception {
        Bundle e = s.getNotification().extras;
        CharSequence t = e.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence m = e.getCharSequence(Notification.EXTRA_TEXT);
        if (m == null) m = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
        JSONObject o = new JSONObject();
        o.put("k", s.getKey());
        o.put("p", s.getPackageName());
        o.put("t", t == null ? "" : t.toString());
        o.put("m", m == null ? "" : m.toString());
        o.put("w", s.getPostTime());
        return o;
    }

    static String dump() {
        JSONArray a = new JSONArray();
        if (inst != null) {
            try {
                for (StatusBarNotification s : inst.getActiveNotifications()) {
                    if (inst.ok(s)) a.put(jo(s));
                }
            } catch (Exception e) { }
        }
        return a.toString();
    }

    static void open(String key) {
        if (inst == null) return;
        try {
            for (StatusBarNotification s : inst.getActiveNotifications()) {
                if (s.getKey().equals(key)) {
                    PendingIntent pi = s.getNotification().contentIntent;
                    if (pi != null) pi.send();
                    if ((s.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) inst.cancelNotification(key);
                    break;
                }
            }
        } catch (Exception e) { }
    }

    static void clear() {
        if (inst != null) {
            try { inst.cancelAllNotifications(); } catch (Exception e) { }
        }
    }
}
