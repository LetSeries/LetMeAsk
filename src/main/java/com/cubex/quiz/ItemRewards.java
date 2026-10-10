package com.cubex.quiz;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * 物品奖励：解析 base.yml 的 items 列表，答对时与金币叠加发放。
 * 配置格式（random-one: true 时每次随机其一，否则全发）：
 * <pre>
 * items:
 *   enabled: true
 *   random-one: false
 *   list:
 *     - {material: DIAMOND, amount: 2}
 *     - {material: GOLD_INGOT, amount: 1, name: "&6幸运金锭", lore: ["&7答题奖励"], enchantments: {sharpness: 1}}
 * </pre>
 * 背包满时掉在脚下（不清背包、不取消发奖）。
 */
public class ItemRewards {
    private final Logger logger;
    private final Random random = new Random();

    private boolean enabled = false;
    private boolean randomOne = false;
    private final List<ItemSpec> specs = new ArrayList<>();

    /** 单条物品规格（解析期快照，reload 时整体替换）。 */
    private static class ItemSpec {
        Material material;
        int amount = 1;
        String name;
        List<String> lore;
        Map<Enchantment, Integer> enchantments;
    }

    public ItemRewards(Logger logger) {
        this.logger = logger;
    }

    /** 从 base.yml 的 items 节点加载；缺失/非法时整体禁用，不抛异常。 */
    @SuppressWarnings("unchecked")
    public void load(org.bukkit.configuration.file.FileConfiguration cfg) {
        enabled = false;
        randomOne = false;
        specs.clear();
        if (cfg == null) return;
        Object section = cfg.get("items");
        if (!(section instanceof Map) && cfg.getConfigurationSection("items") == null) return;
        enabled = cfg.getBoolean("items.enabled", false);
        if (!enabled) return;
        randomOne = cfg.getBoolean("items.random-one", false);
        List<?> list = cfg.getList("items.list");
        if (list == null || list.isEmpty()) {
            logger.warning("items.enabled=true 但 items.list 为空，已禁用物品奖励。");
            enabled = false;
            return;
        }
        if (list.size() > 50) {
            logger.warning("物品奖励条目过多（" + list.size() + "），仅加载前 50 条。");
            list = list.subList(0, 50);
        }
        for (Object entry : list) {
            if (!(entry instanceof Map)) {
                logger.warning("物品奖励条目格式错误已跳过（需为 map）： " + entry);
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) entry;
            Object matObj = map.get("material");
            if (matObj == null) {
                logger.warning("物品奖励缺少 material 已跳过。");
                continue;
            }
            Material material = Material.matchMaterial(matObj.toString().trim().toUpperCase(Locale.ROOT));
            if (material == null || !material.isItem()) {
                logger.warning("物品奖励材质无效已跳过: " + matObj);
                continue;
            }
            ItemSpec spec = new ItemSpec();
            spec.material = material;
            Object amountObj = map.get("amount");
            int amount = 1;
            if (amountObj instanceof Number) {
                amount = ((Number) amountObj).intValue();
            } else if (amountObj != null) {
                logger.warning("物品 " + material + " 数量不是数字，已按 1 处理。");
            }
            int clamped = Math.min(material.getMaxStackSize(), Math.max(1, amount));
            if (clamped != amount) {
                logger.warning("物品 " + material + " 数量 " + amount + " 越界，已钳制为 " + clamped
                        + "（范围 1~" + material.getMaxStackSize() + "）。");
            }
            spec.amount = clamped;
            Object nameObj = map.get("name");
            if (nameObj != null) spec.name = nameObj.toString().replace('&', '§');
            Object loreObj = map.get("lore");
            if (loreObj instanceof List) {
                List<?> rawLore = (List<?>) loreObj;
                if (rawLore.size() > 10) {
                    logger.warning("物品 " + material + " lore 超过 10 行，仅保留前 10 行。");
                }
                List<String> lore = new ArrayList<>();
                for (int i = 0; i < Math.min(rawLore.size(), 10); i++) {
                    Object line = rawLore.get(i);
                    if (line == null) continue;
                    String text = line.toString();
                    if (text.length() > 100) {
                        logger.warning("物品 " + material + " lore 第 " + (i + 1) + " 行超长已截断。");
                        text = text.substring(0, 100);
                    }
                    lore.add(text.replace('&', '§'));
                }
                if (!lore.isEmpty()) spec.lore = lore;
            }
            Object nameCheck = map.get("name");
            if (nameCheck != null && nameCheck.toString().length() > 50) {
                logger.warning("物品 " + material + " 自定义名超长已截断。");
                spec.name = nameCheck.toString().substring(0, 50).replace('&', '§');
            }
            Object enchObj = map.get("enchantments");
            if (enchObj instanceof Map) {
                Map<Enchantment, Integer> enchs = new HashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) enchObj).entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    Enchantment ench = parseEnchantment(e.getKey().toString());
                    if (ench == null) {
                        logger.warning("物品 " + material + " 附魔无效已跳过: " + e.getKey());
                        continue;
                    }
                    int level = 1;
                    if (e.getValue() instanceof Number) {
                        level = Math.max(1, ((Number) e.getValue()).intValue());
                    }
                    enchs.put(ench, level);
                }
                if (!enchs.isEmpty()) spec.enchantments = enchs;
            }
            specs.add(spec);
        }
        if (specs.isEmpty()) {
            logger.warning("物品奖励解析后为空，已禁用物品奖励。");
            enabled = false;
        }
    }

    /** 附魔名兼容：minecraft:sharpness / SHARPNESS / sharpness 都接受。 */
    private Enchantment parseEnchantment(String raw) {
        if (raw == null) return null;
        String key = raw.trim();
        try {
            if (key.contains(":")) {
                return Enchantment.getByKey(NamespacedKey.fromString(key.toLowerCase(Locale.ROOT)));
            }
            Enchantment byKey = Enchantment.getByKey(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
            if (byKey != null) return byKey;
            return Enchantment.getByName(key.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean isEnabled() {
        return enabled && !specs.isEmpty();
    }

    /** 构建待发放的物品列表（random-one 时随机其一，否则全发）。 */
    public List<ItemStack> roll() {
        List<ItemStack> out = new ArrayList<>();
        if (!isEnabled()) return out;
        if (randomOne) {
            out.add(build(specs.get(random.nextInt(specs.size()))));
        } else {
            for (ItemSpec spec : specs) {
                out.add(build(spec));
            }
        }
        return out;
    }

    private ItemStack build(ItemSpec spec) {
        ItemStack item = new ItemStack(spec.material, spec.amount);
        boolean needMeta = spec.name != null || spec.lore != null;
        if (needMeta) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (spec.name != null) meta.setDisplayName(spec.name);
                if (spec.lore != null) meta.setLore(new ArrayList<>(spec.lore));
                item.setItemMeta(meta);
            }
        }
        if (spec.enchantments != null) {
            try {
                item.addUnsafeEnchantments(spec.enchantments);
            } catch (Throwable t) {
                logger.warning("物品附魔应用失败，已发放无附魔版本: " + spec.material);
            }
        }
        return item;
    }

    /**
     * 发放物品：优先进背包，满时掉在脚下（不清背包、不取消发奖）。
     * 返回成功发进背包的数量（掉地上的不计）。
     */
    public int give(Player winner, List<ItemStack> items) {
        int bagged = 0;
        for (ItemStack item : items) {
            if (item == null) continue;
            Map<Integer, ItemStack> leftover;
            try {
                leftover = winner.getInventory().addItem(item.clone());
            } catch (Throwable t) {
                logger.warning("发放物品失败，已改为掉落: " + item.getType());
                leftover = new HashMap<>();
                leftover.put(0, item.clone());
            }
            if (leftover.isEmpty()) {
                bagged += item.getAmount();
            } else {
                // 背包满：掉在脚下，保证奖励不丢失
                for (ItemStack drop : leftover.values()) {
                    if (drop == null) continue;
                    try {
                        winner.getWorld().dropItemNaturally(winner.getLocation(), drop);
                    } catch (Throwable t) {
                        logger.warning("掉落物品失败，已丢失: " + drop.getType() + "x" + drop.getAmount());
                    }
                }
            }
        }
        return bagged;
    }

    /**
     * 展示名：根据实际 roll 出的物品生成（如 "2x Diamond"），保证广播与实发一致。
     * random-one 时只描述抽中的那一件，不再写死"随机物品"。
     */
    public String describe(List<ItemStack> rolled) {
        if (rolled == null || rolled.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rolled.size(); i++) {
            if (i > 0) sb.append("、");
            ItemStack item = rolled.get(i);
            if (item == null) continue;
            sb.append(item.getAmount()).append("x ").append(displayNameOf(item));
        }
        return sb.toString();
    }

    /** 展示名后备：配置规格描述（roll 前预览用，如 status 页）。 */
    public String describeSpecs() {
        if (!isEnabled()) return "";
        if (randomOne) return "随机物品（" + specs.size() + " 选 1）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) sb.append("、");
            ItemSpec s = specs.get(i);
            sb.append(s.amount).append("x ").append(prettyName(s));
        }
        return sb.toString();
    }

    /** 物品展示名：自定义名优先，否则材质可读名。 */
    private String displayNameOf(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) return meta.getDisplayName();
        } catch (Throwable ignored) {}
        String[] parts = item.getType().name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private String prettyName(ItemSpec spec) {
        if (spec.name != null) return spec.name;
        // 材质名转可读：DIAMOND_SWORD -> Diamond Sword
        String[] parts = spec.material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
