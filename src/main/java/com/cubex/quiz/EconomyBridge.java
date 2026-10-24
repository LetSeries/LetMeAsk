package com.cubex.quiz;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 经济桥接：Vault 反射调用 + 出资人解析 + 出资/退款通道。
 * 从 QuizPlugin 拆出，无编译期 Vault 依赖，缺失时优雅降级。
 */
public class EconomyBridge {
    private final Logger logger;

    // Vault 经济服务实例（Object 持有，避免编译期依赖 Vault API）
    // econ/economyClass 由主线程 setup 写入、异步发奖线程读取，需 volatile
    private volatile Object econ;
    // 反射方法缓存：status 余额异步查询后不再是主线程独占，用并发容器
    // ConcurrentHashMap 不存 null，缺失的方法记在 missingMethods 里
    private volatile Class<?> economyClass;
    private final Map<String, Method> economyMethods = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> missingMethods = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile String providerName = null;
    private volatile boolean bankSupport = true;

    /**
     * 解析后的出资人信息（支持 UUID / 玩家名 / Server / LittleSkin 前缀）。
     * 用不可变快照整体替换：reload 在主线程写、异步发奖线程读，
     * 若分字段写入会读到“新账户名 + 旧 isServer”的撕裂组合，导致扣错账户。
     */
    private record PayerInfo(org.bukkit.OfflinePlayer offline, boolean server,
                             boolean usesUuid, String display) {}

    private volatile PayerInfo payer = new PayerInfo(null, false, false, null);

    public EconomyBridge(Logger logger) {
        this.logger = logger;
    }

    public boolean setup() {
        try {
            Class<?> econClass = Class.forName("net.milkbowl.vault.economy.Economy");
            // 通过 ServicesManager 获取 Vault 经济服务注册信息
            Object rsp = Bukkit.getServer().getServicesManager().getRegistration(econClass);
            if (rsp == null) return false;
            // RegisteredServiceProvider 通过 getProvider() 取实际服务实例
            Method getProvider = rsp.getClass().getMethod("getProvider");
            Object provider = getProvider.invoke(rsp);
            this.econ = provider;
            if (this.econ == null) return false;
            this.economyClass = econClass;
            this.economyMethods.clear();
            this.missingMethods.clear();
            this.providerName = readEconomyName();
            this.bankSupport = readEconomyBankSupport();
            return true;
        } catch (ClassNotFoundException cnf) {
            logger.warning("Vault API 不在类路径中，无法加载 Economy 接口");
            return false;
        } catch (Throwable t) {
            logger.log(java.util.logging.Level.SEVERE, "加载经济提供者时出错", t);
            return false;
        }
    }

    public String getProviderName() {
        return providerName;
    }

    public boolean hasBankSupport() {
        return bankSupport;
    }

    public String getPayerDisplay() {
        return payer.display();
    }

    public boolean isPayerServer() {
        return payer.server();
    }

    public void resolvePayer(String payerName) {
        if (payerName == null) {
            payer = new PayerInfo(null, false, false, null);
            return;
        }
        if (payerName.equalsIgnoreCase("server") || payerName.equalsIgnoreCase("console")) {
            payer = new PayerInfo(null, true, false, payerName);
            return;
        }
        // 支持 littleskin:uuid 或 littleskin:name 两种写法
        if (payerName.toLowerCase().startsWith("littleskin:")) {
            String v = payerName.substring(payerName.indexOf(":") + 1);
            try {
                java.util.UUID uuid = java.util.UUID.fromString(v);
                payer = new PayerInfo(Bukkit.getOfflinePlayer(uuid), false, false, uuid.toString());
                return;
            } catch (IllegalArgumentException ignored) {
                // fall through to name
            }
            org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(v);
            payer = new PayerInfo(off, false, false, off.getName() != null ? off.getName() : v);
            return;
        }
        // 尝试按 UUID 解析
        try {
            java.util.UUID uuid = java.util.UUID.fromString(payerName);
            payer = new PayerInfo(Bukkit.getOfflinePlayer(uuid), false, true, uuid.toString());
            return;
        } catch (IllegalArgumentException ignored) {
        }
        // 兜底按玩家名处理
        org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(payerName);
        payer = new PayerInfo(off, false, false, off.getName() != null ? off.getName() : payerName);
    }

