package com.cubex.quiz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 占位符替换单测：模板颜色转换与 {arg}/{arg2}/{cmd} 填充。
 * fillPlaceholders 为包级静态纯函数，不依赖 Bukkit 服务器。
 */
class MessagesTest {
    @Test
    void 颜色码只转换模板_插入文本原样保留() {
        assertEquals("§f新题目: 2 & 3", Messages.fillPlaceholders("&f新题目: {arg}", null, "2 & 3", null));
    }

    @Test
    void cmd占位符替换命令别名() {
        assertEquals("§e/lma help", Messages.fillPlaceholders("&e/{cmd} help", "lma", null, null));
    }

    @Test
    void arg与arg2同时替换() {
        assertEquals("§a1 2", Messages.fillPlaceholders("&a{arg} {arg2}", null, "1", "2"));
    }

    @Test
    void cmd为空时msg3用arg回填() {
        // 老服 status-total 默认值曾借用 {cmd} 传累计出题数的兼容逻辑（回填在 msg3，不在 fillPlaceholders）
        assertEquals("7", new Messages(() -> null).msg3("status-total", "{cmd}", null, "7", null));
    }

    @Test
    void 插入的文本不会被二次扫描() {
        // arg 的值恰好长得像占位符时，必须只写入不再替换
        assertEquals("{arg2}", Messages.fillPlaceholders("{arg}", null, "{arg2}", "B"));
    }

    @Test
    void 参数为空时占位符原样保留() {
        assertEquals("x{arg}y", Messages.fillPlaceholders("x{arg}y", null, null, null));
    }
}
