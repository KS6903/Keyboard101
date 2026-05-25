package com.keyboard101;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * JNI bridge to the native pinyin segmentation engine (pinyin_jni.cpp).
 * The native code uses the authoritative syllable table from the libpinyin
 * submodule (android/app/src/main/cpp/libpinyin) to correctly split an
 * arbitrary pinyin string into its constituent syllables via dynamic
 * programming (e.g. "nihao" → ["ni","hao"], "xiang" → ["xiang"]).
 *
 * Fall-back: if the native library is unavailable the Java-only fallback
 * segmentation is used so the keyboard never crashes.
 */
public class PinyinEngine {

    private static final String TAG = "PinyinEngine";
    private static boolean nativeAvailable = false;

    static {
        try {
            System.loadLibrary("pinyin_jni");
            nativeAvailable = true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native pinyin library not available, using Java fallback: " + e.getMessage());
        }
    }

    // --- Native declarations ---
    private static native String[] parsePinyin(String input);
    private static native boolean isSyllable(String s);

    /**
     * Segment a raw pinyin buffer into individual syllables.
     * Returns at least one element (may be the raw input if no syllable matched).
     */
    public static String[] segment(String input) {
        if (input == null || input.isEmpty()) return new String[0];
        if (nativeAvailable) {
            try {
                String[] result = parsePinyin(input.toLowerCase());
                if (result != null && result.length > 0) return result;
            } catch (Exception e) {
                Log.w(TAG, "Native parsePinyin failed, falling back: " + e.getMessage());
            }
        }
        return segmentFallback(input.toLowerCase());
    }

    /**
     * Check if a string is a complete valid pinyin syllable.
     */
    public static boolean isCompleteSyllable(String s) {
        if (s == null || s.isEmpty()) return false;
        if (nativeAvailable) {
            try {
                return isSyllable(s.toLowerCase());
            } catch (Exception ignored) {}
        }
        return SYLLABLE_SET.contains(s.toLowerCase());
    }

    // ------------------------------------------------------------------
    // Java fallback segmentation (greedy longest-match)
    // ------------------------------------------------------------------
    private static String[] segmentFallback(String input) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            int best = -1;
            for (int len = Math.min(6, input.length() - i); len >= 1; len--) {
                if (SYLLABLE_SET.contains(input.substring(i, i + len))) {
                    best = len;
                    break;
                }
            }
            if (best > 0) {
                result.add(input.substring(i, i + best));
                i += best;
            } else {
                // Append non-matching char to last token or start new one
                if (!result.isEmpty()) {
                    result.set(result.size() - 1, result.get(result.size() - 1) + input.charAt(i));
                } else {
                    result.add(String.valueOf(input.charAt(i)));
                }
                i++;
            }
        }
        return result.toArray(new String[0]);
    }

    // Minimal syllable set for the Java fallback (matches the native table)
    private static final java.util.Set<String> SYLLABLE_SET = new java.util.HashSet<>(
        java.util.Arrays.asList(
            "a","ai","an","ang","ao",
            "ba","bai","ban","bang","bao","bei","ben","beng","bi","bian","biao","bie","bin","bing","bo","bu",
            "ca","cai","can","cang","cao","ce","cen","ceng","cha","chai","chan","chang","chao","che","chen",
            "cheng","chi","chong","chou","chu","chuai","chuan","chuang","chui","chun","chuo","ci","cong","cou","cu","cuan","cui","cun","cuo",
            "da","dai","dan","dang","dao","de","dei","deng","di","dia","dian","diao","die","ding","diu","dong","dou","du","duan","dui","dun","duo",
            "e","ei","en","er",
            "fa","fan","fang","fei","fen","feng","fo","fou","fu",
            "ga","gai","gan","gang","gao","ge","gei","gen","geng","gong","gou","gu","gua","guai","guan","guang","gui","gun","guo",
            "ha","hai","han","hang","hao","he","hei","hen","heng","hong","hou","hu","hua","huai","huan","huang","hui","hun","huo",
            "ji","jia","jian","jiang","jiao","jie","jin","jing","jiong","jiu","ju","juan","jue","jun",
            "ka","kai","kan","kang","kao","ke","ken","keng","kong","kou","ku","kua","kuai","kuan","kuang","kui","kun","kuo",
            "la","lai","lan","lang","lao","le","lei","leng","li","lia","lian","liang","liao","lie","lin","ling","liu","lo","long","lou","lu","luan","lun","luo","lv","lve",
            "ma","mai","man","mang","mao","me","mei","men","meng","mi","mian","miao","mie","min","ming","miu","mo","mou","mu",
            "na","nai","nan","nang","nao","ne","nei","nen","neng","ng","ni","nian","niang","niao","nie","nin","ning","niu","nong","nou","nu","nuan","nuo","nv","nve",
            "o","ou",
            "pa","pai","pan","pang","pao","pei","pen","peng","pi","pian","piao","pie","pin","ping","po","pou","pu",
            "qi","qia","qian","qiang","qiao","qie","qin","qing","qiong","qiu","qu","quan","que","qun",
            "ran","rang","rao","re","ren","reng","ri","rong","rou","ru","ruan","rui","run","ruo",
            "sa","sai","san","sang","sao","se","sen","seng","sha","shai","shan","shang","shao","she","shei","shen","sheng","shi","shou","shu","shua","shuai","shuan","shuang","shui","shun","shuo","si","song","sou","su","suan","sui","sun","suo",
            "ta","tai","tan","tang","tao","te","teng","ti","tian","tiao","tie","ting","tong","tou","tu","tuan","tui","tun","tuo",
            "wa","wai","wan","wang","wei","wen","weng","wo","wu",
            "xi","xia","xian","xiang","xiao","xie","xin","xing","xiong","xiu","xu","xuan","xue","xun",
            "ya","yan","yang","yao","ye","yi","yin","ying","yo","yong","you","yu","yuan","yue","yun",
            "za","zai","zan","zang","zao","ze","zei","zen","zeng","zha","zhai","zhan","zhang","zhao","zhe","zhen","zheng","zhi","zhong","zhou","zhu","zhua","zhuai","zhuan","zhuang","zhui","zhun","zhuo","zi","zong","zou","zu","zuan","zui","zun","zuo"
        )
    );
}
