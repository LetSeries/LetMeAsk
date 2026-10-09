package com.cubex.quiz;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 人机验证桥接：HumanVerify 反射调用 + 缺失降级 + 结果判定。
 * 从 QuizPlugin 拆出；无编译期 HumanVerify 依赖，缺失时返回 null 由调用方直接发奖。
 */
public class HumanVerifyBridge {
    private final Logger logger;
    // 验证服务缺失类告警只刷一次：无 HumanVerify 的服每次触发验证都会走降级，不能每题刷 warning
    private volatile boolean missingWarned = false;

    public HumanVerifyBridge(Logger logger) {
        this.logger = logger;
    }

    /** 配置重载成功后调用：重置缺失告警标记（可能刚装上 HumanVerify）。 */
    public void resetMissingWarning() {
        missingWarned = false;
    }

    /**
     * 发起一次人机验证。返回 null 表示验证服务不可用，调用方应降级直接发奖；
     * 否则返回 CompletableFuture，调用方挂 whenComplete 处理结果。
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<?> requestVerification(Player player) {
        try {
            // 使用反射调用 HumanVerifyApi，避免将第三方实现打进本插件。
            Class<?> apiClass = Class.forName("org.cubexmc.humanverify.api.HumanVerifyApi");
            Object api = Bukkit.getServicesManager().load(apiClass);
            if (api == null) {
                warnMissingOnce("未能通过 ServicesManager 加载 HumanVerifyApi，跳过验证直接发奖。");
                return null;
            }
            Object future;
            try {
                future = apiClass.getMethod("requestVerification", org.bukkit.entity.Player.class, boolean.class)
                        .invoke(api, player, true);
            } catch (NoSuchMethodException nsme) {
                warnMissingOnce("HumanVerifyApi 没有 requestVerification(Player, boolean) 方法，跳过验证直接发奖。");
                return null;
            }
            if (!(future instanceof CompletableFuture)) {
                logger.warning("HumanVerifyApi.requestVerification 未返回 CompletableFuture，跳过验证直接发奖");
                return null;
            }
            return (CompletableFuture<?>) future;
        } catch (ClassNotFoundException cnf) {
            warnMissingOnce("HumanVerifyApi 类未找到，跳过验证直接发奖。请确认 HumanVerify 已安装并先于本插件加载。");
            return null;
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "调用人机验证 API 时出错，跳过验证直接发奖", t);
            return null;
        }
    }

    /**
     * 判定验证结果是否为通过：枚举名 SUCCESS 或 toString 为 SUCCESS 即算通过。
     * result 为 null（不应发生，防御性处理）返回 false。
     */
    public boolean isPassed(Object result) {
        if (result == null) return false;
        try {
            java.lang.reflect.Method nameM = result.getClass().getMethod("name");
            Object nm = nameM.invoke(result);
            return "SUCCESS".equals(nm);
        } catch (Exception e) {
            // fallback to toString
            return "SUCCESS".equals(result.toString());
        }
    }

    private void warnMissingOnce(String message) {
        if (!missingWarned) {
            missingWarned = true;
            logger.warning(message);
        } else {
            logger.fine(message);
        }
    }
}
