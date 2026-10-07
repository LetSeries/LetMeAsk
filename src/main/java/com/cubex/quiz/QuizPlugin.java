package com.cubex.quiz;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;

/**
 * 抢答活动插件（CubeX 兼容布局）。
 * - 经济走 Vault（推荐用 CMI 提供的 Vault 实现）。
 * - 人机验证对接外部 HumanVerifyApi；若对方包名不同请自行调整。
 *   注意不要把它们加进 pom 依赖，全部走反射调用以便优雅降级。
 */
public class QuizPlugin extends JavaPlugin implements Listener {
    // 子系统：经济桥接 / 答案匹配 / 消息中心（大重构拆出，主类只保留状态机与流程编排）
    // 注意：EconomyBridge 构造需要 Logger，getLogger() 在字段初始化阶段不可用，onEnable 里再 new；
    // Messages 持 baseCfg 的方法引用，lambda 在调用时才求值，不存在前向引用问题
    EconomyBridge economy;
    final QuestionMatcher matcher = new QuestionMatcher();
    final Messages messages = new Messages(this::getBaseCfg);

    private final Random random = new Random();

    // 运行时状态
    private volatile Question currentQuestion = null;
    private volatile boolean paused = false; // paused due to payer insufficient funds
    private volatile boolean verifying = false; // question locked while human verification pending
    private volatile java.util.UUID verifyingPlayer = null;
    private volatile long verifyStartMillis = 0L;
    private volatile long verifyEpoch = 0L; // 验证轮次：reload/force/超时解锁时自增，旧回调直接丢弃
    private volatile long nextPostAtMillis = 0L; // when the next question may be posted
    private volatile boolean economyAvailable = false;

    // 配置项缓存
    private String payerName;
    private double rewardAmount;
    private long questionIntervalSeconds;
    private long questionTimeoutSeconds;
    private double antiBotThresholdSeconds;
    private int antiBotCorrectAnswerThreshold;
    private long antiBotStreakWindowSeconds;
    // 异步聊天线程会读 antiBotChat*/celebrate*（reload 在主线程写），加 volatile 保证可见性；
    // 模糊阈值已搬进 QuestionMatcher（自带 volatile），主类不再持有
    private volatile int antiBotChatHistoryCount;
    private volatile double antiBotChatMinIntervalSeconds;
    private long verifyTimeoutSeconds;
    private long balanceRetrySeconds = 30L; // 暂停后每隔多少秒复查一次出资人余额
    private volatile long nextBalanceCheckMillis = 0L;
    private boolean leaderboardBroadcastEnabled = true;
    private long leaderboardBroadcastMinutes = 60L;
    private int leaderboardBroadcastCount = 10;
    private volatile boolean celebrateEnabled = true;
    private volatile String celebrateTitle = "§6§l答对了！";
    private volatile String celebrateSubtitle = "§e+{reward} 金币";
    private volatile String celebrateSound = "ENTITY_PLAYER_LEVELUP";
    private volatile float celebrateVolume = 1.0f;
    private volatile float celebratePitch = 1.0f;

    // 出资人解析已搬进 EconomyBridge（resolvePayer/isPayer/getPayerDisplay）

    // 配置文件
    private File baseFile;
    private File questionsFile;
    private FileConfiguration baseCfg;
    private FileConfiguration questionsCfg;

    /** 供 Messages 子系统惰性读取 base.yml 配置。 */
    FileConfiguration getBaseCfg() {
        return baseCfg;
    }

    // 定时任务句柄
    private BukkitTask tickerTask;
    private BukkitTask leaderboardTask;
    private BukkitTask statsSaveTask;
    private final Map<java.util.UUID, Integer> correctAnswerCounts = new HashMap<>();
    private final Map<java.util.UUID, Long> lastCorrectTimes = new HashMap<>();
    private final Map<java.util.UUID, ChatHistory> recentChatMessages = new HashMap<>();

    // 答题统计（持久化到 stats.yml，key 为玩家 UUID 字符串）
    private File statsFile;
    private FileConfiguration statsCfg;
    private final Map<String, Integer> totalCorrect = new HashMap<>();
    private final Map<String, Double> totalEarned = new HashMap<>();
    private final Set<String> statsDirty = new HashSet<>(); // 自上次落盘后有变更的玩家 key
    private final Map<String, String> nameCache = new HashMap<>(); // UUID 字符串 -> 最后已知玩家名（top 榜免查）
    private volatile long totalAsked = 0L;
    private volatile long totalAnswered = 0L;
    // 统计独立锁：saveStats 走异步线程，recordCorrect 走主线程；不用 this 锁，避免异步落盘阻塞主线程答题
    private final Object statsLock = new Object();
    // 落盘独占锁：快照在 statsLock 下拷贝，磁盘 IO 在此锁下串行，主线程发奖全程不被 IO 阻塞
    private final Object statsSaveLock = new Object();
    // 答题专用锁：handleCorrectAnswer 不再用 synchronized 方法，避免占用插件 this 监视器
    private final Object answerLock = new Object();

