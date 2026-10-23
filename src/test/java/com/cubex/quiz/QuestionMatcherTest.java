package com.cubex.quiz;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 答案匹配单测：归一化、带上界早退的编辑距离、模糊匹配阈值。
 * QuestionMatcher 为纯逻辑类，不依赖 Bukkit 服务器。
 */
class QuestionMatcherTest {
    @Test
    void 归一化去除非字母数字并小写() {
        assertEquals("北京", QuestionMatcher.normalize(" 北 京！ "));
        assertEquals("helloworld", QuestionMatcher.normalize("Hello, World!"));
        // Locale.ROOT：土耳其 locale 下 I 也不会转成 ı
        assertEquals("i", QuestionMatcher.normalize("I"));
        assertEquals("", QuestionMatcher.normalize(null));
    }

    @Test
    void 编辑距离基础值() {
        assertEquals(0, QuestionMatcher.levenshtein("abc", "abc", 0));
        assertEquals(1, QuestionMatcher.levenshtein("abc", "abd", 1));
        assertEquals(3, QuestionMatcher.levenshtein("kitten", "sitting", 3));
    }

    @Test
    void 编辑距离超标时早退为maxDist加一() {
        assertEquals(2, QuestionMatcher.levenshtein("abc", "xyz", 1));
        // 长度差本身就是下界
        assertEquals(2, QuestionMatcher.levenshtein("ab", "abcdef", 1));
    }

    @Test
    void 精确与模糊匹配() {
        QuestionMatcher m = new QuestionMatcher(); // 默认阈值 0.75
        assertTrue(m.matchesAny("jupiter", List.of("jupiter", "木星")));
        assertTrue(m.matchesAny("木星", List.of("jupiter", "木星")));
        // 距离 1，max=7 → maxDist = floor(7*0.25) = 1，达标
        assertTrue(m.matchesAny("jupite", List.of("jupiter")));
        // 距离 5 远超阈值
        assertFalse(m.matchesAny("jup", List.of("jupiter")));
        assertFalse(m.matchesAny("", List.of("jupiter")));
    }

    @Test
    void 阈值为一时只接受精确匹配() {
        QuestionMatcher m = new QuestionMatcher();
        m.setFuzzySimilarityThreshold(1.0);
        assertTrue(m.matchesAny("jupiter", List.of("jupiter")));
        assertFalse(m.matchesAny("jupite", List.of("jupiter")));
        assertFalse(m.matchesAny("JUPITER", List.of("jupiter"))); // 传入方需先自行归一化
    }

    @Test
    void 超长输入走分配路径结果一致() {
        // 聊天路径缓冲区 128，超过即走 levenshteinAlloc，两路径语义必须一致
        String a = "x".repeat(130);
        String b = "x".repeat(129) + "y";
        assertEquals(1, QuestionMatcher.levenshtein(a, b, 5));
        // 全程无公共子串：距离远超上界，返回早退哨兵值 maxDist+1
        assertEquals(6, QuestionMatcher.levenshtein(a, "z".repeat(130), 5));
    }
}
