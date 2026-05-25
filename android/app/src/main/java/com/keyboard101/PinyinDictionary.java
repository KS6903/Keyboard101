package com.keyboard101;

import android.os.Handler;
import android.os.Looper;

import com.github.promeg.pinyinhelper.Pinyin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reverse pinyin→characters dictionary built at runtime using TinyPinyin.
 *
 * TinyPinyin already knows the pinyin for every CJK character in GB2312/GBK.
 * We iterate the full CJK Unified Ideographs block (U+4E00–U+9FFF, ~20 K chars),
 * ask TinyPinyin for each character's pinyin, and invert the map.
 * Characters TinyPinyin doesn't recognise (non-CJK, unsupported) are skipped.
 * Results are sorted by character frequency so the most common characters
 * appear first in the candidate bar.
 *
 * The build runs once on a background thread; callers register an onReady
 * callback that fires on the main thread when the map is available.
 */
public class PinyinDictionary {

    // ~600 most-frequent Mandarin characters for candidate ranking.
    // Position in this string = priority; characters not listed rank last.
    private static final String FREQ =
        "的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年" +
        "动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水" +
        "化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前" +
        "外天政四日那社义事平形相全表间样与关各重新线内数正心明文思保路号基信价级知品" +
        "运位式代际常问任次市公共供功建展己达处统领增南管按期先无比济别见说并向通但支" +
        "队百千万亿己发改名情长系被根据证以改力求值给标接际己发己己己己己己己己己己己" +
        "想向相象响香项乡享像小笑校消晓情轻青清庆倾晴经精景京静净竟境径惊晶睛起其气期" +
        "七奇器棋旗企骑齐妻弃栖解写协鞋谢些歇斜携新心信辛欣薪行星性形型兴幸醒了乐勒";

    private static volatile Map<String, List<String>> reverseMap = null;
    private static volatile boolean loading = false;

    /**
     * Starts the background build if not already done.
     * {@code onReady} is posted to the main thread once the map is ready.
     * Safe to call multiple times — only builds once.
     */
    public static void initAsync(Runnable onReady) {
        if (reverseMap != null) {
            new Handler(Looper.getMainLooper()).post(onReady);
            return;
        }
        if (loading) return;
        loading = true;
        new Thread(() -> {
            Map<String, List<String>> map = new HashMap<>();

            // CJK Unified Ideographs U+4E00 – U+9FFF
            for (int cp = 0x4E00; cp <= 0x9FFF; cp++) {
                char c = (char) cp;
                String py = Pinyin.toPinyin(c);
                // TinyPinyin returns the char itself when unknown — skip those
                if (py != null && py.matches("[a-zA-Z]+")) {
                    map.computeIfAbsent(py.toLowerCase(), k -> new ArrayList<>())
                       .add(String.valueOf(c));
                }
            }

            // Sort each bucket by frequency: lower FREQ index = higher priority
            for (List<String> bucket : map.values()) {
                bucket.sort((a, b) -> {
                    int ia = FREQ.indexOf(a);
                    int ib = FREQ.indexOf(b);
                    if (ia < 0) ia = 999_999;
                    if (ib < 0) ib = 999_999;
                    return Integer.compare(ia, ib);
                });
            }

            reverseMap = map;
            loading = false;
            new Handler(Looper.getMainLooper()).post(onReady);
        }).start();
    }

    public static boolean isReady() {
        return reverseMap != null;
    }

    /**
     * Returns all characters whose pinyin exactly matches {@code pinyin},
     * frequency-ordered. E.g. "yi" → [一, 乙, 已, 以, 义, 艺, 亿, ...]
     */
    public static List<String> getCandidates(String pinyin) {
        if (reverseMap == null) return Collections.emptyList();
        List<String> r = reverseMap.get(pinyin.toLowerCase());
        return r != null ? r : Collections.emptyList();
    }

    /**
     * Returns characters from every syllable whose pinyin starts with {@code prefix},
     * de-duplicated and frequency-ordered. E.g. "y" covers yi/ya/yan/yang/yao/ye/...
     */
    public static List<String> getPrefixCandidates(String prefix) {
        if (reverseMap == null) return Collections.emptyList();
        Set<String> seen = new LinkedHashSet<>();
        String lc = prefix.toLowerCase();
        for (Map.Entry<String, List<String>> e : reverseMap.entrySet()) {
            if (e.getKey().startsWith(lc)) {
                seen.addAll(e.getValue());
                if (seen.size() >= 60) break;
            }
        }
        return new ArrayList<>(seen);
    }
}
