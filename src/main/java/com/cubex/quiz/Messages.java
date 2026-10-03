package com.cubex.quiz;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.function.Supplier;

/**
 * 消息中心：前缀缓存 + msg/msg2/msg3 可配置消息。
 * 从 QuizPlugin 拆出；持配置 Supplier，reload 后自动读新配置，无需重建。
 */
public class Messages {
    private final Supplier<FileConfiguration> configSupplier;
    private volatile String cachedPrefix = null; // prefix 缓存：reload 时失效

    public Messages(Supplier<FileConfiguration> configSupplier) {
        this.configSupplier = configSupplier;
    }

    /** base.yml 已重载时调用，前缀缓存失效。 */
    public void invalidateCache() {
        cachedPrefix = null;
    }

    public String prefix() {
        String hit = cachedPrefix;
        if (hit != null) return hit;
        FileConfiguration cfg = configSupplier.get();
        String prefix = cfg == null ? "&6[教育部]" : cfg.getString("messages.prefix", "&6[教育部]");
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
    public String msg3(String key, String def, String cmd, String arg, String arg2) {
        FileConfiguration cfg = configSupplier.get();
        String s = cfg == null ? def : cfg.getString("messages." + key, def);
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
