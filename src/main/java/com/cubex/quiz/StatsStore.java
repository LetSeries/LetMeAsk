package com.cubex.quiz;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 答题统计存储：累计出题/答对数、每人答对次数与奖金、玩家名缓存。
 * 从 QuizPlugin 拆出；线程模型不变——
 * recordCorrect 走主线程，save 走异步定时任务，statsLock 互斥；
 * 磁盘 IO 在 statsSaveLock 下串行，主线程只被短暂快照拷贝阻塞。
 */
public class StatsStore {
    private final Logger logger;
    private final File statsFile;
    private FileConfiguration statsCfg;

    private final Map<String, Integer> totalCorrect = new HashMap<>();
    private final Map<String, Double> totalEarned = new HashMap<>();
    private final Set<String> statsDirty = new HashSet<>(); // 自上次落盘后有变更的玩家 key
    private final Map<String, String> nameCache = new HashMap<>(); // UUID 字符串 -> 最后已知玩家名（top 榜免查）
    private long totalAsked = 0L;
    private long totalAnswered = 0L;

    private final Object statsLock = new Object();
    private final Object statsSaveLock = new Object();

    public StatsStore(Logger logger, File dataFolder) {
        this.logger = logger;
        this.statsFile = new File(dataFolder, "stats.yml");
    }

