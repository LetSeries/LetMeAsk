package com.cubex.quiz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 题库行解析单测："题目=答案"切分、=/: 分隔符优先级、\= \: \\ 转义还原。
 * splitQALine 为包级静态纯函数，不依赖 Bukkit 服务器。
 */
class QAParsingTest {
    @Test
    void 基本切分() {
        String[] r = QuizPlugin.splitQALine("中国首都=北京");
        assertEquals("中国首都", r[0]);
        assertEquals("北京", r[1]);
    }

    @Test
    void 没有等号时回退冒号() {
        String[] r = QuizPlugin.splitQALine("太阳从哪边升起:东");
        assertEquals("太阳从哪边升起", r[0]);
        assertEquals("东", r[1]);
    }

    @Test
    void 等号优先于冒号() {
        String[] r = QuizPlugin.splitQALine("a:b=c");
        assertEquals("a:b", r[0]);
        assertEquals("c", r[1]);
    }

    @Test
    void 答案里转义的等号还原为字面量() {
        String[] r = QuizPlugin.splitQALine("一元一次方程=x\\=1");
        assertEquals("一元一次方程", r[0]);
        assertEquals("x=1", r[1]);
    }

    @Test
    void 题目里转义的等号还原为字面量() {
        String[] r = QuizPlugin.splitQALine("2+2\\=4 这个等式成立吗=成立");
        assertEquals("2+2=4 这个等式成立吗", r[0]);
        assertEquals("成立", r[1]);
    }

    @Test
    void 双反斜杠还原为单个反斜杠() {
        // raw: a\\=b=c → 题目 "a\"（\\ 还原），答案 "b=c"（第一个 = 是分隔符）
        String[] r = QuizPlugin.splitQALine("a\\\\=b=c");
        assertEquals("a\\", r[0]);
        assertEquals("b=c", r[1]);
    }

    @Test
    void 转义的冒号不参与切分() {
        // raw: a\:b:c → 题目 "a:b"，答案 "c"
        String[] r = QuizPlugin.splitQALine("a\\:b:c");
        assertEquals("a:b", r[0]);
        assertEquals("c", r[1]);
    }

    @Test
    void 奇数个反斜杠为转义偶数个不是() {
        // raw: x\=y\:z → 全部是转义分隔符，无可切分
        assertNull(QuizPlugin.splitQALine("x\\=y\\:z"));
        // raw: x\\=y → 两个反斜杠后跟未转义的 =，可切分
        String[] r = QuizPlugin.splitQALine("x\\\\=y");
        assertEquals("x\\", r[0]);
        assertEquals("y", r[1]);
    }

    @Test
    void 没有分隔符返回null() {
        assertNull(QuizPlugin.splitQALine("没有分隔符"));
        assertNull(QuizPlugin.splitQALine(""));
    }
}
