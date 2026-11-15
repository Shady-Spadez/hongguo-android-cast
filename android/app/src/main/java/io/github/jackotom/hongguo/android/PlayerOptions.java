package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONException;
import org.json.JSONObject;

/** A single native preference source shared by the settings bridge and every player. */
final class PlayerOptions {
    private static final String FILE = "player_options";
    static final float[] SPEEDS = { .75f, 1f, 1.25f, 1.5f, 2f };
    private PlayerOptions() { }
    private static SharedPreferences preferences(Context context) {
        Context app = context.getApplicationContext();
        return (app == null ? context : app).getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    static String quality(String input) {
        if (!"auto".equals(input) && !"720p".equals(input) && !"1080p".equals(input)) throw new IllegalArgumentException("清晰度仅支持自动、720p 或 1080p");
        return input;
    }
    static float speed(double input) {
        for (float value : SPEEDS) if (Double.isFinite(input) && Math.abs(input - value) < .00001) return value;
        throw new IllegalArgumentException("倍速仅支持 0.75、1、1.25、1.5 或 2");
    }
    /** Portrait projection: the projector lies on its side, so rendering rotates 90° or 270°. */
    static String rotation(String input) {
        if (!"0".equals(input) && !"90".equals(input) && !"270".equals(input)) throw new IllegalArgumentException("画面旋转仅支持 0、90 或 270");
        return input;
    }
    static void validateSpeed(double input) { speed(input); }
    static synchronized JSONObject read(Context context) throws JSONException {
        SharedPreferences prefs = preferences(context);
        String selected = "auto"; float rate = 1f; boolean danmaku = false; String rotation = "0";
        try { selected = quality(prefs.getString("quality", "auto")); } catch (RuntimeException ignored) { }
        try { rate = speed(Double.parseDouble(prefs.getString("speed", "1.0"))); } catch (RuntimeException ignored) { }
        try { danmaku = "true".equals(prefs.getString("danmaku", "false")); } catch (RuntimeException ignored) { }
        try { rotation = rotation(prefs.getString("screenRotation", "0")); } catch (RuntimeException ignored) { }
        return new JSONObject().put("quality", selected).put("speed", (double) rate).put("danmaku", danmaku).put("screenRotation", rotation);
    }
    /** Validate the whole update before committing either field. A failed commit is not success. */
    static synchronized JSONObject update(Context context, JSONObject changes) throws Exception {
        if (changes == null) throw new IllegalArgumentException("播放设置无效");
        JSONObject prior = read(context);
        String selected = prior.getString("quality"); float rate = (float) prior.getDouble("speed"); boolean danmaku = prior.getBoolean("danmaku");
        String rotation = prior.getString("screenRotation");
        if (changes.has("quality")) {
            Object value = changes.get("quality");
            if (!(value instanceof String)) throw new IllegalArgumentException("清晰度设置无效");
            selected = quality((String) value);
        }
        if (changes.has("speed")) {
            Object value = changes.get("speed");
            if (!(value instanceof Number)) throw new IllegalArgumentException("倍速设置无效");
            rate = speed(((Number) value).doubleValue());
        }
        if (changes.has("danmaku")) {
            if (!(changes.opt("danmaku") instanceof Boolean)) throw new IllegalArgumentException("弹幕开关无效");
            danmaku = changes.getBoolean("danmaku");
        }
        if (changes.has("screenRotation")) {
            Object value = changes.get("screenRotation");
            if (!(value instanceof String)) throw new IllegalArgumentException("画面旋转设置无效");
            rotation = rotation((String) value);
        }
        SharedPreferences prefs = preferences(context);
        boolean committed = false;
        try {
            committed = prefs.edit().putString("quality", selected).putString("speed", Float.toString(rate)).putString("danmaku", Boolean.toString(danmaku)).putString("screenRotation", rotation).commit();
        } finally {
            // Android updates memory before reporting a disk-write failure. Restore that memory
            // too, so another Activity never observes a change which this call rejected.
            if (!committed) try { prefs.edit().putString("quality", prior.getString("quality")).putString("speed", Float.toString((float) prior.getDouble("speed"))).putString("danmaku", prior.getBoolean("danmaku") ? "true" : "false").putString("screenRotation", prior.getString("screenRotation")).commit(); } catch (RuntimeException ignored) { }
        }
        if (!committed) throw new IllegalStateException("播放设置保存未成功");
        return new JSONObject().put("quality", selected).put("speed", (double) rate).put("danmaku", danmaku).put("screenRotation", rotation);
    }
}