    /** 从 stats.yml 加载累计统计。 */
    public void load() {
        statsCfg = YamlConfiguration.loadConfiguration(statsFile);
        synchronized (statsLock) {
            totalCorrect.clear();
            totalEarned.clear();
            nameCache.clear();
            statsDirty.clear();
            ConfigurationSection players = statsCfg.getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    totalCorrect.put(key, players.getInt(key + ".correct", 0));
                    totalEarned.put(key, players.getDouble(key + ".earned", 0.0));
                    String n = players.getString(key + ".name");
                    if (n != null && !n.isEmpty()) nameCache.put(key, n);
                }
            }
            totalAsked = statsCfg.getLong("total-asked", 0L);
            totalAnswered = statsCfg.getLong("total-answered", 0L);
        }
    }

    /**
     * 写回 stats.yml。incremental=true 时只写 dirty 玩家+累计计数（30s 高频任务用），
     * false 时全量写回（5 分钟任务与关服时用）。
     * 快照在 statsLock 下拷贝后释放锁，磁盘 IO 在 statsSaveLock 下串行。
     */
    public void save(boolean incremental) {
        if (statsCfg == null) return;
        final long asked;
        final long answered;
        final Map<String, Integer> correctSnap;
        final Map<String, Double> earnedSnap;
        final Map<String, String> nameSnap;
        final Set<String> dirtySnap;
        synchronized (statsLock) {
            if (incremental && statsDirty.isEmpty()) return; // 无变更时连文件都不碰
            asked = totalAsked;
            answered = totalAnswered;
            if (incremental) {
                dirtySnap = new HashSet<>(statsDirty);
                correctSnap = new HashMap<>();
                earnedSnap = new HashMap<>();
                nameSnap = new HashMap<>();
                for (String uuid : dirtySnap) {
                    correctSnap.put(uuid, totalCorrect.getOrDefault(uuid, 0));
                    earnedSnap.put(uuid, totalEarned.getOrDefault(uuid, 0.0));
                    String n = nameCache.get(uuid);
                    if (n != null) nameSnap.put(uuid, n);
                }
                statsDirty.clear();
            } else {
                dirtySnap = null;
                correctSnap = new HashMap<>(totalCorrect);
                earnedSnap = new HashMap<>(totalEarned);
                nameSnap = new HashMap<>(nameCache);
                statsDirty.clear();
            }
        }
        synchronized (statsSaveLock) {
            try {
                statsCfg.set("total-asked", asked);
                statsCfg.set("total-answered", answered);
                if (incremental) {
                    for (String uuid : dirtySnap) {
                        String key = "players." + uuid;
                        statsCfg.set(key + ".correct", correctSnap.getOrDefault(uuid, 0));
                        statsCfg.set(key + ".earned", earnedSnap.getOrDefault(uuid, 0.0));
                        String n = nameSnap.get(uuid);
                        if (n != null) statsCfg.set(key + ".name", n);
                    }
                } else {
                    for (Map.Entry<String, Integer> e : correctSnap.entrySet()) {
                        String key = "players." + e.getKey();
                        statsCfg.set(key + ".correct", e.getValue());
                        statsCfg.set(key + ".earned", earnedSnap.getOrDefault(e.getKey(), 0.0));
                        String n = nameSnap.get(e.getKey());
                        if (n != null) statsCfg.set(key + ".name", n);
                    }
                }
                statsCfg.save(statsFile);
            } catch (Exception ex) {
                logger.log(java.util.logging.Level.WARNING, "保存 stats.yml 失败", ex);
            }
        }
    }

    /** 全量保存（关服与 5 分钟任务用）。 */
    public void save() {
        save(false);
    }

    /** 出题计数+1（主线程调用）。 */
    public void incrementAsked() {
        synchronized (statsLock) {
            totalAsked++;
        }
    }

    /**
     * 记录一次答对：累计次数与实发金额（0 奖励/自答/无 Vault 时金额为 0 也计数）。
     */
    public void recordCorrect(Player player, double earned) {
        synchronized (statsLock) {
            String key = player.getUniqueId().toString();
            totalCorrect.merge(key, 1, Integer::sum);
            if (earned > 0.0) totalEarned.merge(key, earned, Double::sum);
            else totalEarned.putIfAbsent(key, 0.0);
            if (player.getName() != null) nameCache.put(key, player.getName());
            statsDirty.add(key);
            totalAnswered++;
        }
    }

    /**
     * UUID 反查最后已知玩家名：先读内存缓存，未命中再查 Bukkit（UUID 版走内存映射，不碰磁盘）。
     * 仍无则回退显示 UUID 前 8 位。Bukkit 查询放锁外，避免拖长临界区。
     */
    public String displayNameOf(String uuidKey) {
        synchronized (statsLock) {
            String cached = nameCache.get(uuidKey);
            if (cached != null) return cached;
        }
        String found = null;
        try {
            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(java.util.UUID.fromString(uuidKey));
            found = off.getName();
        } catch (IllegalArgumentException ignored) {}
        if (found != null) {
            cacheName(uuidKey, found);
            return found;
        }
        return uuidKey.length() > 8 ? uuidKey.substring(0, 8) : uuidKey;
    }

    /** 线程安全地缓存玩家名。 */
    public void cacheName(String uuidKey, String name) {
        if (uuidKey == null || name == null) return;
        synchronized (statsLock) {
            nameCache.put(uuidKey, name);
        }
    }

    public long getTotalAsked() {
        synchronized (statsLock) {
            return totalAsked;
        }
    }

    public long getTotalAnswered() {
        synchronized (statsLock) {
            return totalAnswered;
        }
    }

    public int getCorrect(String uuidKey) {
        synchronized (statsLock) {
            return totalCorrect.getOrDefault(uuidKey, 0);
        }
    }

    public double getEarned(String uuidKey) {
        synchronized (statsLock) {
            return totalEarned.getOrDefault(uuidKey, 0.0);
        }
    }

    public boolean isEmpty() {
        synchronized (statsLock) {
            return totalCorrect.isEmpty();
        }
    }

    /** 排行快照：拷贝后排序，调用方遍历无锁安全（防 CME）。 */
    public List<Map.Entry<String, Integer>> sortedSnapshot() {
        final List<Map.Entry<String, Integer>> sorted;
        synchronized (statsLock) {
            sorted = new ArrayList<>(totalCorrect.entrySet());
        }
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return sorted;
    }
}
