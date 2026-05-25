package com.keyboard101;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches Chinese character candidates for a pinyin string using
 * Google's Input Tools API (the same backend Google uses for its own
 * Chinese keyboard). No API key required; requires internet access.
 *
 * Response format: ["SUCCESS",[["query",["cand1","cand2",...],[...]]]]
 */
public class GoogleInputEngine {

    private static final String TAG = "GoogleInputEngine";
    private static final String ENDPOINT =
        "https://inputtools.google.com/request" +
        "?text=%s&itc=zh-t-i0-pinyin&num=30&cp=0&cs=1&ie=utf-8&oe=utf-8&app=droid";

    public interface Callback {
        void onResult(List<String> candidates);
    }

    public static void getCandidates(String pinyin, Callback callback) {
        if (pinyin == null || pinyin.isEmpty()) {
            callback.onResult(new ArrayList<>());
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<String> result = fetch(pinyin);
            main.post(() -> callback.onResult(result));
        }).start();
    }

    private static List<String> fetch(String pinyin) {
        List<String> out = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(pinyin, "UTF-8");
            String urlStr = String.format(ENDPOINT, encoded);
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "KeyS/1.0 Android IME");

            if (conn.getResponseCode() != 200) return out;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }

            // ["SUCCESS",[["query",["c1","c2",...],["py1",...]],[...]]]
            JSONArray root = new JSONArray(sb.toString());
            if (!"SUCCESS".equals(root.optString(0))) return out;
            JSONArray candidates = root.getJSONArray(1).getJSONArray(0).getJSONArray(1);
            for (int i = 0; i < candidates.length(); i++) {
                out.add(candidates.getString(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "Input Tools request failed: " + e.getMessage());
        }
        return out;
    }
}
