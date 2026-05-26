package com.keyboard101;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends hand-drawn stroke data to Google Input Tools (same backend as the
 * HTML demo) and returns Chinese character candidates.
 *
 * Endpoint: https://inputtools.google.com/request?itc=zh-t-i0-handwrit
 * No API key required; requires internet access.
 *
 * Stroke format: ink = [ [xs,ys,ts], [xs,ys,ts], ... ]
 * where each array-triple represents one continuous stroke.
 */
public class HandwritingEngine {

    private static final String TAG = "HandwritingEngine";
    private static final String ENDPOINT =
        "https://inputtools.google.com/request?itc=zh-t-i0-handwrit&num=15";

    public interface Callback {
        void onResult(List<String> candidates);
    }

    public static class Stroke {
        public final List<Float> xs = new ArrayList<>();
        public final List<Float> ys = new ArrayList<>();
        public final List<Long>  ts = new ArrayList<>();
    }

    public static void recognize(List<Stroke> strokes,
                                  float areaWidth, float areaHeight,
                                  Callback callback) {
        if (strokes == null || strokes.isEmpty()) {
            callback.onResult(new ArrayList<>());
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        List<Stroke> snapshot = new ArrayList<>(strokes);
        new Thread(() -> {
            List<String> result = fetch(snapshot, areaWidth, areaHeight);
            main.post(() -> callback.onResult(result));
        }).start();
    }

    private static List<String> fetch(List<Stroke> strokes,
                                       float areaWidth, float areaHeight) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray ink = new JSONArray();
            for (Stroke s : strokes) {
                JSONArray xArr = new JSONArray();
                JSONArray yArr = new JSONArray();
                JSONArray tArr = new JSONArray();
                for (int i = 0; i < s.xs.size(); i++) {
                    xArr.put(s.xs.get(i).doubleValue());
                    yArr.put(s.ys.get(i).doubleValue());
                    tArr.put(s.ts.get(i).longValue());
                }
                JSONArray stroke = new JSONArray();
                stroke.put(xArr);
                stroke.put(yArr);
                stroke.put(tArr);
                ink.put(stroke);
            }

            JSONObject guide = new JSONObject();
            guide.put("writing_area_width",  (double) areaWidth);
            guide.put("writing_area_height", (double) areaHeight);

            JSONObject req = new JSONObject();
            req.put("language",      "zh");
            req.put("writing_guide", guide);
            req.put("ink",           ink);
            req.put("pre_context",   "");

            JSONArray requests = new JSONArray();
            requests.put(req);

            JSONObject body = new JSONObject();
            body.put("options",  "enable_pre_space");
            body.put("requests", requests);

            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn =
                (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "KeyS/1.0 Android IME");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            if (conn.getResponseCode() != 200) return out;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(),
                                          StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }

            // ["SUCCESS",[["query",["c1","c2",...],["py1",...]],[...]]]
            JSONArray root = new JSONArray(sb.toString());
            if (!"SUCCESS".equals(root.optString(0))) return out;
            JSONArray candidates =
                root.getJSONArray(1).getJSONArray(0).getJSONArray(1);
            for (int i = 0; i < candidates.length(); i++) {
                String s = candidates.getString(i);
                if (s != null && !s.trim().isEmpty()) out.add(s);
            }
        } catch (Exception e) {
            Log.w(TAG, "Handwriting request failed: " + e.getMessage());
        }
        return out;
    }
}
