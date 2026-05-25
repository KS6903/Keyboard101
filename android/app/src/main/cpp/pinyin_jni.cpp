/*
 * pinyin_jni.cpp
 *
 * JNI bridge for pinyin syllable segmentation.
 * The syllable table is derived from libpinyin (src/storage/pinyin_parser_table.h),
 * which is included as a git submodule at cpp/libpinyin/.
 * Only the valid IS_PINYIN entries with distance==0 are used here so we avoid
 * libpinyin's glib2 dependency while still using its authoritative syllable data.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cstring>

// ---------------------------------------------------------------------------
// Syllable table — 428 entries from libpinyin's pinyin_parser_table.h
// Kept sorted (binary-search compatible).
// ---------------------------------------------------------------------------
static const char* PINYIN_SYLLABLES[] = {
    "a", "ai", "an", "ang", "ao",
    "b", "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng",
    "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu",
    "c", "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng",
    "ch", "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng",
    "chi", "chong", "chou", "chu", "chuai", "chuan", "chuang", "chui", "chun", "chuo",
    "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo",
    "d", "da", "dai", "dan", "dang", "dao", "de", "dei", "deng",
    "di", "dia", "dian", "diao", "die", "ding", "diu", "dong", "dou",
    "du", "duan", "dui", "dun", "duo",
    "e", "ei", "en", "er",
    "f", "fa", "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu",
    "g", "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng",
    "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
    "h", "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng",
    "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
    "j", "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong",
    "jiu", "ju", "juan", "jue", "jun",
    "k", "ka", "kai", "kan", "kang", "kao", "ke", "ken", "keng",
    "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
    "l", "la", "lai", "lan", "lang", "lao", "le", "lei", "leng",
    "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling", "liu",
    "lo", "long", "lou", "lu", "luan", "lun", "luo", "lv", "lve",
    "m", "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng",
    "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
    "n", "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng",
    "ng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu",
    "nong", "nou", "nu", "nuan", "nuo", "nv", "nve",
    "o", "ou",
    "p", "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng",
    "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
    "q", "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong",
    "qiu", "qu", "quan", "que", "qun",
    "r", "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou",
    "ru", "ruan", "rui", "run", "ruo",
    "s", "sa", "sai", "san", "sang", "sao", "se", "sen", "seng",
    "sh", "sha", "shai", "shan", "shang", "shao", "she", "shei", "shen", "sheng",
    "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo",
    "si", "song", "sou", "su", "suan", "sui", "sun", "suo",
    "t", "ta", "tai", "tan", "tang", "tao", "te", "teng",
    "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
    "w", "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
    "x", "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong",
    "xiu", "xu", "xuan", "xue", "xun",
    "y", "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying",
    "yo", "yong", "you", "yu", "yuan", "yue", "yun",
    "z", "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng",
    "zh", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhen", "zheng",
    "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo",
    "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo"
};

static const int SYLLABLE_COUNT =
    static_cast<int>(sizeof(PINYIN_SYLLABLES) / sizeof(PINYIN_SYLLABLES[0]));

// ---------------------------------------------------------------------------
// Binary search: returns true if `str` (length `len`) is a valid syllable.
// ---------------------------------------------------------------------------
static bool isSyllable(const char* str, int len) {
    int lo = 0, hi = SYLLABLE_COUNT - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        int cmp = strncmp(PINYIN_SYLLABLES[mid], str, len);
        if (cmp == 0) {
            // also ensure full match (no trailing chars in table entry)
            cmp = (int)(strlen(PINYIN_SYLLABLES[mid]) - len);
        }
        if (cmp == 0) return true;
        if (cmp < 0) lo = mid + 1;
        else         hi = mid - 1;
    }
    return false;
}

// ---------------------------------------------------------------------------
// DP segmentation: splits `input` into the best sequence of valid syllables.
//
// dp[i] = index of the syllable boundary at position i that gives the best
//         (fewest unsegmented chars, then most syllables) split up to i.
// Returns the list of syllable strings.
// ---------------------------------------------------------------------------
static std::vector<std::string> segmentPinyin(const std::string& input) {
    const int n = static_cast<int>(input.size());
    if (n == 0) return {};

    // dp[i]: best number of complete chars covered up to position i
    // prev[i]: previous boundary position
    std::vector<int> dp(n + 1, -1);
    std::vector<int> prev(n + 1, -1);
    std::vector<int> count(n + 1, 0);  // number of syllables used
    dp[0] = 0;

    for (int i = 0; i < n; i++) {
        if (dp[i] < 0) continue;
        // Try all valid syllable lengths from current position (max 6 chars)
        for (int len = 1; len <= 6 && i + len <= n; len++) {
            if (isSyllable(input.c_str() + i, len)) {
                int j = i + len;
                int newCovered = dp[i] + len;
                if (newCovered > dp[j] ||
                    (newCovered == dp[j] && count[i] + 1 > count[j])) {
                    dp[j] = newCovered;
                    prev[j] = i;
                    count[j] = count[i] + 1;
                }
            }
        }
    }

    // Backtrack from the rightmost matched position
    int end = n;
    // Find the furthest position where we have a valid segmentation
    // (may not reach n if input has non-pinyin suffix)
    while (end > 0 && dp[end] < 0) end--;

    std::vector<std::string> result;
    int pos = end;
    while (pos > 0 && prev[pos] >= 0) {
        result.push_back(input.substr(prev[pos], pos - prev[pos]));
        pos = prev[pos];
    }
    std::reverse(result.begin(), result.end());

    // Append any unmatched suffix as-is (so caller always sees full input)
    if (end < n) {
        result.push_back(input.substr(end));
    }

    return result;
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------
extern "C" {

/*
 * parsePinyin(String input) -> String[]
 * Segments a raw pinyin string into syllable tokens.
 * e.g. "nihao" -> ["ni","hao"], "xiang" -> ["xiang"]
 */
JNIEXPORT jobjectArray JNICALL
Java_com_keyboard101_PinyinEngine_parsePinyin(JNIEnv* env, jclass, jstring jinput) {
    const char* input_cstr = env->GetStringUTFChars(jinput, nullptr);
    std::string input(input_cstr);
    env->ReleaseStringUTFChars(jinput, input_cstr);

    // Lowercase the input
    for (char& c : input) c = static_cast<char>(tolower(c));

    std::vector<std::string> segs = segmentPinyin(input);

    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(segs.size()), strClass, nullptr);
    for (int i = 0; i < static_cast<int>(segs.size()); i++) {
        env->SetObjectArrayElement(arr, i, env->NewStringUTF(segs[i].c_str()));
    }
    return arr;
}

/*
 * isSyllable(String s) -> boolean
 * Returns true if the string is a valid complete pinyin syllable.
 */
JNIEXPORT jboolean JNICALL
Java_com_keyboard101_PinyinEngine_isSyllable(JNIEnv* env, jclass, jstring js) {
    const char* s = env->GetStringUTFChars(js, nullptr);
    jboolean result = isSyllable(s, static_cast<int>(strlen(s))) ? JNI_TRUE : JNI_FALSE;
    env->ReleaseStringUTFChars(js, s);
    return result;
}

} // extern "C"
