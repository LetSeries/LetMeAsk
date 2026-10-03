package com.cubex.quiz;

import java.util.List;
import java.util.Locale;

/**
 * 答案匹配器：归一化 + 模糊匹配（带上界早退的编辑距离）。
 * 从 QuizPlugin 拆出；阈值由主类在 reload 时同步（volatile，保证异步聊天线程可见）。
 */
public class QuestionMatcher {
    private volatile double fuzzySimilarityThreshold = 0.75;

    public void setFuzzySimilarityThreshold(double threshold) {
        this.fuzzySimilarityThreshold = Math.min(1.0, Math.max(0.0, threshold));
    }

    public double getFuzzySimilarityThreshold() {
        return fuzzySimilarityThreshold;
    }

    /**
     * 任一候选答案匹配即算答对。normalizedAnswers 为出题时预归一化的答案，
     * 聊天消息只归一化一次，避免每条消息重复归一化题库答案。
     */
    public boolean matchesAny(String providedNormalized, List<String> normalizedAnswers) {
        if (providedNormalized == null || providedNormalized.isEmpty() || normalizedAnswers == null) return false;
        double threshold = fuzzySimilarityThreshold; // 读一次快照，本轮判定内一致
        for (String b : normalizedAnswers) {
            if (b.isEmpty()) continue;
            if (providedNormalized.equals(b)) return true;
            if (threshold >= 1.0) continue; // 1.0 = 严格精确匹配
            int max = Math.max(providedNormalized.length(), b.length());
            // sim >= threshold  <=>  dist <= max * (1 - threshold)，上界早退
            int maxDist = (int) Math.floor(max * (1.0 - threshold));
            int dist = levenshtein(providedNormalized, b, maxDist);
            if (dist <= maxDist) return true;
        }
        return false;
    }

    /** 预编译：String.replaceAll 每次都编译 Pattern，高频聊天路径下不可接受。 */
    private static final java.util.regex.Pattern NON_ALNUM = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");

    public static String normalize(String s) {
        // Locale.ROOT：避免土耳其语等 locale 下 I/i 大小写转换异常
        return s == null ? "" : NON_ALNUM.matcher(s).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** DP 数组复用：聊天消息长度已限 100 字，按 128 预分配，线程隔离避免并发污染。 */
    private static final ThreadLocal<int[][]> LEVENSHTEIN_BUF =
            ThreadLocal.withInitial(() -> new int[2][128]);

    /**
     * 带上界的编辑距离：若中途已能确定距离超过 maxDist，直接返回 maxDist+1。
     * 调用方只关心“是否达标”，超标的精确值无意义，早退省掉剩余 DP 计算。
     */
    public static int levenshtein(String s1, String s2, int maxDist) {
        int n = s1.length();
        int m = s2.length();
        if (Math.abs(n - m) > maxDist) return maxDist + 1; // 长度差本身就是下界
        // 超长回退：正常走不到（聊天限 100 字），避免越界
        if (m + 1 > 128) return levenshteinAlloc(s1, s2, maxDist);
        int[][] buf = LEVENSHTEIN_BUF.get();
        int[] prev = buf[0];
        int[] curr = buf[1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= m; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (curr[j] < rowMin) rowMin = curr[j];
            }
            if (rowMin > maxDist) return maxDist + 1; // 整行都超标，后续只会更大
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }

    /** 超长回退路径：分配新数组计算（正常走不到，仅防越界）。 */
    private static int levenshteinAlloc(String s1, String s2, int maxDist) {
        int n = s1.length();
        int m = s2.length();
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= m; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (curr[j] < rowMin) rowMin = curr[j];
            }
            if (rowMin > maxDist) return maxDist + 1;
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }
}
