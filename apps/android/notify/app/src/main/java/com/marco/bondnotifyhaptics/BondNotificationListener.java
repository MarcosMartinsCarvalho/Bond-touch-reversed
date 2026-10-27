package com.marco.bondnotifyhaptics;

import android.app.AlarmManager;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class BondNotificationListener extends NotificationListenerService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> lastPackageMs = new HashMap<>();
    private final Map<String, Runnable> repeatingAlarms = new HashMap<>();

    @Override
    public void onListenerConnected() {
        log("Notification listener connected.");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        SharedPreferences prefs = BondConfig.prefs(this);
        if (!prefs.getBoolean(BondConfig.KEY_ENABLED, false) || quietNow(prefs)) {
            return;
        }

        Notification notification = sbn.getNotification();
        String packageName = sbn.getPackageName();
        if (packageName == null || packageName.equals(getPackageName())) {
            return;
        }
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0 && !isAlarmNotification(sbn)) {
            return;
        }
        if (isNoisySystemNotification(notification)) {
            return;
        }

        if (isPreAlarmNotification(sbn)) {
            handlePreAlarmPosted();
            return;
        }

        if (isAlarmNotification(sbn)) {
            handleAlarmPosted(sbn);
            return;
        }

        AppRule rule = BondConfig.loadRule(this, packageName);
        if (!selectedPackage(packageName) || !rule.enabled || !cooldownReady(packageName, rule.cooldownMs)) {
            return;
        }
        BondHapticService.haptic(this, new HapticCommand(
                "notification:" + safeText(notification),
                rule.pattern,
                rule.strength,
                rule.durationMs,
                rule.color,
                rule.priority));
        log("Notification haptic from " + rule.label + ".");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        Runnable runnable = repeatingAlarms.remove(sbn.getKey());
        if (runnable != null) {
            handler.removeCallbacks(runnable);
            log("Alarm haptics stopped.");
        }
    }

    private void handleAlarmPosted(StatusBarNotification sbn) {
        AppRule rule = BondConfig.alarmRule(this);
        if (!rule.enabled || !cooldownReady("__alarm__", rule.cooldownMs)) {
            return;
        }
        boolean repeat = BondConfig.prefs(this).getBoolean(BondConfig.KEY_ALARM_REPEAT, true);
        HapticCommand command = new HapticCommand("alarm", rule.pattern, rule.strength, rule.durationMs, rule.color, rule.priority);
        BondHapticService.haptic(this, command);
        log("Alarm haptic triggered.");
        if (!repeat || repeatingAlarms.containsKey(sbn.getKey())) {
            return;
        }
        long startMs = System.currentTimeMillis();
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - startMs > 120_000L) {
                    repeatingAlarms.remove(sbn.getKey());
                    return;
                }
                BondHapticService.haptic(BondNotificationListener.this, command);
                handler.postDelayed(this, Math.max(1200, rule.cooldownMs));
            }
        };
        repeatingAlarms.put(sbn.getKey(), runnable);
        handler.postDelayed(runnable, Math.max(1200, rule.cooldownMs));
    }

    private void handlePreAlarmPosted() {
        AppRule rule = BondConfig.preAlarmRule(this);
        if (!rule.enabled || !cooldownReady("__pre_alarm__", rule.cooldownMs)) {
            return;
        }
        BondHapticService.haptic(this, new HapticCommand(
                "pre-alarm",
                rule.pattern,
                rule.strength,
                rule.durationMs,
                rule.color,
                rule.priority));
        log("Pre-alarm haptic triggered.");
    }

    private boolean selectedPackage(String packageName) {
        return BondConfig.prefs(this)
                .getStringSet(BondConfig.KEY_RULE_PACKAGES, java.util.Collections.emptySet())
                .contains(packageName);
    }

    private boolean cooldownReady(String key, int cooldownMs) {
        long now = System.currentTimeMillis();
        long last = lastPackageMs.getOrDefault(key, -100_000L);
        if (now - last < cooldownMs) {
            return false;
        }
        lastPackageMs.put(key, now);
        return true;
    }

    private boolean quietNow(SharedPreferences prefs) {
        if (!prefs.getBoolean(BondConfig.KEY_QUIET_ENABLED, false)) {
            return false;
        }
        int start = prefs.getInt(BondConfig.KEY_QUIET_START, 23 * 60);
        int end = prefs.getInt(BondConfig.KEY_QUIET_END, 7 * 60);
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int now = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE);
        if (start == end) {
            return false;
        }
        if (start < end) {
            return now >= start && now < end;
        }
        return now >= start || now < end;
    }

    private boolean isAlarmNotification(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        String packageName = sbn.getPackageName() == null ? "" : sbn.getPackageName().toLowerCase(Locale.US);
        String text = safeText(notification).toLowerCase(Locale.US);
        if (isUpcomingAlarmText(text)) {
            return false;
        }
        if (looksLikeRingingAlarm(text)) {
            return true;
        }
        if (Notification.CATEGORY_ALARM.equals(notification.category)) {
            return isNearSystemAlarmTime();
        }
        return (packageName.contains("clock") || packageName.contains("alarm")) && isNearSystemAlarmTime();
    }

    private boolean isPreAlarmNotification(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        String packageName = sbn.getPackageName() == null ? "" : sbn.getPackageName().toLowerCase(Locale.US);
        String text = safeText(notification).toLowerCase(Locale.US);
        return (Notification.CATEGORY_ALARM.equals(notification.category)
                || packageName.contains("clock")
                || packageName.contains("alarm"))
                && isUpcomingAlarmText(text)
                && !isNearSystemAlarmTime();
    }

    private boolean isNearSystemAlarmTime() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            AlarmManager.AlarmClockInfo nextAlarm = alarmManager == null ? null : alarmManager.getNextAlarmClock();
            if (nextAlarm == null) {
                return false;
            }
            long deltaMs = System.currentTimeMillis() - nextAlarm.getTriggerTime();
            return deltaMs >= -5_000L && deltaMs <= 180_000L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean looksLikeRingingAlarm(String text) {
        return text.contains("snooze")
                || text.contains("dismiss")
                || text.contains("stop alarm")
                || text.contains("alarm is ringing");
    }

    private boolean isUpcomingAlarmText(String text) {
        return text.contains("upcoming alarm")
                || text.contains("next alarm")
                || text.contains("alarm set for")
                || text.contains("alarm in ")
                || text.contains("goes off")
                || text.contains("will ring")
                || text.contains("upcoming")
                || text.contains("minutes before")
                || text.contains("mins before");
    }

    private boolean isNoisySystemNotification(Notification notification) {
        String category = notification.category;
        return Notification.CATEGORY_TRANSPORT.equals(category)
                || Notification.CATEGORY_SERVICE.equals(category)
                || Notification.CATEGORY_PROGRESS.equals(category)
                || Notification.CATEGORY_STATUS.equals(category);
    }

    private String safeText(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) {
            return "";
        }
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE, "");
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");
        StringBuilder value = new StringBuilder(String.valueOf(title)).append(' ').append(String.valueOf(text));
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action != null && action.title != null) {
                    value.append(' ').append(action.title);
                }
            }
        }
        return value.toString();
    }

    private void log(String message) {
        BondConfig.prefs(this).edit()
                .putString(BondConfig.KEY_LAST_LOG,
                        String.format(Locale.US, "%1$tH:%1$tM:%1$tS  %2$s", System.currentTimeMillis(), message))
                .apply();
    }
}