    /** 判断答对者是否为出资人（UUID 优先，其次名字忽略大小写比对）。 */
    public boolean isPayer(Player player) {
        if (player == null) return false;
        PayerInfo p = payer; // 单次快照，避免字段间不一致
        if (p.server()) return false; // Server/Console 账户不可能是真实玩家
        if (p.offline() != null && player.getUniqueId().equals(p.offline().getUniqueId())) return true;
        String name = player.getName();
        return name != null && p.display() != null && name.equalsIgnoreCase(p.display());
    }

    private String readEconomyName() {
        try {
            Object r = invoke("getName", new Class<?>[0]);
            if (r instanceof String) {
                String s = ((String) r).trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean readEconomyBankSupport() {
        try {
            Object r = invoke("hasBankSupport", new Class<?>[0]);
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) {}
        return true;
    }

    public double getBalanceOf(String who) {
        if (econ == null) return 0.0;
        PayerInfo p = payer; // 单次快照
        if (p.server() && bankSupport) {
            Double balance = extractBalance(invoke("bankBalance", new Class<?>[]{String.class}, who));
            if (balance != null) return saneBalance(balance);
        }
        // 注意：invoke 内部已捕获全部异常并返回 null，外层无需 try-catch
        if (p.usesUuid() && p.offline() != null) {
            Object uuidResponse = invoke("getBalance", new Class<?>[]{org.bukkit.OfflinePlayer.class}, p.offline());
            if (uuidResponse instanceof Number) return saneBalance(((Number) uuidResponse).doubleValue());
        }
        Object response = invoke("getBalance", new Class<?>[]{String.class}, who);
        if (response instanceof Number) return saneBalance(((Number) response).doubleValue());
        if (p.offline() != null) {
            Object offlineResponse = invoke("getBalance", new Class<?>[]{org.bukkit.OfflinePlayer.class}, p.offline());
            if (offlineResponse instanceof Number) return saneBalance(((Number) offlineResponse).doubleValue());
        }
        return 0.0;
    }

    /**
     * 余额消毒：Vault 后端异常时可能返回 NaN/无穷大，NaN 会让所有 >= 比较恒 false
     * （暂停后永不恢复），统一按 0.0 处理。
     */
    private static double saneBalance(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0.0) return 0.0;
        return v;
    }

    public Object withdrawFrom(String who, double amount) {
        if (econ == null) return null;
        PayerInfo p = payer; // 单次快照
        if (p.server() && bankSupport) {
            Object response = invoke("bankWithdraw", new Class<?>[]{String.class, double.class}, who, amount);
            if (response != null) return response;
        }
        // 注意：invoke 内部已捕获全部异常并返回 null，外层无需 try-catch
        if (p.usesUuid() && p.offline() != null) {
            Object uuidResponse = invoke("withdrawPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, p.offline(), amount);
            if (uuidResponse != null) return uuidResponse;
        }
        Object response = invoke("withdrawPlayer", new Class<?>[]{String.class, double.class}, who, amount);
        if (response != null) return response;
        if (p.offline() != null) {
            Object offlineResponse = invoke("withdrawPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, p.offline(), amount);
            if (offlineResponse != null) return offlineResponse;
        }
        return null;
    }

    /** 给在线获奖者发奖：优先用 OfflinePlayer/UUID，避免改名玩家按旧名错发。 */
    public Object depositTo(Player winner, double amount) {
        if (econ == null) return null;
        // 在线玩家优先走 OfflinePlayer 通道（UUID 精确，防改名错发）
        // 注意：invoke 内部已捕获全部异常，外层无需 try-catch
        Object response = invoke("depositPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, winner, amount);
        if (response != null) return response;
        return depositTo(winner.getName(), amount);
    }

    /**
     * 给出资人退款：必须与 withdrawFrom 走对称通道，否则钱会退错地方。
     * <ul>
     *   <li>Server 出资（bankWithdraw 扣的）→ bankDeposit 退回银行账户</li>
     *   <li>UUID 出资（OfflinePlayer 扣的）→ OfflinePlayer 退回</li>
     *   <li>普通玩家名出资 → 按名退回</li>
     * </ul>
     */
    public Object refundToPayer(double amount) {
        if (econ == null) return null;
        PayerInfo p = payer; // 单次快照，避免退款通道与扣款通道判定不一致
        // 注意：invoke 内部已捕获全部异常并返回 null，外层无需 try-catch
        if (p.server() && bankSupport) {
            Object response = invoke("bankDeposit", new Class<?>[]{String.class, double.class}, p.display(), amount);
            if (response != null) return response;
        }
        if (p.offline() != null && (p.usesUuid() || p.display() == null
                || (p.offline().getName() != null && p.offline().getName().equals(p.display())))) {
            Object offlineResponse = invoke("depositPlayer", new Class<?>[]{org.bukkit.OfflinePlayer.class, double.class}, p.offline(), amount);
            if (offlineResponse != null) return offlineResponse;
        }
        Object response = invoke("depositPlayer", new Class<?>[]{String.class, double.class}, p.display(), amount);
        if (response != null) return response;
        return null;
    }

    /** 按账户名发奖（仅用于非出资人退款等兜底路径）。 */
    public Object depositTo(String who, double amount) {
        if (econ == null) return null;
        Object response = invoke("depositPlayer", new Class<?>[]{String.class, double.class}, who, amount);
        if (response != null) return response;
        return null;
    }

    private Object invoke(String methodName, Class<?>[] parameterTypes, Object... arguments) {
        if (econ == null || economyClass == null) return null;
        try {
            String cacheKey = methodName + Arrays.toString(parameterTypes);
            if (missingMethods.contains(cacheKey)) return null; // 已知缺失的方法直接返回
            Method m = economyMethods.get(cacheKey);
            if (m == null) {
                try {
                    m = economyClass.getMethod(methodName, parameterTypes);
                } catch (NoSuchMethodException ignored) {
                    m = null;
                }
                if (m == null) {
                    missingMethods.add(cacheKey);
                    return null;
                }
                economyMethods.put(cacheKey, m);
            }
            return m.invoke(econ, arguments);
        } catch (Throwable t) {
            logger.log(java.util.logging.Level.WARNING, "调用经济方法 " + methodName + " 时出错", t);
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
            logger.log(java.util.logging.Level.WARNING, "读取经济余额响应时出错", t);
        }
        return null;
    }

    public boolean isSuccess(Object resp) {
        if (resp == null) return false;
        try {
            // 先试 transactionSuccess() 方法
            try {
                Method m = resp.getClass().getMethod("transactionSuccess");
                Object r = m.invoke(resp);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (NoSuchMethodException ignored) {}
            // 再试 success 字段
            try {
                java.lang.reflect.Field f = resp.getClass().getField("success");
                Object r = f.get(resp);
                if (r instanceof Boolean) return (Boolean) r;
            } catch (NoSuchFieldException ignored) {}
        } catch (Throwable t) {
            logger.log(java.util.logging.Level.WARNING, "检查经济响应时出错", t);
        }
        return false;
    }

    public String errorOf(Object resp) {
        if (resp == null) return "null_response";
        try {
            // 先试 errorMessage 字段
            try {
                java.lang.reflect.Field f = resp.getClass().getField("errorMessage");
                Object r = f.get(resp);
                if (r != null) return r.toString();
            } catch (NoSuchFieldException ignored) {}
            // 再试 getErrorMessage() 方法
            try {
                Method m = resp.getClass().getMethod("getErrorMessage");
                Object r = m.invoke(resp);
                if (r != null) return r.toString();
            } catch (NoSuchMethodException ignored) {}
            // 兜底用 toString()
            return resp.toString();
        } catch (Throwable t) {
            logger.log(java.util.logging.Level.WARNING, "读取经济响应错误信息时出错", t);
            return "error_read_failed";
        }
    }
}
