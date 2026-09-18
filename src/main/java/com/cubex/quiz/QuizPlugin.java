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
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Quiz activity plugin (CubeX-compatible layout).
 * - Uses Vault for economy (recommended: CMI exposes Vault provider)
 * - Integrates with external HumanVerifyApi as in your example. If you have a different package
 *   for HumanVerifyApi, add the API dependency when compiling. If compilation fails, adjust imports
 *   to the correct package for your HumanVerify plugin.
 */
public class QuizPlugin extends JavaPlugin implements Listener {
    // Vault economy provider (kept as Object to avoid compile-time dependency on Vault API)
    private Object econ; // provider instance

    private final Random random = new Random();

    // runtime state
    private volatile Question currentQuestion = null;
    private volatile boolean paused = false; // paused due to payer insufficient funds
    private volatile boolean verifying = false; // question locked while human verification pending
    private volatile java.util.UUID verifyingPlayer = null;
    private volatile long verifyStartMillis = 0L;
    private volatile long nextPostAtMillis = 0L; // when the next question may be posted
    private volatile boolean economyAvailable = false;

    // config values
    private String payerName;
    private double rewardAmount;
    private long questionIntervalSeconds;
    private long questionTimeoutSeconds;
    private double antiBotThresholdSeconds;
    private int antiBotCorrectAnswerThreshold;
    private long antiBotStreakWindowSeconds;
    private long verifyTimeoutSeconds;
    private double fuzzySimilarityThreshold = 0.75; // default similarity threshold (0-1)

    // resolved payer information (support UUID / OfflinePlayer / Server / LittleSkin via prefix)
    private org.bukkit.OfflinePlayer payerOffline = null;
    private boolean payerIsServer = false;
    private boolean payerUsesUuid = false;
    private String payerDisplay = null; // human readable identifier

    // config files
    private File baseFile;
    private File questionsFile;
    private FileConfiguration baseCfg;
    private FileConfiguration questionsCfg;

    // scheduler handle
    private BukkitTask tickerTask;
    private BukkitTask statsSaveTask;
    private final Map<java.util.UUID, Integer> correctAnswerCounts = new HashMap<>();
    private final Map<java.util.UUID, Long> lastCorrectTimes = new HashMap<>();

    // 答题统计（持久化到 stats.yml，key 为玩家 UUID 字符串）
    private File statsFile;
    private FileConfiguration statsCfg;
    private final Map<String, Integer> totalCorrect = new HashMap<>();
    private final Map<String, Double> totalEarned = new HashMap<>();
    private volatile long totalAsked = 0L;
    private volatile long totalAnswered = 0L;

    @Override
    public void onEnable() {
        // Ensure default resource files exist
        saveResource("base.yml", false);
        saveResource("questions.yml", false);

        // load configuration files
        if (!loadConfigValues()) {
            getLogger().severe("启动时题库为空，禁用插件。请在 questions.yml 中添加题目后重启。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economyAvailable = setupEconomy();
        if (!economyAvailable) {
            getLogger().warning("未找到 Vault 经济插件：以“仅公告、无奖励”模式运行，安装 Vault 后请重启或重载插件");
        }

        getServer().getPluginManager().registerEvents(this, this);

        // register command
        if (getCommand("letmeask") != null) {
            QuizCommand quizCommand = new QuizCommand();
            getCommand("letmeask").setExecutor(quizCommand);
            getCommand("letmeask").setTabCompleter(quizCommand);
        }

        // Start scheduler to post questions periodically (also checks paused state)
        startTask();

        loadStats();
        // 统计每 5 分钟落盘一次，避免服崩丢失
        statsSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveStats();
            }
        }.runTaskTimerAsynchronously(this, 6000L, 6000L);

        getLogger().info("QuizPlugin enabled");
    }

