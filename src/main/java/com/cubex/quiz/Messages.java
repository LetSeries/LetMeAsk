package com.cubex.quiz;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.function.Supplier;

/**
 * 消息中心：前缀缓存 + msg/msg2/msg3 可配置消息。
 * 从 QuizPlugin 拆出；持配置 Supplier，reload 后自动读新配置，无需重建。
 */
public class Messages {
    private final Supplier<FileConfiguration> configSupplier;
    private volatile String cachedPrefix = null; // prefix 缓存：reload/语言切换时失效
    // 语言开关：zh=中文（默认），en=英文；读 base.yml 的 language 键
    private volatile String language = "zh";

    public Messages(Supplier<FileConfiguration> configSupplier) {
        this.configSupplier = configSupplier;
    }

    /** base.yml 已重载时调用，前缀缓存失效。 */
    public void invalidateCache() {
        cachedPrefix = null;
    }

    /** 设置语言（仅接受 zh/en，其他值回退 zh）。 */
    public void setLanguage(String lang) {
        String next = "en".equalsIgnoreCase(lang) ? "en" : "zh";
        if (!next.equals(language)) {
            language = next;
            cachedPrefix = null; // 语言切换，前缀缓存失效
        }
    }

    public String getLanguage() {
        return language;
    }

    public String prefix() {
        String hit = cachedPrefix;
        if (hit != null) return hit;
        FileConfiguration cfg = configSupplier.get();
        String prefix = cfg == null ? "&6[教育部]" : lookup(cfg, "prefix", "&6[教育部]");
        hit = prefix.replace('&', '§');
        cachedPrefix = hit;
        return hit;
    }

    /** 可配置消息：读 messages.<key>，缺失用默认值；支持 & 颜色码与 {arg} 占位。 */
    public String msg(String key, String def, String arg) {
        return msg3(key, def, null, arg, null);
    }

    /** 双占位版本：{cmd} 为命令别名（如 lma），{arg} 为其他参数。 */
    public String msg2(String key, String def, String cmd, String arg) {
        return msg3(key, def, cmd, arg, null);
    }

    /**
     * 三占位版本：在 msg2 基础上加 {arg2}，用于需要两个数字参数的消息（如累计出题/答对）。
     * 兼容逻辑：cmd 为空时用 arg 回填 {cmd}（老服 base.yml 的 status-total 默认值曾借用 {cmd} 传累计出题数，
     * saveResource 不覆盖旧文件，不能指望老服自动更新默认值）。
     */
    /**
     * 按语言查消息：en 时优先读 messages-en.<key>，缺失回退 messages.<key>，再缺失用代码默认值。
     * 老服没有 messages-en 段也能正常工作（全回退中文），无感升级。
     */
    private String lookup(FileConfiguration cfg, String key, String def) {
        if ("en".equals(language)) {
            String en = cfg.getString("messages-en." + key, null);
            if (en != null) return en;
        }
        return cfg.getString("messages." + key, def);
    }

    public String msg3(String key, String def, String cmd, String arg, String arg2) {
        FileConfiguration cfg = configSupplier.get();
        String s = cfg == null ? def : lookup(cfg, key, def);
        if (cmd != null) {
            s = s.replace("{cmd}", cmd);
        } else if (arg != null) {
            // 兼容旧版 status-total 默认值（曾借用 {cmd} 传累计出题数）：cmd 为空时用 arg 回填
            s = s.replace("{cmd}", arg);
        }
        if (arg != null) s = s.replace("{arg}", arg);
        if (arg2 != null) s = s.replace("{arg2}", arg2);
        return s.replace('&', '§');
    }
}