    @Override
    public void onEnable() {
        // 释放默认配置文件（不存在时才写，不覆盖老服已有配置）
        saveResource("base.yml", false);
        saveResource("questions.yml", false);

        // load configuration files
        if (!loadConfigValues()) {
            getLogger().severe("启动时题库为空，禁用插件。请在 questions.yml 中添加题目后重启。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economy = new EconomyBridge(getLogger());
        economyAvailable = setupEconomy();
        if (!economyAvailable) {
            getLogger().warning("未找到 Vault 经济插件：以“仅公告、无奖励”模式运行，安装 Vault 后请重启或重载插件");
        } else {
            getLogger().info("已连接经济后端: " + (economyProviderName() != null ? economyProviderName() : "未知"));
            if (payerIsServer() && !economyBankSupport()) {
                getLogger().warning("经济后端 " + (economyProviderName() != null ? economyProviderName() : "未知")
                        + " 未实现银行账户 API，将按账户名 \"" + payerName + "\" 扣款。"
                        + "请确保该账户存在且有余额，否则出题会因“资金不足”暂停；"
                        + "XConomy 可在 config.yml 开启 non-player-account 并给该账户充值。");
            }
        }

        getServer().getPluginManager().registerEvents(this, this);

        // register command（别名 lma 在 plugin.yml 中声明）
        if (getCommand("letmeask") != null) {
            QuizCommand quizCommand = new QuizCommand();
            getCommand("letmeask").setExecutor(quizCommand);
            getCommand("letmeask").setTabCompleter(quizCommand);
        }

        // 启动定时出题任务（同时处理暂停恢复检查）
        startTask();

        loadStats();
        startLeaderboardTask();
        // 统计落盘：30 秒增量写（只写有变更的玩家），每 10 次做一次全量（约 5 分钟）
        statsSaveTask = new BukkitRunnable() {
            private int runs = 0;
            @Override
            public void run() {
                saveStats(++runs % 10 != 0);
            }
        }.runTaskTimerAsynchronously(this, 600L, 600L);

        getLogger().info("QuizPlugin enabled");
    }

    @Override
    public void onDisable() {
        stopTask();
        if (leaderboardTask != null && !leaderboardTask.isCancelled()) {
            leaderboardTask.cancel();
            leaderboardTask = null;
        }
        if (statsSaveTask != null && !statsSaveTask.isCancelled()) {
            statsSaveTask.cancel();
            statsSaveTask = null;
        }
        saveStats();
        getLogger().info("QuizPlugin disabled");
    }

    /** 从 stats.yml 加载累计统计。 */
    private void loadStats() {
        statsFile = new File(getDataFolder(), "stats.yml");
        statsCfg = YamlConfiguration.loadConfiguration(statsFile);
        totalCorrect.clear();
        totalEarned.clear();
        nameCache.clear();
        org.bukkit.configuration.ConfigurationSection players = statsCfg.getConfigurationSection("players");
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

    /**
     * 写回 stats.yml。incremental=true 时只写 dirty 玩家+累计计数（30s 高频任务用），
     * false 时全量写回（5 分钟任务与关服时用）。
     * 调用方注意线程：定时任务走异步，onDisable 走主线程。
     * 快照在 statsLock 下拷贝后释放锁，磁盘 IO 在 statsSaveLock 下串行——
     * 主线程 recordCorrect 只被短暂拷贝阻塞，不再被慢 IO 卡住。
     */
    private void saveStats(boolean incremental) {
        if (statsCfg == null || statsFile == null) return;
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
                getLogger().log(Level.WARNING, "保存 stats.yml 失败", ex);
            }
        }
    }

    /** 全量保存（关服与 5 分钟任务用）。 */
    private void saveStats() {
        saveStats(false);
    }

    /**
     * 记录一次答对：累计次数与实发金额（0 奖励/自答/无 Vault 时金额为 0 也计数）。
     * statsLock：awardWinner 走主线程，saveStats 走异步定时任务，需与保存互斥。
     */
    private void recordCorrect(Player player, double earned) {
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
     * 仍无则回退显示 UUID 前 8 位。
     * Bukkit 查询放锁外：只做缓存读写加锁，避免拖长临界区。
     */
    private String displayNameOf(String uuidKey) {
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

    /** 线程安全地缓存玩家名（sendStats 走命令线程，直接写 map 会与异步保存竞态）。 */
    private void cacheName(String uuidKey, String name) {
        if (uuidKey == null || name == null) return;
        synchronized (statsLock) {
            nameCache.put(uuidKey, name);
        }
    }



    /**
     * 加载配置。先验题库再应用 base.yml：题库为空则整体回滚（旧题库+旧配置都不动），
     * 返回 false；调用方据此决定禁用插件（启动时）还是报错继续运行（reload 时）。
     */
    private boolean loadConfigValues() {
        // load base and questions from their own files
        baseFile = new File(getDataFolder(), "base.yml");
        questionsFile = new File(getDataFolder(), "questions.yml");
        try {
            if (!baseFile.exists()) saveResource("base.yml", false);
            if (!questionsFile.exists()) saveResource("questions.yml", false);
        } catch (Exception ignored) {}

        FileConfiguration newBase = YamlConfiguration.loadConfiguration(baseFile);
        FileConfiguration newQuestions = YamlConfiguration.loadConfiguration(questionsFile);

        // 先验题库：解析到临时列表，空则整体回滚（base.yml 字段与出资人都不动）
        // 条目可以是纯字符串（"题目=答案"，权重 1），也可以是 map（{q, a, weight}）
        List<?> raw = newQuestions.getList("questions");
        boolean usingFallback = false;
        if (raw == null || raw.isEmpty()) {
            if (questions.isEmpty()) {
                // 首次启动且无题库：用内置示例兜底
                raw = Arrays.asList(
                        "中国首都=北京",
                        "2+2=4",
                        "香蕉是什么颜色=黄色"
                );
                usingFallback = true;
                getLogger().warning("questions.yml 中没有题目，使用内置示例题目。请在 questions.yml 中配置 questions 字段（格式：题目=答案）");
            } else {
                // reload 时新题库为空：整体回滚，不中断运行
                getLogger().warning("questions.yml 中没有可用题目，已保留旧题库与旧配置（" + questions.size() + " 题）。请检查配置后重新 reload。");
                return false;
            }
        }

        List<Question> parsed = parseQuestions(raw);
        if (parsed.isEmpty()) {
            if (questions.isEmpty()) {
                getLogger().severe("没有可用题目，插件无法正常出题。请在 questions.yml 中添加题目。");
                return false;
            }
            getLogger().warning("新题库解析后为空，已保留旧题库与旧配置（" + questions.size() + " 题）。");
            return false;
        }

        // 题库可用：提交新配置
        baseCfg = newBase;
        questionsCfg = newQuestions;
        messages.invalidateCache(); // base.yml 已重载，前缀缓存失效

        payerName = baseCfg.getString("payer", "Server");
        rewardAmount = Math.max(0.0, baseCfg.getDouble("reward", 50.0));
        questionIntervalSeconds = Math.max(5L, baseCfg.getLong("question-interval-seconds", 60L));
        questionTimeoutSeconds = Math.max(0L, baseCfg.getLong("question-timeout-seconds", 30L));
        antiBotThresholdSeconds = Math.max(0.0, baseCfg.getDouble("anti-bot-threshold-seconds", 1.0));
        antiBotCorrectAnswerThreshold = Math.max(0, baseCfg.getInt("anti-bot-correct-answer-threshold", 3));
        antiBotStreakWindowSeconds = Math.max(0L, baseCfg.getLong("anti-bot-streak-window-seconds", 300L));
        antiBotChatHistoryCount = Math.max(2, baseCfg.getInt("anti-bot-chat-history-count", 3));
        antiBotChatMinIntervalSeconds = Math.max(0.0, baseCfg.getDouble("anti-bot-chat-min-interval-seconds", 0.5));
        verifyTimeoutSeconds = Math.max(10L, baseCfg.getLong("verify-timeout-seconds", 120L));
        balanceRetrySeconds = Math.max(5L, baseCfg.getLong("balance-retry-seconds", 30L));
        matcher.setFuzzySimilarityThreshold(
                baseCfg.getDouble("fuzzy-similarity-threshold", matcher.getFuzzySimilarityThreshold()));
        leaderboardBroadcastEnabled = baseCfg.getBoolean("leaderboard-broadcast.enabled", true);
        leaderboardBroadcastMinutes = Math.max(1L, baseCfg.getLong("leaderboard-broadcast.minutes", 60L));
        leaderboardBroadcastCount = Math.min(20, Math.max(1, baseCfg.getInt("leaderboard-broadcast.count", 10)));
        celebrateEnabled = baseCfg.getBoolean("celebrate.enabled", true);
        celebrateTitle = baseCfg.getString("celebrate.title", "§6§l答对了！");
        celebrateSubtitle = baseCfg.getString("celebrate.subtitle", "§e+{reward} 金币");
        celebrateSound = baseCfg.getString("celebrate.sound", "ENTITY_PLAYER_LEVELUP");
        celebrateVolume = (float) Math.max(0.0, baseCfg.getDouble("celebrate.volume", 1.0));
        celebratePitch = (float) Math.max(0.0, baseCfg.getDouble("celebrate.pitch", 1.0));

        // resolve payer to a stable identifier (UUID/name/Server/LittleSkin)
        resolvePayer(payerName);

        questions.clear();
        questions.addAll(parsed);
        return !usingFallback || !questions.isEmpty();
    }

    /** 单题权重上限：防止极端配置下加权求和 int 溢出。 */
    private static final int MAX_WEIGHT = 10000;

    private static int clampWeight(int w) {
        return Math.min(MAX_WEIGHT, Math.max(1, w));
    }

    /**
     * 解析题库条目。支持两种格式（可混用）：
     * <ul>
     *   <li>纯字符串: "题目=答案1|答案2"，权重默认为 1</li>
     *   <li>map: {q: "题目", a: "答案1|答案2", weight: 3}，weight 越大越容易被抽中（1~10000）</li>
     * </ul>
     * 非法行与重复题目跳过。
     */
    private List<Question> parseQuestions(List<?> raw) {
        List<Question> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object entry : raw) {
            String q;
            String a;
            int weight = 1;
            if (entry instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) entry;
                Object qObj = map.get("q");
                Object aObj = map.get("a");
                if (qObj == null || aObj == null) continue;
                // map 格式直取 q/a：题目本身含 = 号时（如数学题）拼接再切分会切错
                q = qObj.toString().trim();
                a = aObj.toString().trim();
                Object wObj = map.get("weight");
                if (wObj instanceof Number) {
                    weight = clampWeight(((Number) wObj).intValue());
                } else if (wObj != null) {
                    getLogger().warning("题目权重不是数字，已按 1 处理: " + q);
                }
            } else if (entry instanceof String) {
                String line = (String) entry;
                String[] parts = line.split("=", 2);
                if (parts.length < 2) parts = line.split(":", 2);
                if (parts.length < 2) continue;
                q = parts[0].trim();
                a = parts[1].trim();
            } else {
                continue;
            }
            if (q.isEmpty() || a.isEmpty()) continue;
            if (!seen.add(q)) {
                getLogger().warning("题库存在重复题目，已跳过: " + q);
                continue;
            }
            List<String> answers = new ArrayList<>();
            for (String alt : a.split("\\|")) {
                String t = alt.trim();
                if (!t.isEmpty()) answers.add(t);
            }
            if (answers.isEmpty()) {
                seen.remove(q);
                continue;
            }
            parsed.add(new Question(q, answers, weight));
        }
        return parsed;
    }

    private void startTask() {
        stopTask();
        nextPostAtMillis = System.currentTimeMillis() + questionIntervalSeconds * 1000L;
        tickerTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (Throwable t) {
                    getLogger().log(Level.SEVERE, "Error in quiz tick", t);
                }
            }
        }.runTaskTimer(this, 20L, 20L); // 1s 粒度，保证超时与验证兜底准时
    }

    private void startLeaderboardTask() {
        if (leaderboardTask != null && !leaderboardTask.isCancelled()) {
            leaderboardTask.cancel();
            leaderboardTask = null;
        }
        if (!leaderboardBroadcastEnabled) return; // 关闭定时广播
        long periodTicks = Math.max(1L, leaderboardBroadcastMinutes) * 60L * 20L;
        final int count = Math.min(20, Math.max(1, leaderboardBroadcastCount));
        leaderboardTask = new BukkitRunnable() {
            @Override
            public void run() {
                broadcastTop(count);
            }
        }.runTaskTimer(this, periodTicks, periodTicks);
    }

    private void broadcastTop(int count) {
        // 空榜时跳过定时广播：手动 /top 仍提示“暂无记录”，但别每小时刷屏打扰玩家
        synchronized (statsLock) {
            if (totalCorrect.isEmpty()) return;
        }
        List<String> messages = topMessages(count);
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (String message : messages) {
                sendLegacy(player, message);
            }
        }
    }

    private List<String> topMessages(int count) {
        // 快照拷贝：统计 Map 受 statsLock 保护，异步落盘线程会并发读写，直接遍历会抛 CME
        final List<Map.Entry<String, Integer>> sorted;
        final Map<String, Double> earned;
        synchronized (statsLock) {
            sorted = new ArrayList<>(totalCorrect.entrySet());
            earned = new HashMap<>(totalEarned);
        }
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (sorted.isEmpty()) {
            return Collections.singletonList(msg("no-records", "&e暂无答题记录", null));
        }

        List<String> messages = new ArrayList<>();
        messages.add("§6答题排行榜 §7(前 " + Math.min(count, sorted.size()) + " 名):");
        int rank = 0;
        for (Map.Entry<String, Integer> entry : sorted) {
            if (++rank > count) break;
            messages.add(" §e" + rank + ". §f" + displayNameOf(entry.getKey()) + " §7答对 §f" + entry.getValue()
                    + " §7奖金 §e" + String.format(Locale.ROOT, "%,.2f", earned.getOrDefault(entry.getKey(), 0.0)));
        }
        return messages;
    }

    // 消息转发到 Messages 子系统（渐进迁移：调用方不动，行为零变化）
    private String messagePrefix() {
        return messages.prefix();
    }

    // Bukkit 传统文本 API 兼容层，Paper 与 Spigot 通用。
    @SuppressWarnings("deprecation")
    private void broadcastLegacy(String message) {
        Bukkit.broadcastMessage(message);
    }

    @SuppressWarnings("deprecation")
    private void kickLegacy(Player player, String reason) {
        player.kickPlayer(reason);
    }

    @SuppressWarnings("deprecation")
    private void sendLegacy(Player player, String message) {
        player.sendMessage(message);
    }

    @SuppressWarnings("deprecation")
    private void showLegacyTitle(Player player, String title, String subtitle) {
        player.sendTitle(title, subtitle);
    }

    // 消息转发到 Messages 子系统（渐进迁移：调用方不动，行为零变化）
    private String msg(String key, String def, String arg) {
        return messages.msg(key, def, arg);
    }

    private String msg2(String key, String def, String cmd, String arg) {
        return messages.msg2(key, def, cmd, arg);
    }

    private String msg3(String key, String def, String cmd, String arg, String arg2) {
        return messages.msg3(key, def, cmd, arg, arg2);
    }

    // 出资人解析转发到 EconomyBridge（渐进迁移：调用方不动，行为零变化）
    private void resolvePayer(String payer) {
        economy.resolvePayer(payer);
    }

    private boolean isPayer(Player player) {
        return economy.isPayer(player);
    }

    private String payerDisplay() {
        return economy.getPayerDisplay();
    }

    private boolean payerIsServer() {
        return economy.isPayerServer();
    }

    private void stopTask() {
        if (tickerTask != null && !tickerTask.isCancelled()) {
            tickerTask.cancel();
            tickerTask = null;
        }
    }

    private boolean postNewQuestion(boolean force) {
        if (!force && (currentQuestion != null || verifying || paused)) return false;
        if (force && verifying) {
            // 管理员强制出题：先解锁验证状态，并清掉被验证者的连击，避免旧回调干扰新题
            if (verifyingPlayer != null) resetStreak(verifyingPlayer);
            clearQuestionState();
        }
        publishQuestion();
        nextPostAtMillis = System.currentTimeMillis() + questionIntervalSeconds * 1000L;
        return true;
    }

    private void publishQuestion() {
        Question q = pickQuestionAvoidRepeat();
        if (q == null) return;
        q.postTime = System.currentTimeMillis();
        q.id = java.util.UUID.randomUUID();
        currentQuestion = q;
        synchronized (recentChatMessages) {
            recentChatMessages.clear();
        }
        totalAsked++;
        broadcastLegacy(messagePrefix() + msg("announce-question", " §f新题目: §f{arg}", q.question));
    }

    /**
     * 按权重随机选题；题库多于 1 题时保证连续两题不同。
     * 权重全为 1 时退化为均匀随机。
     */
    private Question pickQuestionAvoidRepeat() {
        if (questions.isEmpty()) return null;
        if (questions.size() == 1) return questions.get(0);
        String last = currentQuestion == null ? null : currentQuestion.question;
        Question q = null;
        int guard = 0;
        do {
            q = pickWeighted();
            guard++;
        } while (last != null && q != null && last.equals(q.question) && guard < 10);
        return q != null ? q : questions.get(random.nextInt(questions.size()));
    }

    /** 加权随机：权重越大越容易被抽中（long 求和，极端题库也不溢出）。 */
    private Question pickWeighted() {
        long total = 0;
        for (Question q : questions) total += q.weight;
        if (total <= 0) return questions.get(questions.size() - 1);
        long r = (random.nextLong() & Long.MAX_VALUE) % total;
        for (Question q : questions) {
            r -= q.weight;
            if (r < 0) return q;
        }
        return questions.get(questions.size() - 1);
    }

    // 答案匹配转发到 QuestionMatcher（渐进迁移：调用方不动，行为零变化）
    private boolean matchesAny(String providedNormalized, List<String> normalizedAnswers) {
        return matcher.matchesAny(providedNormalized, normalizedAnswers);
    }

    private static String normalize(String s) {
        return QuestionMatcher.normalize(s);
    }

    private final List<Question> questions = new ArrayList<>();

    private void tick() {
        if (!economyAvailable) {
            // 降级模式：无 Vault 时不暂停出题逻辑，但 awardWinner 只发公告
            if (paused) paused = false;
        } else if (paused) {
            // 暂停中：节流复查余额（默认 30 秒一次），避免每秒打一次 Vault 后端
            long nowMs = System.currentTimeMillis();
            if (nowMs < nextBalanceCheckMillis) return;
            nextBalanceCheckMillis = nowMs + balanceRetrySeconds * 1000L;
            double bal = getBalanceOf(payerDisplay());
            // 恢复阈值加 1 美分滞后：余额恰好等于奖励时恢复后下一题又暂停，来回横跳刷屏
            if (bal >= rewardAmount + 0.01) {
                paused = false;
                String countText = rewardAmount > 0.0
                        ? String.format(Locale.ROOT, "%,d", (long) (bal / rewardAmount))
                        : "";
                broadcastLegacy(messagePrefix() + msg3("announce-funded",
                        " §a资金已足额，恢复出题。当前余额: {arg}，预计还可以奖励{arg2}次。",
                        null, String.format(Locale.ROOT, "%,.2f", bal), countText));
            } else {
                return;
            }
        }

        // 验证超时兜底：回调永不返回时解锁，避免永久锁死
        if (verifying) {
            Player p = verifyingPlayer == null ? null : Bukkit.getPlayer(verifyingPlayer);
            long elapsedMillis = System.currentTimeMillis() - verifyStartMillis;
            if (elapsedMillis >= verifyTimeoutSeconds * 1000L) {
                // 注意：先清连击再清题目，与验证失败/刷屏踢人分支一致；
                // 否则被踢玩家重进后连击残留，下次答对直接再触发验证
                if (verifyingPlayer != null) resetStreak(verifyingPlayer);
                if (p != null) {
                    getLogger().warning(p.getName() + "人机验证超时（" + verifyTimeoutSeconds + "s），自动解锁作废本轮题目，并踢出玩家");
                    broadcastLegacy(messagePrefix() + msg("announce-verify-timeout-kick", " §c玩家 {arg} 因人机验证超时被踢出服务器，本轮题目作废。", p.getName()));
                    kickLegacy(p, msg("kick-verify-timeout", "人机验证超时", null));
                } else {
                    getLogger().warning("人机验证超时（" + verifyTimeoutSeconds + "s），玩家已离线，自动解锁并作废本轮题目");
                    broadcastLegacy(messagePrefix() + msg("announce-verify-timeout", " §c人机验证超时，本轮题目作废。", null));
                }
                clearQuestionState();
            }
            return;
        }

        // 题目超时：公布答案、清空连击、安排下一题
        if (currentQuestion != null) {
            if (questionTimeoutSeconds > 0) {
                long elapsedMillis = System.currentTimeMillis() - currentQuestion.postTime;
                if (elapsedMillis >= questionTimeoutSeconds * 1000L) {
                    broadcastLegacy(messagePrefix() + msg("announce-timeout", " §c无人答对！答案是: §f{arg}", currentQuestion.displayAnswer()));
                    correctAnswerCounts.clear();
                    lastCorrectTimes.clear();
                    clearQuestionState();
                    nextPostAtMillis = System.currentTimeMillis() + questionIntervalSeconds * 1000L;
                }
            }
            return;
        }

        // 到点出题
        if (System.currentTimeMillis() >= nextPostAtMillis) {
            publishQuestion();
            nextPostAtMillis = System.currentTimeMillis() + questionIntervalSeconds * 1000L;
        }
    }

    /** 清理本轮题目与验证状态（验证通过/失败/超时统一入口）。epoch 自增使旧回调失效。 */
    private void clearQuestionState() {
        currentQuestion = null;
        verifying = false;
        verifyingPlayer = null;
        verifyStartMillis = 0L;
        verifyEpoch++;
        synchronized (recentChatMessages) {
            recentChatMessages.clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Question snapshot = currentQuestion;
        if (snapshot == null || verifying) return;

        String msg = event.getMessage().trim();
        Player player = event.getPlayer();
        long messageTime = System.currentTimeMillis();

        // 超长刷屏消息直接拒绝：仍计入聊天频率统计，但跳过归一化与模糊匹配
        if (msg.length() > 100) {
            recordChatMessage(player.getUniqueId(), snapshot.id, messageTime, false);
            return;
        }

        String normalizedMsg = normalize(msg);
        boolean correctAnswer = matchesAny(normalizedMsg, snapshot.normalizedAnswers);
        boolean chatTooFast = recordChatMessage(player.getUniqueId(), snapshot.id, messageTime, correctAnswer);

        if (correctAnswer) {
            // 切主线程发奖，携带题目 id：若题目已轮换/作废则拒绝，防止旧题答案领走新题奖励
            Bukkit.getScheduler().runTask(this, () -> handleCorrectAnswer(player, snapshot.id, chatTooFast));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 玩家离线即清理连击记录，防止长期在线服 Map 无限增长
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        resetStreak(uuid);
        // 被验证人退出：直接作废本轮（epoch 自增使旧回调失效），否则题目锁到验证超时
        if (verifying && uuid.equals(verifyingPlayer)) {
            clearQuestionState();
        }
    }

    private boolean recordChatMessage(java.util.UUID playerId, java.util.UUID questionId, long messageTime,
                                      boolean correctAnswer) {
        synchronized (recentChatMessages) {
            ChatHistory history = recentChatMessages.get(playerId);
            if (history == null || !Objects.equals(history.questionId, questionId)) {
                history = new ChatHistory(questionId);
                recentChatMessages.put(playerId, history);
            }
            history.timestamps.addLast(messageTime);
            while (history.timestamps.size() > antiBotChatHistoryCount) {
                history.timestamps.removeFirst();
            }
            if (!correctAnswer || antiBotChatMinIntervalSeconds <= 0.0 || history.timestamps.size() < 2) {
                return false;
            }
            long minimumIntervalMillis = (long) (antiBotChatMinIntervalSeconds * 1000.0);
            Long previous = null;
            for (Long timestamp : history.timestamps) {
                if (previous != null && timestamp - previous < minimumIntervalMillis) return true;
                previous = timestamp;
            }
            return false;
        }
    }

    // 答题判定用专用 answerLock，不占用插件 this 监视器（之前 synchronized 方法会阻塞其他同步块）。
    // 注意：判定→消耗必须在同一锁内原子完成，不可分段，否则两名玩家可同时通过判定导致重复发奖。
    private void handleCorrectAnswer(Player player, java.util.UUID questionId, boolean chatTooFast) {
        synchronized (answerLock) {
            handleCorrectAnswerLocked(player, questionId, chatTooFast);
        }
    }

    private void handleCorrectAnswerLocked(Player player, java.util.UUID questionId, boolean chatTooFast) {
        Question snapshot = currentQuestion;
        if (snapshot == null || verifying) return; // double-check
        if (snapshot.id == null || !snapshot.id.equals(questionId)) return; // 题目已轮换或作废

        if (chatTooFast) {
            resetStreak(player.getUniqueId());
            clearQuestionState();
            broadcastLegacy(messagePrefix() + msg("announce-chat-kick", " §c玩家 §f{arg} §c因聊天消息间隔过短被踢出服务器。", player.getName()));
            kickLegacy(player, msg("kick-chat-too-fast", "聊天消息间隔过短", null));
            return;
        }

        long now = System.currentTimeMillis();
        double deltaSecs = (now - snapshot.postTime) / 1000.0; // 浮点精度，避免 1.9s 被截断成 1s

        // 连击计数：超出时间窗口自动清零，避免一次刷满后永久触发验证
        java.util.UUID uuid = player.getUniqueId();
        if (antiBotStreakWindowSeconds > 0) {
            Long last = lastCorrectTimes.get(uuid);
            if (last != null && now - last > antiBotStreakWindowSeconds * 1000L) {
                correctAnswerCounts.remove(uuid);
            }
        }
        lastCorrectTimes.put(uuid, now);
        int correctAnswerCount = correctAnswerCounts.merge(uuid, 1, Integer::sum);
        boolean answeredTooFast = deltaSecs <= antiBotThresholdSeconds;
        boolean answeredTooOften = antiBotCorrectAnswerThreshold > 0
                && correctAnswerCount >= antiBotCorrectAnswerThreshold;

        // 答题过快或连续答对过多时触发人机验证
        if (answeredTooFast || answeredTooOften) {
            Player p = player;
            String reason = answeredTooFast ? "答题速度过快" : "连续答对次数过多";

            // 按对接示例调用 HumanVerifyApi，要求该 API 在编译/运行时可用；
            // 若对方包名不同，需自行调整。
            // 注意：verifying 锁与广播放在确认 future 有效之后；验证服务缺失时直接发奖，不打扰玩家。
            Object future = null;
            try {
                // 使用反射调用 HumanVerifyApi，避免将第三方实现打进本插件。
                Class<?> apiClass = Class.forName("org.cubexmc.humanverify.api.HumanVerifyApi");
                Object api = Bukkit.getServicesManager().load(apiClass);
                if (api != null) {
                    try {
                        future = apiClass.getMethod("requestVerification", org.bukkit.entity.Player.class, boolean.class)
                                .invoke(api, p, true);
                    } catch (NoSuchMethodException nsme) {
                        getLogger().warning("HumanVerifyApi 没有 requestVerification(Player, boolean) 方法，跳过验证直接发奖。");
                    }
                    if (future != null && !(future instanceof java.util.concurrent.CompletableFuture)) {
                        getLogger().warning("HumanVerifyApi.requestVerification 未返回 CompletableFuture，跳过验证直接发奖");
                        future = null;
                    }
                } else {
                    getLogger().warning("未能通过 ServicesManager 加载 HumanVerifyApi，跳过验证直接发奖。");
                }
            } catch (ClassNotFoundException cnf) {
                getLogger().warning("HumanVerifyApi 类未找到，跳过验证直接发奖。请确认 HumanVerify 已安装并先于本插件加载。");
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "调用人机验证 API 时出错，跳过验证直接发奖", t);
            }

            if (future == null) {
                // 验证服务调不起来：按 README 承诺降级为直接发奖，不作废玩家答案
                // 注意：必须先消耗本题，否则题目残留可被无限次答对领奖
                clearQuestionState();
                awardWinner(p);
            } else {
                verifying = true;
                verifyingPlayer = player.getUniqueId();
                verifyStartMillis = System.currentTimeMillis();
                broadcastLegacy(messagePrefix() + msg3("announce-verify-start", " §c玩家 §f{arg} §c{arg2}，需要进行人机验证...", null, p.getName(), reason));
                java.util.UUID targetPlayer = p.getUniqueId();
                java.util.UUID targetQuestion = snapshot.id;
                long targetEpoch = verifyEpoch;
                // whenComplete 而非 thenAccept：future 异常完成时 thenAccept 永不触发，
                // verifying 会锁死到超时兜底；异常视为验证服务故障，降级直接发奖
                ((java.util.concurrent.CompletableFuture<?>) future).whenComplete((result, ex) -> {
                    if (ex != null) {
                        getLogger().log(Level.WARNING, "人机验证 future 异常完成，跳过验证直接发奖", ex);
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (targetEpoch != verifyEpoch) return;
                            if (!verifying || !targetPlayer.equals(verifyingPlayer)) return;
                            Player live = Bukkit.getPlayer(targetPlayer);
                            resetStreak(targetPlayer);
                            clearQuestionState();
                            if (live == null) return; // 验证期间已退服：只清状态，不发奖
                            awardWinner(live);
                        });
                        return;
                    }
                    try {
                        // 通过比较枚举名称判断是否为 SUCCESS
                        boolean ok = false;
                        try {
                            java.lang.reflect.Method nameM = result.getClass().getMethod("name");
                            String nm = (String) nameM.invoke(result);
                            ok = "SUCCESS".equals(nm);
                        } catch (Exception e) {
                            // fallback to toString
                            ok = "SUCCESS".equals(result.toString());
                        }
                        final boolean passed = ok;

                        Bukkit.getScheduler().runTask(this, () -> {
                            // 若已超时兜底/题目轮换/reload/force，直接丢弃过期回调
                            if (targetEpoch != verifyEpoch) return;
                            if (!verifying || !targetPlayer.equals(verifyingPlayer)) return;
                            Question cur = currentQuestion;
                            if (cur == null || cur.id == null || !cur.id.equals(targetQuestion)) return;

                            if (passed) {
                                Player live = Bukkit.getPlayer(targetPlayer);
                                resetStreak(targetPlayer);
                                clearQuestionState();
                                if (live == null) return; // 验证期间已退服：只清状态，不发奖
                                awardWinner(live);
                            } else {
                                // 失败分支同样重新查活：闭包 p 可能是验证期间已退服的过期引用，
                                // 对离线引用 kick 会抛异常，用查活后的 live 才安全
                                Player live = Bukkit.getPlayer(targetPlayer);
                                resetStreak(targetPlayer);
                                clearQuestionState();
                                if (live != null) {
                                    broadcastLegacy(messagePrefix() + msg("announce-verify-failed", " §c玩家 §f{arg} §c未通过人机验证，已被踢出服务器。", live.getName()));
                                    kickLegacy(live, msg("kick-verify-failed", "未通过人机验证", null));
                                }
                            }
                        });
                    } catch (Throwable t) {
                        getLogger().log(Level.SEVERE, "处理人机验证结果时出错", t);
                        Bukkit.getScheduler().runTask(this, () -> {
                            resetStreak(p.getUniqueId());
                            clearQuestionState();
                        });
                    }
                });
                // else 分支结束：验证回调已挂接，后续由回调或超时兜底解锁
            }

            return;
        }

        // Normal awarding — 注意：这里不能 resetStreak，否则连击永远累积不到阈值，
        // anti-bot-correct-answer-threshold 将形同虚设。连击只靠时间窗口衰减/超时/验证/退出清理。
        clearQuestionState();
        awardWinner(player);
    }

    /** 重置某玩家的连击计数。 */
    private void resetStreak(java.util.UUID uuid) {
        correctAnswerCounts.remove(uuid);
        lastCorrectTimes.remove(uuid);
        synchronized (recentChatMessages) {
            recentChatMessages.remove(uuid);
        }
    }

    /**
     * 答对庆祝：只给答对者发 Title（全服广播太扰民），音效全服可听。
     * earned <= 0 时副标题不显示金额。
     */
    private void celebrate(Player winner, double earned) {
        if (!celebrateEnabled) return;
        try {
            String sub = celebrateSubtitle.replace("{reward}", String.format(Locale.ROOT, "%,.2f", earned));
            if (earned <= 0.0) sub = "";
            showLegacyTitle(winner, celebrateTitle.replace('&', '§'), sub.replace('&', '§'));
        } catch (Throwable t) {
            getLogger().fine("发送 Title 失败: " + t.getMessage());
        }
        if (celebrateSound == null || celebrateSound.isEmpty() || "none".equalsIgnoreCase(celebrateSound)) return;
        try {
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(celebrateSound.toUpperCase(Locale.ROOT));
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    // 走 MASTER 通道：不受玩家环境音量设置影响，保证庆祝音效可听
                    p.playSound(p.getLocation(), sound, org.bukkit.SoundCategory.MASTER, celebrateVolume, celebratePitch);
                } catch (Throwable ignored) {}
            }
        } catch (IllegalArgumentException e) {
            getLogger().warning("celebrate.sound 配置无效: " + celebrateSound + "，已跳过音效");
            celebrateSound = "none"; // 避免每题刷一次告警
        }
    }

    private void awardWinner(Player winner) {
        // 无 Vault 时降级为纯公告模式，不暂停出题
        if (!economyAvailable) {
            recordCorrect(winner, 0.0);
            broadcastLegacy(messagePrefix() + msg("announce-win-no-vault", " §a玩家 §f{arg} §a答对了问题！§7（未安装 Vault，本轮无货币奖励）", winner.getName()));
            celebrate(winner, 0.0);
            return;
        }
        // 奖励为 0：跳过全部转账调用，直接公告
        if (rewardAmount <= 0.0) {
            recordCorrect(winner, 0.0);
            broadcastLegacy(messagePrefix() + msg("announce-win", " §a玩家 §f{arg} §a答对了问题！", winner.getName()));
            celebrate(winner, 0.0);
            return;
        }
        // 答对者就是出资人：左手倒右手，跳过转账
        if (isPayer(winner)) {
            recordCorrect(winner, 0.0);
            broadcastLegacy(messagePrefix() + msg("announce-win-self", " §a玩家 §f{arg} §a答对了问题！§7（出资人自答，无需转账）", winner.getName()));
            celebrate(winner, 0.0);
            return;
        }
        // 检查出资人余额
        double payerBal = getBalanceOf(payerDisplay());
        if (payerBal < rewardAmount) {
            paused = true;
            // 进入暂停即定好下次复查时间，避免 tick 第一秒就重复查询
            nextBalanceCheckMillis = System.currentTimeMillis() + balanceRetrySeconds * 1000L;
            broadcastLegacy(messagePrefix() + msg3("announce-paused-funds",
                    " §c出题已暂停：资金不足（需要 {arg}，当前 {arg2}）。", null,
                    String.format(Locale.ROOT, "%,.2f", rewardAmount),
                    String.format(Locale.ROOT, "%,.2f", payerBal)));
            return;
        }

        Object w = withdrawFrom(payerDisplay(), rewardAmount);
        if (!isEconomyResponseSuccess(w)) {
            paused = true;
            nextBalanceCheckMillis = System.currentTimeMillis() + balanceRetrySeconds * 1000L;
            String err = getEconomyResponseError(w);
            broadcastLegacy(messagePrefix() + msg("announce-transfer-failed", " §c转账失败（错误: {arg}），出题已暂停。请检查服务器日志。", err));
            getLogger().warning("扣款失败: " + err);
            return;
        }

        Object d = depositTo(winner, rewardAmount);
        if (!isEconomyResponseSuccess(d)) {
            // refund payer if possible（必须走对称通道，否则 Server/UUID 出资时钱退错地方）
            String err = getEconomyResponseError(d);
            getLogger().warning("发放给胜利玩家失败: " + err + "。尝试退款。");
            Object refund = refundToPayer(rewardAmount);
            if (isEconomyResponseSuccess(refund)) {
                broadcastLegacy(messagePrefix() + msg("announce-refunded", " §c发放奖励失败，已退款，请联系管理员。错误: {arg}", err));
            } else {
                // 退款也失败：出资人已被扣款，玩家未到账，必须人工介入，不能谎称已退款
                getLogger().severe("退款失败！出资人 " + payerDisplay() + " 已被扣 " + rewardAmount
                        + "，玩家 " + winner.getName() + " 未到账。请手动补账。发放错误: " + err
                        + "，退款错误: " + getEconomyResponseError(refund));
                broadcastLegacy(messagePrefix() + msg("announce-refund-failed", " §c发放奖励失败，且自动退款失败！请联系管理员手动补账。错误: {arg}", err));
            }
            return;
        }

        recordCorrect(winner, rewardAmount);
        broadcastLegacy(messagePrefix() + msg3("announce-win-reward", " §a玩家 §f{arg} §a答对了问题，获得 §e{arg2} §a货币！",
                null, winner.getName(), String.format(Locale.ROOT, "%,.2f", rewardAmount)));
        celebrate(winner, rewardAmount);
    }

    // 经济初始化转发到 EconomyBridge（渐进迁移：调用方不动，行为零变化）
    private boolean setupEconomy() {
        return economy.setup();
    }

    private String economyProviderName() {
        return economy.getProviderName();
    }

    private boolean economyBankSupport() {
        return economy.hasBankSupport();
    }

    // 经济调用转发到 EconomyBridge（渐进迁移：调用方不动，行为零变化）
    private double getBalanceOf(String who) {
        return economy.getBalanceOf(who);
    }

    private Object withdrawFrom(String who, double amount) {
        return economy.withdrawFrom(who, amount);
    }

    private Object depositTo(Player winner, double amount) {
        return economy.depositTo(winner, amount);
    }

    private Object refundToPayer(double amount) {
        return economy.refundToPayer(amount);
    }

    private Object depositTo(String who, double amount) {
        return economy.depositTo(who, amount);
    }

    private boolean isEconomyResponseSuccess(Object resp) {
        return economy.isSuccess(resp);
    }

    private String getEconomyResponseError(Object resp) {
        return economy.errorOf(resp);
    }

    // 命令处理器：子命令注册表驱动——别名、权限、处理器、补全全部单源定义，
    // 新增子命令只需在 COMMANDS 加一行 + sendHelp 补一行 + plugin.yml/README 同步
    private interface SubHandler {
        boolean handle(CommandSender sender, String label, String[] args);
    }

    private static class SubCommand {
        final String name;
        final boolean admin;
        final List<String> aliases;
        final SubHandler handler;
        // 帮助行：msg 键与默认值（help 菜单循环生成，新增子命令不再漏行）
        final String helpKey;
        final String helpDef;

        SubCommand(String name, boolean admin, SubHandler handler, String helpKey, String helpDef, String... aliases) {
            this.name = name;
            this.admin = admin;
            this.handler = handler;
            this.helpKey = helpKey;
            this.helpDef = helpDef;
            this.aliases = Arrays.asList(aliases);
        }

        boolean matches(String sub) {
            if (name.equals(sub)) return true;
            for (String a : aliases) {
                if (a.equals(sub)) return true;
            }
            return false;
        }
    }

    private class QuizCommand implements CommandExecutor, TabCompleter {
        private final List<SubCommand> commands = Arrays.asList(
                new SubCommand("help", false, (s, l, a) -> { sendHelp(s, l); return true; },
                        "help-help", "&e/{cmd} help &7- 显示此帮助", "?"),
                new SubCommand("top", false, (s, l, a) -> { sendTop(s, a.length > 1 ? a[1] : null); return true; },
                        "help-top", "&e/{cmd} top [数量] &7- 答题排行榜（默认 10，最多 20）"),
                new SubCommand("stats", false, (s, l, a) -> { sendStats(s, a.length > 1 ? a[1] : null); return true; },
                        "help-stats", "&e/{cmd} stats [玩家] &7- 查看答题统计（默认自己）"),
                new SubCommand("status", false, (s, l, a) -> { sendStatus(s); return true; },
                        "help-status", "&e/{cmd} status &7- 查看插件状态"),
                new SubCommand("start", true, (s, l, a) -> handleStart(s),
                        "help-start", "&e/{cmd} start &7- 启动定时出题"),
                new SubCommand("stop", true, (s, l, a) -> handleStop(s),
                        "help-stop", "&e/{cmd} stop &7- 停止定时出题"),
                new SubCommand("question", true, (s, l, a) -> handleQuestion(s, a),
                        "help-question", "&e/{cmd} question [force] &7- 发布新题目（force 强制）", "q"),
                new SubCommand("reload", true, (s, l, a) -> handleReload(s),
                        "help-reload", "&e/{cmd} reload &7- 重载配置")
        );

        private SubCommand find(String sub) {
            for (SubCommand c : commands) {
                if (c.matches(sub)) return c;
            }
            return null;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                sendHelp(sender, label);
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            SubCommand cmd = find(sub);
            if (cmd == null) {
                sender.sendMessage(msg("unknown-command", "&c未知子命令: {arg}", sub));
                sendHelp(sender, label);
                return true;
            }
            if (cmd.admin && !sender.hasPermission("letmeask.admin")) {
                sender.sendMessage(msg("no-permission", "&c你没有权限执行此命令 (letmeask.admin)", null));
                return true;
            }
            return cmd.handler.handle(sender, label, args);
        }

        private boolean handleStart(CommandSender sender) {
            startTask();
            if (currentQuestion == null && !verifying && !paused) {
                publishQuestion();
                nextPostAtMillis = System.currentTimeMillis() + questionIntervalSeconds * 1000L;
            }
            sender.sendMessage(msg("started", "&a已启动定时出题", null));
            return true;
        }

        private boolean handleStop(CommandSender sender) {
            stopTask();
            // 停止同时作废当前题并解锁验证：否则题目残留仍可作答，与“已停止”语义矛盾
            if (currentQuestion != null || verifying) {
                if (verifyingPlayer != null) resetStreak(verifyingPlayer);
                clearQuestionState();
                sender.sendMessage(msg("stopped-with-question", "&c已停止定时出题（当前题目已作废）", null));
            } else {
                sender.sendMessage(msg("stopped", "&c已停止定时出题", null));
            }
            return true;
        }

        private boolean handleQuestion(CommandSender sender, String[] args) {
            boolean force = args.length > 1 && args[1].equalsIgnoreCase("force");
            boolean ok = postNewQuestion(force);
            if (ok) sender.sendMessage(msg("question-posted", "&a已发布新题目", null));
            else sender.sendMessage(msg("question-blocked", "&c无法发布新题目（已有题目/正在验证/已暂停）。使用 /letmeask question force 可强制发布", null));
            return true;
        }

        private boolean handleReload(CommandSender sender) {
            boolean ok = loadConfigValues();
            // 重载时若正在验证：解锁并作废本轮，否则锁会一直占到验证超时
            if (verifying) {
                if (verifyingPlayer != null) resetStreak(verifyingPlayer);
                clearQuestionState();
                sender.sendMessage(msg("reload-dropped-verify", "&e重载时存在未完成的验证，已作废本轮题目", null));
            }
            // restart scheduler to pick up interval changes
            startTask();
            startLeaderboardTask(); // 排行榜广播配置也可能变了，一并重启
            if (ok) sender.sendMessage(msg("reloaded", "&a已重载配置(base.yml 与 questions.yml)", null));
                    else sender.sendMessage(msg("reloaded-empty", "&e配置已重载，但新题库为空，已保留旧题库与旧配置继续运行", null));
            return true;
        }

        private void sendHelp(CommandSender sender, String label) {
            // 用玩家实际输入的别名展示（如 /lma 进来就显示 /lma），复制即用
            String cmd = (label == null || label.isEmpty()) ? "letmeask" : label;
            sender.sendMessage(msg("help-header", "&6&m----------&r &6LetMeAsk 帮助 &6&m----------", null));
            // 帮助行从注册表循环生成：新增子命令只需在 commands 加一行，不会再漏
            boolean adminSection = false;
            for (SubCommand c : commands) {
                if (c.admin && !sender.hasPermission("letmeask.admin")) continue;
                if (c.admin && !adminSection) {
                    sender.sendMessage(msg("help-admin-header", "&6管理命令:", null));
                    adminSection = true;
                }
                sender.sendMessage(msg2(c.helpKey, c.helpDef, cmd, null));
            }
            sender.sendMessage(msg("help-footer", "&6&m--------------------------------", null));
        }

        private void sendStatus(CommandSender sender) {
            sender.sendMessage(msg("status-header", "&6LetMeAsk 状态:", null));
            sender.sendMessage(msg("status-task", " 自动出题: {arg}",
                    tickerTask != null ? "§a运行中" : "§c已停止"));
            sender.sendMessage(msg("status-questions", " 题库数量: §f{arg}", String.valueOf(questions.size())));
            sender.sendMessage(msg("status-current", " 当前题目: {arg}",
                    currentQuestion != null ? currentQuestion.question : "无"));
            sender.sendMessage(msg("status-paused", " 暂停(余额不足): {arg}", paused ? "§c是" : "§a否"));
            sender.sendMessage(msg("status-verifying", " 人机验证锁定: {arg}", verifying ? "§c是" : "§a否"));
            sender.sendMessage(msg3("status-total", " 累计出题: §f{arg} §7已答对: §f{arg2}",
                    null, String.valueOf(totalAsked), String.valueOf(totalAnswered)));
            if (economyAvailable) {
                // 余额查询可能打 Vault 后端 IO，异步查完再回主线程输出，避免卡主线程
                final String payerNameSnap = payerDisplay();
                final String providerSnap = economyProviderName();
                final boolean bankWarn = payerIsServer() && !economyBankSupport();
                Bukkit.getScheduler().runTaskAsynchronously(QuizPlugin.this, () -> {
                    double bal;
                    try {
                        bal = getBalanceOf(payerNameSnap);
                    } catch (Throwable t) {
                        bal = Double.NaN;
                    }
                    final double balance = bal;
                    Bukkit.getScheduler().runTask(QuizPlugin.this, () -> {
                        if (sender instanceof Player && !((Player) sender).isOnline()) return;
                        String balText = Double.isNaN(balance)
                                ? "§c查询失败"
                                : String.format(Locale.ROOT, "%,.2f", balance);
                        sender.sendMessage(" 支付玩家: §f" + payerNameSnap + " §7(余额: " + balText + ")");
                        sender.sendMessage(" 经济后端: §f" + (providerSnap != null ? providerSnap : "未知")
                                + (bankWarn ? " §7(无银行账户，按账户名扣款)" : ""));
                    });
                });
            } else {
                sender.sendMessage(" 经济系统: §e未检测到 Vault（纯公告模式，无货币奖励）");
            }
        }

        /** 解析 stats/top 的目标玩家：无参数查自己（需为玩家），有参数按名查找（离线也可）。 */
        private OfflinePlayer resolveStatsTarget(CommandSender sender, String name) {
            if (name == null || name.isEmpty()) {
                return (sender instanceof Player) ? (Player) sender : null;
            }
            // 先走缓存（在线/近期离线玩家命中，不碰磁盘）；未命中再走 getOfflinePlayer
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
            if (off == null) off = Bukkit.getOfflinePlayer(name);
            // getOfflinePlayer(name) 对从未进服的名字也会返回占位对象，用是否玩过来过滤
            if (!off.hasPlayedBefore() && !off.isOnline()) return null;
            return off;
        }

        private void sendStats(CommandSender sender, String nameArg) {
            OfflinePlayer target = resolveStatsTarget(sender, nameArg);
            if (target == null) {
                if (nameArg == null) sender.sendMessage(msg("console-need-name", "&c控制台请指定玩家名：/letmeask stats <玩家名>", null));
                else sender.sendMessage(msg("player-not-found", "&c找不到玩家: {arg}", nameArg));
                return;
            }
            String key = target.getUniqueId().toString();
            cacheName(key, target.getName()); // 顺手更新缓存（加锁，与异步保存互斥）
            String display = displayNameOf(key);
            int correct = totalCorrect.getOrDefault(key, 0);
            double earned = totalEarned.getOrDefault(key, 0.0);
            sender.sendMessage("§6玩家 §f" + display + " §6的答题统计:");
            sender.sendMessage(" 答对: §f" + correct + " §7累计奖金: §e" + String.format(Locale.ROOT, "%,.2f", earned));
        }

        private void sendTop(CommandSender sender, String countArg) {
            int count = 10;
            if (countArg != null && !countArg.isEmpty()) {
                try {
                    count = Math.min(20, Math.max(1, Integer.parseInt(countArg)));
                } catch (NumberFormatException ignored) {
                    sender.sendMessage(msg("invalid-count", "&c数量参数无效，使用默认值 10", null));
                }
            }
            topMessages(count).forEach(sender::sendMessage);
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                // 补全与分发同源：注册表即唯一来源，新增子命令自动出现在补全里
                String prefix = args[0].toLowerCase(Locale.ROOT);
                boolean admin = sender.hasPermission("letmeask.admin");
                List<String> subs = new ArrayList<>();
                for (SubCommand c : commands) {
                    if (!c.admin || admin) {
                        subs.add(c.name);
                        subs.addAll(c.aliases);
                    }
                }
                return subs.stream()
                        .filter(subcommand -> subcommand.startsWith(prefix))
                        .collect(java.util.stream.Collectors.toList());
            }

            if (args.length == 2 && (args[0].equalsIgnoreCase("question") || args[0].equalsIgnoreCase("q"))) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return "force".startsWith(prefix) ? Collections.singletonList("force") : Collections.emptyList();
            }

            if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName() != null && p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        names.add(p.getName());
                    }
                }
                return names;
            }

            return Collections.emptyList();
        }
    }

    // 题目数据（支持多答案与权重）
    private static class Question {
        final String question;
        final List<String> answers; // 任一匹配即算答对（原始文本，用于公布答案）
        final List<String> normalizedAnswers; // 预归一化答案，判定用，避免每条聊天重复归一化
        final int weight; // 出题权重（>=1），越大越容易被抽中
        volatile long postTime;
        volatile java.util.UUID id;

        Question(String q, List<String> as) {
            this(q, as, 1);
        }

        Question(String q, List<String> as, int w) {
            this.question = q;
            this.answers = Collections.unmodifiableList(new ArrayList<>(as));
            List<String> norm = new ArrayList<>(as.size());
            for (String a : as) norm.add(normalize(a));
            this.normalizedAnswers = Collections.unmodifiableList(norm);
            this.weight = clampWeight(w);
        }

        /** 公布答案时展示的首选答案。 */
        String displayAnswer() {
            return answers.isEmpty() ? "" : answers.get(0);
        }
    }

    private static class ChatHistory {
        final java.util.UUID questionId;
        final Deque<Long> timestamps = new ArrayDeque<>();

        ChatHistory(java.util.UUID questionId) {
            this.questionId = questionId;
        }
    }
}