    @Override
    public void onDisable() {
        stopTask();
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
        org.bukkit.configuration.ConfigurationSection players = statsCfg.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                totalCorrect.put(key, players.getInt(key + ".correct", 0));
                totalEarned.put(key, players.getDouble(key + ".earned", 0.0));
            }
        }
        totalAsked = statsCfg.getLong("total-asked", 0L);
        totalAnswered = statsCfg.getLong("total-answered", 0L);
    }

    /** 同步写回 stats.yml（调用方注意线程：定时任务走异步，onDisable 走主线程）。 */
    private synchronized void saveStats() {
        if (statsCfg == null || statsFile == null) return;
        try {
            statsCfg.set("total-asked", totalAsked);
            statsCfg.set("total-answered", totalAnswered);
            for (Map.Entry<String, Integer> e : totalCorrect.entrySet()) {
                String key = "players." + e.getKey();
                statsCfg.set(key + ".correct", e.getValue());
                statsCfg.set(key + ".earned", totalEarned.getOrDefault(e.getKey(), 0.0));
            }
            statsCfg.save(statsFile);
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "保存 stats.yml 失败", ex);
        }
    }

    /** 记录一次答对：累计次数与实发金额（0 奖励/自答/无 Vault 时金额为 0 也计数）。 */
    private void recordCorrect(Player player, double earned) {
        String key = player.getUniqueId().toString();
        totalCorrect.merge(key, 1, Integer::sum);
        if (earned > 0.0) totalEarned.merge(key, earned, Double::sum);
        else totalEarned.putIfAbsent(key, 0.0);
        totalAnswered++;
    }



    /**
     * 加载配置。题库解析到临时列表：新题库为空则保留旧题库并返回 false，
     * 调用方据此决定是禁用插件（启动时）还是报错但继续运行（reload 时）。
     */
    private boolean loadConfigValues() {
        // load base and questions from their own files
        baseFile = new File(getDataFolder(), "base.yml");
        questionsFile = new File(getDataFolder(), "questions.yml");
        try {
            if (!baseFile.exists()) saveResource("base.yml", false);
            if (!questionsFile.exists()) saveResource("questions.yml", false);
        } catch (Exception ignored) {}

        baseCfg = YamlConfiguration.loadConfiguration(baseFile);
        questionsCfg = YamlConfiguration.loadConfiguration(questionsFile);

        payerName = baseCfg.getString("payer", "Server");
        rewardAmount = Math.max(0.0, baseCfg.getDouble("reward", 50.0));
        questionIntervalSeconds = Math.max(5L, baseCfg.getLong("question-interval-seconds", 60L));
        questionTimeoutSeconds = Math.max(0L, baseCfg.getLong("question-timeout-seconds", 30L));
        antiBotThresholdSeconds = Math.max(0.0, baseCfg.getDouble("anti-bot-threshold-seconds", 1.0));
        antiBotCorrectAnswerThreshold = Math.max(0, baseCfg.getInt("anti-bot-correct-answer-threshold", 3));
        antiBotStreakWindowSeconds = Math.max(0L, baseCfg.getLong("anti-bot-streak-window-seconds", 300L));
        verifyTimeoutSeconds = Math.max(10L, baseCfg.getLong("verify-timeout-seconds", 120L));
        fuzzySimilarityThreshold = Math.min(1.0, Math.max(0.0,
                baseCfg.getDouble("fuzzy-similarity-threshold", fuzzySimilarityThreshold)));

        // resolve payer to a stable identifier (UUID/name/Server/LittleSkin)
        resolvePayer(payerName);

        // 条目可以是纯字符串（"题目=答案"，权重 1），也可以是 map（{q, a, weight}）
        List<?> raw = questionsCfg.getList("questions");
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
                // reload 时新题库为空：保留旧题库，不中断运行
                getLogger().warning("questions.yml 中没有可用题目，已保留旧题库（" + questions.size() + " 题）。请检查配置后重新 reload。");
                return false;
            }
        }

        List<Question> parsed = parseQuestions(raw);
        if (parsed.isEmpty()) {
            if (questions.isEmpty()) {
                getLogger().severe("没有可用题目，插件无法正常出题。请在 questions.yml 中添加题目。");
                return false;
            }
            getLogger().warning("新题库解析后为空，已保留旧题库（" + questions.size() + " 题）。");
            return false;
        }

        questions.clear();
        questions.addAll(parsed);
        return !usingFallback || !questions.isEmpty();
    }

    /**
     * 解析题库条目。支持两种格式（可混用）：
     * <ul>
     *   <li>纯字符串: "题目=答案1|答案2"，权重默认为 1</li>
     *   <li>map: {q: "题目", a: "答案1|答案2", weight: 3}，weight 越大越容易被抽中（最小 1）</li>
     * </ul>
     * 非法行与重复题目跳过。
     */
    private List<Question> parseQuestions(List<?> raw) {
        List<Question> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object entry : raw) {
            String line;
            int weight = 1;
            if (entry instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) entry;
                Object qObj = map.get("q");
                Object aObj = map.get("a");
                if (qObj == null || aObj == null) continue;
                line = qObj + "=" + aObj;
                Object wObj = map.get("weight");
                if (wObj instanceof Number) weight = Math.max(1, ((Number) wObj).intValue());
            } else if (entry instanceof String) {
                line = (String) entry;
            } else {
                continue;
            }
            if (line == null) continue;
            String[] parts = line.split("=", 2);
            if (parts.length < 2) parts = line.split(":", 2);
            if (parts.length < 2) continue;
            String q = parts[0].trim();
            String a = parts[1].trim();
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

    private String messagePrefix() {
        String prefix = baseCfg == null ? "&6[教育部]" : baseCfg.getString("messages.prefix", "&6[教育部]");
        return prefix.replace('&', '§');
    }

    private void resolvePayer(String payer) {
        payerOffline = null;
        payerIsServer = false;
        payerUsesUuid = false;
        payerDisplay = payer;
        if (payer == null) return;
        if (payer.equalsIgnoreCase("server") || payer.equalsIgnoreCase("console")) {
            payerIsServer = true;
            payerDisplay = payer;
            return;
        }
        // support littleskin:uuid or littleskin:name
        if (payer.toLowerCase().startsWith("littleskin:")) {
            String v = payer.substring(payer.indexOf(":") + 1);
            try {
                java.util.UUID uuid = java.util.UUID.fromString(v);
                payerOffline = Bukkit.getOfflinePlayer(uuid);
                payerDisplay = uuid.toString();
                return;
            } catch (IllegalArgumentException ignored) {
                // fall through to name
            }
            payerOffline = Bukkit.getOfflinePlayer(v);
            payerDisplay = payerOffline.getName() != null ? payerOffline.getName() : v;
            return;
        }
        // try UUID
        try {
            java.util.UUID uuid = java.util.UUID.fromString(payer);
            payerOffline = Bukkit.getOfflinePlayer(uuid);
            payerUsesUuid = true;
            payerDisplay = uuid.toString();
            return;
        } catch (IllegalArgumentException ignored) {
        }
        // fallback to name
        payerOffline = Bukkit.getOfflinePlayer(payer);
        payerDisplay = payerOffline.getName() != null ? payerOffline.getName() : payer;
    }

    /** 判断答对者是否为出资人（UUID 优先，其次名字忽略大小写比对）。 */
    private boolean isPayer(Player player) {
        if (player == null) return false;
        if (payerIsServer) return false; // Server/Console 账户不可能是真实玩家
        if (payerOffline != null && player.getUniqueId().equals(payerOffline.getUniqueId())) return true;
        String name = player.getName();
        return name != null && payerDisplay != null && name.equalsIgnoreCase(payerDisplay);
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
        totalAsked++;
        Bukkit.broadcastMessage(messagePrefix() + " §f新题目: §f" + q.question);
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

    /** 加权随机：权重越大越容易被抽中。 */
    private Question pickWeighted() {
        int total = 0;
        for (Question q : questions) total += q.weight;
        int r = random.nextInt(total);
        for (Question q : questions) {
            r -= q.weight;
            if (r < 0) return q;
        }
        return questions.get(questions.size() - 1);
    }

    /** 任一候选答案匹配即算答对。 */
    private boolean matchesAny(String provided, List<String> answers) {
        if (answers == null) return false;
        for (String answer : answers) {
            if (matches(provided, answer)) return true;
        }
        return false;
    }

    private boolean matches(String provided, String answer) {
        if (provided == null || answer == null) return false;
        String a = normalize(provided);
        String b = normalize(answer);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (fuzzySimilarityThreshold >= 1.0) return false; // 1.0 = 严格精确匹配
        int dist = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        double sim = 1.0 - (double) dist / (double) max;
        return sim >= fuzzySimilarityThreshold;
    }

    private String normalize(String s) {
        // Locale.ROOT：避免土耳其语等 locale 下 I/i 大小写转换异常
        return s == null ? "" : s.replaceAll("[^\\p{L}\\p{N}]+", "").toLowerCase(Locale.ROOT);
    }

    private int levenshtein(String s1, String s2) {
        int[] prev = new int[s2.length() + 1];
        int[] curr = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) prev[j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, prev.length);
        }
        return prev[s2.length()];
    }

    private final List<Question> questions = new ArrayList<>();

    private void tick() {
        if (!economyAvailable) {
            // 降级模式：无 Vault 时不暂停出题逻辑，但 awardWinner 只发公告
            if (paused) paused = false;
        } else if (paused) {
            // 暂停中：资金足额则恢复，否则等待
            double bal = getBalanceOf(payerDisplay);
            if (bal >= rewardAmount) {
                paused = false;
                Bukkit.broadcastMessage(messagePrefix() + " §a资金已足额，恢复出题。当前余额: " + bal);
            } else {
                return;
            }
        }

        // 验证超时兜底：回调永不返回时解锁，避免永久锁死
        if (verifying) {
            long elapsedMillis = System.currentTimeMillis() - verifyStartMillis;
            if (elapsedMillis >= verifyTimeoutSeconds * 1000L) {
                getLogger().warning("人机验证超时（" + verifyTimeoutSeconds + "s），自动解锁并作废本轮题目");
                Bukkit.broadcastMessage(messagePrefix() + " §c人机验证超时，本轮题目作废。");
                clearQuestionState();
            }
            return;
        }

        // 题目超时：公布答案、清空连击、安排下一题
        if (currentQuestion != null) {
            if (questionTimeoutSeconds > 0) {
                long elapsedMillis = System.currentTimeMillis() - currentQuestion.postTime;
                if (elapsedMillis >= questionTimeoutSeconds * 1000L) {
                    Bukkit.broadcastMessage(messagePrefix() + " §c无人答对！答案是: §f" + currentQuestion.displayAnswer());
                    correctAnswerCounts.clear();
                    lastCorrectTimes.clear();
                    currentQuestion = null;
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

    /** 清理本轮题目与验证状态（验证通过/失败/超时统一入口）。 */
    private void clearQuestionState() {
        currentQuestion = null;
        verifying = false;
        verifyingPlayer = null;
        verifyStartMillis = 0L;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Question snapshot = currentQuestion;
        if (snapshot == null || verifying) return;

        String msg = event.getMessage().trim();
        Player player = event.getPlayer();

        // 超长刷屏消息直接拒绝，避免无意义的模糊匹配计算
        if (msg.length() > 100) return;

        if (matchesAny(msg, snapshot.answers)) {
            // 切主线程发奖，携带题目 id：若题目已轮换/作废则拒绝，防止旧题答案领走新题奖励
            Bukkit.getScheduler().runTask(this, () -> handleCorrectAnswer(player, snapshot.id));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 玩家离线即清理连击记录，防止长期在线服 Map 无限增长
        resetStreak(event.getPlayer().getUniqueId());
    }

    private synchronized void handleCorrectAnswer(Player player, java.util.UUID questionId) {
        Question snapshot = currentQuestion;
        if (snapshot == null || verifying) return; // double-check
        if (snapshot.id == null || !snapshot.id.equals(questionId)) return; // 题目已轮换或作废

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

        // Invoke human verification for unusually fast or repeated correct answers.
        if (answeredTooFast || answeredTooOften) {
            verifying = true;
            verifyingPlayer = player.getUniqueId();
            verifyStartMillis = System.currentTimeMillis();
            Player p = player;
            String reason = answeredTooFast ? "答题速度过快" : "连续答对次数过多";
            Bukkit.broadcastMessage(messagePrefix() + " §c玩家 §f" + p.getName() + " §c"
                    + reason + "，需要进行人机验证...");

            // Try to call HumanVerifyApi as in provided snippet. This requires that the HumanVerify API
            // is available at compile/runtime. If you use a different package, add the dependency.
            try {
                // 使用反射调用 HumanVerifyApi，避免将第三方实现打进本插件。
                Class<?> apiClass = Class.forName("org.cubexmc.humanverify.api.HumanVerifyApi");
                Object api = Bukkit.getServicesManager().load((Class) apiClass);
                if (api != null) {
                    Object future = null;
                    try {
                        future = apiClass.getMethod("requestVerification", org.bukkit.entity.Player.class, boolean.class)
                                .invoke(api, p, true);
                    } catch (NoSuchMethodException nsme) {
                        getLogger().warning("HumanVerifyApi 没有 requestVerification(Player, boolean) 方法，验证失败，不发放奖励。");
                    }

                    if (future instanceof java.util.concurrent.CompletableFuture) {
                        java.util.UUID targetPlayer = p.getUniqueId();
                        java.util.UUID targetQuestion = snapshot.id;
                        ((java.util.concurrent.CompletableFuture<?>) future).thenAccept(result -> {
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
                                    // 若已超时兜底/题目轮换，直接丢弃过期回调
                                    if (!verifying || !targetPlayer.equals(verifyingPlayer)) return;
                                    Question cur = currentQuestion;
                                    if (cur == null || cur.id == null || !cur.id.equals(targetQuestion)) return;

                                    if (passed) {
                                        if (!p.isOnline()) {
                                            resetStreak(targetPlayer);
                                            clearQuestionState();
                                            return;
                                        }
                                        resetStreak(targetPlayer);
                                        clearQuestionState();
                                        awardWinner(p);
                                    } else {
                                        resetStreak(targetPlayer);
                                        clearQuestionState();
                                        Bukkit.broadcastMessage(messagePrefix() + " §c玩家 §f" + p.getName() + " §c未通过人机验证，已被踢出服务器。");
                                        p.kickPlayer("未通过人机验证");
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
                    } else {
                        getLogger().warning("HumanVerifyApi.requestVerification 未返回 CompletableFuture 或返回 null，验证失败，不发放奖励");
                        resetStreak(p.getUniqueId());
                        clearQuestionState();
                    }
                } else {
                    getLogger().warning("未能通过 ServicesManager 加载 HumanVerifyApi，验证失败，不发放奖励。");
                    resetStreak(p.getUniqueId());
                    clearQuestionState();
                }
            } catch (ClassNotFoundException cnf) {
                getLogger().warning("HumanVerifyApi 类未找到，无法执行人机验证。请确认 HumanVerify 已安装并先于本插件加载。");
                resetStreak(p.getUniqueId());
                clearQuestionState();
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "调用人机验证 API 时出错，验证失败，不发放奖励", t);
                resetStreak(p.getUniqueId());
                clearQuestionState();
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
    }

    private void awardWinner(Player winner) {
        // 无 Vault 时降级为纯公告模式，不暂停出题
        if (!economyAvailable) {
            recordCorrect(winner, 0.0);
            Bukkit.broadcastMessage(messagePrefix() + " §a玩家 §f" + winner.getName() + " §a答对了问题！§7（未安装 Vault，本轮无货币奖励）");
            return;
        }
        // 奖励为 0：跳过全部转账调用，直接公告
        if (rewardAmount <= 0.0) {
            recordCorrect(winner, 0.0);
            Bukkit.broadcastMessage(messagePrefix() + " §a玩家 §f" + winner.getName() + " §a答对了问题！");
            return;
        }
        // 答对者就是出资人：左手倒右手，跳过转账
        if (isPayer(winner)) {
            recordCorrect(winner, 0.0);
            Bukkit.broadcastMessage(messagePrefix() + " §a玩家 §f" + winner.getName() + " §a答对了问题！§7（出资人自答，无需转账）");
            return;
        }
        // Check payer balance
        double payerBal = getBalanceOf(payerDisplay);
        if (payerBal < rewardAmount) {
            paused = true;
            Bukkit.broadcastMessage(messagePrefix() + " §c出题已暂停：资金不足（需要 " + rewardAmount + "，当前 " + payerBal + "）。");
            return;
        }

        Object w = withdrawFrom(payerDisplay, rewardAmount);
        if (!isEconomyResponseSuccess(w)) {
            paused = true;
            String err = getEconomyResponseError(w);
            Bukkit.broadcastMessage(messagePrefix() + " §c转账失败（错误: " + err + "），出题已暂停。请检查服务器日志。" );
            getLogger().warning("扣款失败: " + err);
            return;
        }

        Object d = depositTo(winner, rewardAmount);
        if (!isEconomyResponseSuccess(d)) {
            // refund payer if possible
            String err = getEconomyResponseError(d);
            getLogger().warning("发放给胜利玩家失败: " + err + "。尝试退款。");
            depositTo(payerDisplay, rewardAmount);
            Bukkit.broadcastMessage(messagePrefix() + " §c发放奖励失败，已退款，请联系管理员。错误: " + err);
            return;
        }

        recordCorrect(winner, rewardAmount);
        Bukkit.broadcastMessage(messagePrefix() + " §a玩家 §f" + winner.getName() + " §a答对了问题，获得 §e" + rewardAmount + " §a货币！");
    }

    private boolean setupEconomy() {
        try {
            Class<?> econClass = Class.forName("net.milkbowl.vault.economy.Economy");
            // get registration via ServicesManager.getRegistration(Class)
            Object rsp = getServer().getServicesManager().getRegistration((Class) econClass);
            if (rsp == null) return false;
            // RegisteredServiceProvider has method getProvider()
            Method getProvider = rsp.getClass().getMethod("getProvider");
            Object provider = getProvider.invoke(rsp);
            this.econ = provider;
            return this.econ != null;
        } catch (ClassNotFoundException cnf) {
            getLogger().warning("Vault API 不在类路径中，无法加载 Economy 接口");
            return false;
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "加载经济提供者时出错", t);
            return false;
        }
    }

    // Reflection helpers for interacting with economy provider without compile-time Vault dependency
    private double getBalanceOf(String who) {
        if (econ == null) return 0.0;
            if (payerIsServer) {
            Double balance = extractBalance(invokeEconomy("bankBalance", new Class<?>[]{String.class}, who));
            if (balance != null) return balance;
        }
        if (payerUsesUuid && payerOffline != null) {
            try {
                Object response = invokeEconomy("getBalance", new Class<?>[]{org.bukkit.OfflinePlayer.class}, payerOffline);
                if (response instanceof Number) return ((Number) response).doubleValue();
            } catch (Throwable t) {
                getLogger().fine("CMI OfflinePlayer 余额查询失败，回退到账户名: " + t.getMessage());
            }
        }
        try {
            Object response = invokeEconomy("getBalance", new Class<?>[]{String.class}, who);
            if (response instanceof Number) return ((Number) response).doubleValue();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "查询账户 " + who + " 余额时出错", t);
        }
        if (payerOffline != null) {
            Object response = invokeEconomy("getBalance", new Class<?>[]{org.bukkit.OfflinePlayer.class}, payerOffline);
            if (response instanceof Number) return ((Number) response).doubleValue();
        }
        return 0.0;
    }

    private Object withdrawFrom(String who, double amount) {
        if (econ == null) return null;
        if (payerIsServer) {
            Object response = invokeEconomy("bankWithdraw", new Class<?>[]{String.class, double.class}, who, amount);
            if (response != null) return response;
        }
        if (payerUsesUuid && payerOffline != null) {
            try {
                Object response = invokeEconomy("withdrawPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, payerOffline, amount);
                if (response != null) return response;
            } catch (Throwable t) {
                getLogger().fine("CMI OfflinePlayer 扣款失败，回退到账户名: " + t.getMessage());
            }
        }
        try {
            Object response = invokeEconomy("withdrawPlayer", new Class<?>[]{String.class, double.class}, who, amount);
            if (response != null) return response;
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "从账户 " + who + " 扣款时出错", t);
        }
        if (payerOffline != null) {
            Object response = invokeEconomy("withdrawPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, payerOffline, amount);
            if (response != null) return response;
        }
        return null;
    }

    /** 给在线获奖者发奖：优先用 OfflinePlayer/UUID，避免改名玩家按旧名错发。 */
    private Object depositTo(Player winner, double amount) {
        if (econ == null) return null;
        // 在线玩家优先走 OfflinePlayer 通道（UUID 精确，防改名错发）
        try {
            Object response = invokeEconomy("depositPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, winner, amount);
            if (response != null) return response;
        } catch (Throwable ignored) {}
        return depositTo(winner.getName(), amount);
    }

    private Object depositTo(String who, double amount) {
        if (econ == null) return null;
        try {
            if (payerIsServer && who.equals(payerDisplay)) {
                Object bankResponse = invokeEconomy("bankDeposit", new Class<?>[]{String.class, double.class}, who, amount);
                if (bankResponse != null) return bankResponse;
            }
            if (payerUsesUuid && who != null && payerOffline != null && payerOffline.getName() != null && payerOffline.getName().equals(who)) {
                // deposit to offline payer
                try {
                    Object response = invokeEconomy("depositPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, payerOffline, amount);
                    if (response != null) return response;
                } catch (Throwable ignored) {}
            }
            try {
                Object response = invokeEconomy("depositPlayer", new Class<?>[]{String.class, double.class}, who, amount);
                if (response != null) return response;
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "给账户充值时出错", t);
        }
        return null;
    }

    private Object invokeEconomy(String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Class<?> economyInterface = Class.forName("net.milkbowl.vault.economy.Economy");
            return economyInterface.getMethod(methodName, parameterTypes).invoke(econ, arguments);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "调用经济方法 " + methodName + " 时出错", t);
            return null;
        }
    }

    private Double extractBalance(Object response) {
        if (response instanceof Number) return ((Number) response).doubleValue();
        if (response == null) return null;
        try {
            try {
                Method method = response.getClass().getMethod("getBalance");
                Object value = method.invoke(response);
                if (value instanceof Number) return ((Number) value).doubleValue();
            } catch (NoSuchMethodException ignored) {
                java.lang.reflect.Field field = response.getClass().getField("balance");
                Object value = field.get(response);
                if (value instanceof Number) return ((Number) value).doubleValue();
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "读取经济余额响应时出错", t);
        }
        return null;
    }

    private boolean isEconomyResponseSuccess(Object resp) {
        if (resp == null) return false;
        try {
            // try transactionSuccess() method
            try {
                Method m = resp.getClass().getMethod("transactionSuccess");
                Object r = m.invoke(resp);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (NoSuchMethodException ignored) {}
            // try success field
            try {
                java.lang.reflect.Field f = resp.getClass().getField("success");
                Object r = f.get(resp);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (NoSuchFieldException ignored) {}
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "检查经济响应时出错", t);
        }
        return false;
    }

    private String getEconomyResponseError(Object resp) {
        if (resp == null) return "null_response";
        try {
            // try errorMessage field
            try {
                java.lang.reflect.Field f = resp.getClass().getField("errorMessage");
                Object r = f.get(resp);
                if (r != null) return r.toString();
            } catch (NoSuchFieldException ignored) {}
            // try getErrorMessage() method
            try {
                Method m = resp.getClass().getMethod("getErrorMessage");
                Object r = m.invoke(resp);
                if (r != null) return r.toString();
            } catch (NoSuchMethodException ignored) {}
            // try toString()
            return resp.toString();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "读取经济响应错误信息时出错", t);
            return "error_read_failed";
        }
    }

    // Command handler
    private class QuizCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage("§6LetMeAsk 指令： /letmeask top|stats|status（查询） start|stop|question [force]|reload（管理）");
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            // 查询类子命令全员可用
            switch (sub) {
                case "top":
                    sendTop(sender, args.length > 1 ? args[1] : null);
                    return true;
                case "stats":
                    sendStats(sender, args.length > 1 ? args[1] : null);
                    return true;
            }
            // 管理类子命令需要权限
            if (!sender.hasPermission("letmeask.admin")) {
                sender.sendMessage("§c你没有权限执行此命令 (letmeask.admin)");
                return true;
            }
            switch (sub) {
                case "start":
                    startTask();
                    if (currentQuestion == null && !verifying && !paused) {
                        publishQuestion();
                        nextPostAtMillis = System.currentTimeMillis() + questionIntervalSeconds * 1000L;
                    }
                    sender.sendMessage("§a已启动定时出题");
                    return true;
                case "stop":
                    stopTask();
                    sender.sendMessage("§c已停止定时出题");
                    return true;
                case "question":
                case "q": {
                    boolean force = args.length > 1 && args[1].equalsIgnoreCase("force");
                    boolean ok = postNewQuestion(force);
                    if (ok) sender.sendMessage("§a已发布新题目");
                    else sender.sendMessage("§c无法发布新题目（已有题目/正在验证/已暂停）。使用 /letmeask question force 可强制发布");
                    return true;
                }
                case "reload": {
                    boolean ok = loadConfigValues();
                    // restart scheduler to pick up interval changes
                    startTask();
                    if (ok) sender.sendMessage("§a已重载配置(base.yml 与 questions.yml)");
                    else sender.sendMessage("§e配置已重载，但新题库为空，已保留旧题库继续运行");
                    return true;
                }
                case "status": {
                    sender.sendMessage("§6LetMeAsk 状态:");
                    sender.sendMessage(" 自动出题: " + (tickerTask != null ? "§a运行中" : "§c已停止"));
                    sender.sendMessage(" 题库数量: §f" + questions.size());
                    sender.sendMessage(" 当前题目: " + (currentQuestion != null ? currentQuestion.question : "无"));
                    sender.sendMessage(" 暂停(余额不足): " + (paused ? "§c是" : "§a否"));
                    sender.sendMessage(" 人机验证锁定: " + (verifying ? "§c是" : "§a否"));
                    sender.sendMessage(" 累计出题: §f" + totalAsked + " §7已答对: §f" + totalAnswered);
                    if (economyAvailable) {
                        sender.sendMessage(" 支付玩家: §f" + payerDisplay + " §7(余额: " + String.format("%.2f", getBalanceOf(payerDisplay)) + ")");
                    } else {
                        sender.sendMessage(" 经济系统: §e未检测到 Vault（纯公告模式，无货币奖励）");
                    }
                    return true;
                }
                default:
                    sender.sendMessage("§c未知子命令: " + sub);
                    return true;
            }
        }

        /** 解析 stats/top 的目标玩家：无参数查自己（需为玩家），有参数按名查找（离线也可）。 */
        private OfflinePlayer resolveStatsTarget(CommandSender sender, String name) {
            if (name == null || name.isEmpty()) {
                return (sender instanceof Player) ? (Player) sender : null;
            }
            OfflinePlayer off = Bukkit.getOfflinePlayer(name);
            // Bukkit#getOfflinePlayer(name) 对从未进服的名字也会返回占位对象，用是否玩过来过滤
            if (!off.hasPlayedBefore() && !off.isOnline()) return null;
            return off;
        }

        private void sendStats(CommandSender sender, String nameArg) {
            OfflinePlayer target = resolveStatsTarget(sender, nameArg);
            if (target == null) {
                if (nameArg == null) sender.sendMessage("§c控制台请指定玩家名：/letmeask stats <玩家名>");
                else sender.sendMessage("§c找不到玩家: " + nameArg);
                return;
            }
            String key = target.getUniqueId().toString();
            String display = target.getName() != null ? target.getName() : key;
            int correct = totalCorrect.getOrDefault(key, 0);
            double earned = totalEarned.getOrDefault(key, 0.0);
            sender.sendMessage("§6玩家 §f" + display + " §6的答题统计:");
            sender.sendMessage(" 答对: §f" + correct + " §7累计奖金: §e" + String.format("%.2f", earned));
        }

        private void sendTop(CommandSender sender, String countArg) {
            int count = 10;
            if (countArg != null && !countArg.isEmpty()) {
                try {
                    count = Math.min(20, Math.max(1, Integer.parseInt(countArg)));
                } catch (NumberFormatException ignored) {
                    sender.sendMessage("§c数量参数无效，使用默认值 10");
                }
            }
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totalCorrect.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            if (sorted.isEmpty()) {
                sender.sendMessage("§e暂无答题记录");
                return;
            }
            sender.sendMessage("§6答题排行榜 §7(前 " + Math.min(count, sorted.size()) + " 名):");
            int rank = 0;
            for (Map.Entry<String, Integer> e : sorted) {
                if (++rank > count) break;
                String name = e.getKey();
                try {
                    OfflinePlayer off = Bukkit.getOfflinePlayer(java.util.UUID.fromString(e.getKey()));
                    if (off.getName() != null) name = off.getName();
                } catch (IllegalArgumentException ignored) {}
                sender.sendMessage(" §e" + rank + ". §f" + name + " §7答对 §f" + e.getValue()
                        + " §7奖金 §e" + String.format("%.2f", totalEarned.getOrDefault(e.getKey(), 0.0)));
            }
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                List<String> subs = new ArrayList<>(Arrays.asList("top", "stats", "status"));
                if (sender.hasPermission("letmeask.admin")) {
                    subs.addAll(Arrays.asList("start", "stop", "question", "q", "reload"));
                }
                return subs.stream()
                        .filter(subcommand -> subcommand.startsWith(prefix))
                        .collect(java.util.stream.Collectors.toList());
            }

            if (args.length == 2 && (args[0].equalsIgnoreCase("question") || args[0].equalsIgnoreCase("q"))) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return "force".startsWith(prefix) ? Collections.singletonList("force") : Collections.emptyList();
            }

            return Collections.emptyList();
        }
    }

    // Simple question holder (supports multiple accepted answers + weight)
    private static class Question {
        final String question;
        final List<String> answers; // 任一匹配即算答对
        final int weight; // 出题权重（>=1），越大越容易被抽中
        volatile long postTime;
        volatile java.util.UUID id;

        Question(String q, List<String> as) {
            this(q, as, 1);
        }

        Question(String q, List<String> as, int w) {
            this.question = q;
            this.answers = Collections.unmodifiableList(new ArrayList<>(as));
            this.weight = Math.max(1, w);
        }

        /** 公布答案时展示的首选答案。 */
        String displayAnswer() {
            return answers.isEmpty() ? "" : answers.get(0);
        }
    }
}
