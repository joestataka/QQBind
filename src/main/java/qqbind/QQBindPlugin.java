package qqbind;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * QQBind —— 进服绑定验证白名单。
 *
 * 未绑定的玩家在登录阶段（AsyncPlayerPreLoginEvent）被拦截，客户端断开界面
 * 会展示专属验证码；玩家到 QQ 群发送「绑定 <验证码>」后，由 AstrBot 通过
 * RCON 执行 /qqbind verify 完成绑定，再次进服即放行。
 */
public class QQBindPlugin extends JavaPlugin implements Listener {

    private static final Pattern QQ_PATTERN = Pattern.compile("^[0-9]{5,20}$");
    private static final DateTimeFormatter EXPIRE_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    /** 玩家名（保留原始大小写）-> QQ 号；一个 QQ 可绑定多个玩家名 */
    private final Map<String, String> bindings = new ConcurrentHashMap<>();
    /** 玩家名（小写）-> 待验证码 */
    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();

    private File bindFile;
    private long ttlMillis = 600_000L;
    private int maxBindingsPerQq = 3;
    private boolean bypassOps = false;
    private final List<String> bypassPlayers = new ArrayList<>();

    private static final class Pending {
        final String code;
        final String playerName;
        final long expireAt;

        Pending(String code, String playerName, long expireAt) {
            this.code = code;
            this.playerName = playerName;
            this.expireAt = expireAt;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        reloadSettings();
        loadBindings();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("QQBind 已启用，已绑定玩家数: " + bindings.size());
    }

    @Override
    public void onDisable() {
        saveBindings();
        getLogger().info("QQBind 已关闭");
    }

    private void reloadSettings() {
        ttlMillis = Math.max(30, getConfig().getInt("code-ttl-seconds", 600)) * 1000L;
        maxBindingsPerQq = Math.max(1, getConfig().getInt("max-bindings-per-qq", 3));
        bypassOps = getConfig().getBoolean("bypass-ops", false);
        bypassPlayers.clear();
        for (String name : getConfig().getStringList("bypass-players")) {
            if (name != null && !name.isBlank()) {
                bypassPlayers.add(name.trim());
            }
        }
    }

    // ---- 进服拦截 ----

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String name = event.getName();
        if (name == null || name.isBlank()) {
            return;
        }
        if (isBypassed(name)) {
            return;
        }
        if (findKey(name) != null) {
            return;
        }

        long now = System.currentTimeMillis();
        String key = name.toLowerCase(Locale.ROOT);
        Pending pending = pendings.get(key);
        if (pending == null || pending.expireAt <= now) {
            pending = new Pending(newCode(), name, now + ttlMillis);
            pendings.put(key, pending);
        }

        String message = renderMessage(name, pending);
        event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                LegacyComponentSerializer.legacySection().deserialize(message)
        );
        getLogger().info("拦截未绑定玩家 " + name + "，验证码=" + pending.code);
    }

    private boolean isBypassed(String name) {
        for (String allowed : bypassPlayers) {
            if (allowed.equalsIgnoreCase(name)) {
                return true;
            }
        }
        if (bypassOps) {
            try {
                return Bukkit.getOfflinePlayer(name).isOp();
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private String newCode() {
        for (int i = 0; i < 200; i++) {
            String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
            boolean used = false;
            for (Pending p : pendings.values()) {
                if (p.code.equals(code)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return code;
            }
        }
        return String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private String renderMessage(String player, Pending pending) {
        List<String> lines = getConfig().getStringList("message");
        if (lines == null || lines.isEmpty()) {
            lines = defaultMessage(player, pending);
        }
        String expire = EXPIRE_FMT.format(
                Instant.ofEpochMilli(pending.expireAt).atZone(ZoneId.systemDefault())
        );
        String group = String.join(" / ", getConfig().getStringList("group-ids"));
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line
                    .replace("{player}", player)
                    .replace("{code}", pending.code)
                    .replace("{expire}", expire)
                    .replace("{group}", group)
                    .replace("{groups}", group)
            ).append('\n');
        }
        return sb.toString();
    }

    private List<String> defaultMessage(String player, Pending pending) {
        String expire = EXPIRE_FMT.format(
                Instant.ofEpochMilli(pending.expireAt).atZone(ZoneId.systemDefault())
        );
        List<String> lines = new ArrayList<>();
        lines.add("§4未绑定社交账号");
        lines.add("§c" + player + " 进入服务器需要绑定您的社交账号");
        lines.add("");
        lines.add("§e发送内容：§a绑定 " + pending.code);
        lines.add("§e请在 " + expire + " 前完成验证");
        lines.add("§c验证超时后需重新进入服务器");
        return lines;
    }

    // ---- 绑定表 ----

    private void loadBindings() {
        bindings.clear();
        bindFile = new File(getDataFolder(), "bindings.yml");
        if (!bindFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(bindFile);
        for (String key : yaml.getKeys(false)) {
            String qq = yaml.getString(key);
            if (qq != null && !qq.isBlank()) {
                bindings.put(key, qq.trim());
            }
        }
    }

    private synchronized void saveBindings() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            yaml.set(entry.getKey(), entry.getValue());
        }
        try {
            if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
                getLogger().warning("无法创建数据目录: " + getDataFolder());
                return;
            }
            yaml.save(bindFile);
        } catch (IOException ex) {
            getLogger().warning("保存绑定表失败: " + ex.getMessage());
        }
    }

    // ---- 指令 ----

    private String usage() {
        return "§e/qqbind verify <验证码> <QQ号>  §7完成绑定\n"
                + "§e/qqbind unbind <玩家名>  §7解除绑定并移出白名单\n"
                + "§e/qqbind list  §7列出所有绑定\n"
                + "§e/qqbind status <玩家名>  §7查询绑定\n"
                + "§e/qqbind whois <QQ号>  §7查询某QQ绑定的账号\n"
                + "§e/qqbind reload  §7重载配置";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("qqbind")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage(usage());
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "verify":
                return handleVerify(sender, args);
            case "unbind":
                return handleUnbind(sender, args);
            case "list":
                return handleList(sender);
            case "status":
                return handleStatus(sender, args);
            case "whois":
                return handleWhois(sender, args);
            case "reload":
                reloadConfig();
                reloadSettings();
                sender.sendMessage("OK:配置已重载");
                return true;
            default:
                sender.sendMessage(usage());
                return true;
        }
    }

    private boolean handleVerify(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("FAIL:用法 /qqbind verify <验证码> <QQ号>");
            return true;
        }
        String code = args[1].trim();
        String qq = args[2].trim();
        if (!QQ_PATTERN.matcher(qq).matches()) {
            sender.sendMessage("FAIL:QQ号格式无效");
            return true;
        }

        String hitKey = null;
        Pending hit = null;
        for (Map.Entry<String, Pending> entry : pendings.entrySet()) {
            if (entry.getValue().code.equals(code)) {
                hitKey = entry.getKey();
                hit = entry.getValue();
                break;
            }
        }
        if (hit == null) {
            sender.sendMessage("FAIL:验证码无效（玩家需先进服获取验证码）");
            return true;
        }
        if (hit.expireAt <= System.currentTimeMillis()) {
            pendings.remove(hitKey);
            sender.sendMessage("FAIL:验证码已过期，请玩家重新进入服务器");
            return true;
        }

        String playerName = hit.playerName;
        String existingKey = findKey(playerName);
        if (existingKey != null) {
            String existingQq = bindings.get(existingKey);
            if (!qq.equals(existingQq)) {
                sender.sendMessage("FAIL:该游戏ID已绑定其他 QQ（" + existingQq + "），请先解绑");
                return true;
            }
        } else {
            int boundCount = playersOf(qq).size();
            if (boundCount >= maxBindingsPerQq) {
                sender.sendMessage(
                        "FAIL:该 QQ 已绑定 " + boundCount + " 个游戏账号，已达上限 "
                                + maxBindingsPerQq + " 个，请先解绑"
                );
                return true;
            }
        }

        bindings.put(playerName, qq);
        pendings.remove(hitKey);
        saveBindings();
        String players = String.join(",", playersOf(qq));
        getLogger().info("绑定成功: " + playerName + " -> QQ " + qq + "（该QQ共 " + playersOf(qq).size() + " 个）");
        sender.sendMessage("OK:绑定成功 玩家=" + playerName + " QQ=" + qq + " 列表=" + players);
        return true;
    }

    /** 按玩家名大小写不敏感地找绑定表里的键；未绑定返回 null。 */
    private String findKey(String playerName) {
        for (String key : bindings.keySet()) {
            if (key.equalsIgnoreCase(playerName)) {
                return key;
            }
        }
        return null;
    }

    /** 返回某 QQ 已绑定的全部玩家名。 */
    private List<String> playersOf(String qq) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            if (entry.getValue().equals(qq)) {
                result.add(entry.getKey());
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private boolean handleUnbind(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("FAIL:用法 /qqbind unbind <玩家名>");
            return true;
        }
        String playerName = args[1].trim();
        String key = findKey(playerName);
        if (key == null) {
            sender.sendMessage("FAIL:该玩家未绑定");
            return true;
        }
        String qq = bindings.remove(key);
        saveBindings();
        String players = String.join(",", playersOf(qq));
        getLogger().info("解除绑定: " + key + "（原 QQ " + qq + "）");
        sender.sendMessage("OK:已解绑 玩家=" + key + " 原QQ=" + qq + " 列表=" + players);
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (bindings.isEmpty()) {
            sender.sendMessage("OK:当前没有绑定记录");
            return true;
        }
        StringBuilder sb = new StringBuilder("OK:共 ").append(bindings.size()).append(" 条绑定");
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            sb.append("; ").append(entry.getKey()).append("=").append(entry.getValue());
        }
        sender.sendMessage(sb.toString());
        return true;
    }

    private boolean handleWhois(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("FAIL:用法 /qqbind whois <QQ号>");
            return true;
        }
        String qq = args[1].trim();
        List<String> players = playersOf(qq);
        if (players.isEmpty()) {
            sender.sendMessage("OK:该 QQ 未绑定任何游戏账号");
        } else {
            sender.sendMessage("OK:该 QQ 已绑定 " + players.size() + " 个账号 列表="
                    + String.join(",", players));
        }
        return true;
    }

    private boolean handleStatus(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("FAIL:用法 /qqbind status <玩家名>");
            return true;
        }
        String name = args[1].trim();
        String key = findKey(name);
        String qq = key == null ? null : bindings.get(key);
        Pending pending = pendings.get(name.toLowerCase(Locale.ROOT));
        if (qq != null) {
            sender.sendMessage("OK:已绑定 QQ=" + qq);
        } else if (pending != null && pending.expireAt > System.currentTimeMillis()) {
            sender.sendMessage("OK:未绑定，待验证码=" + pending.code);
        } else {
            sender.sendMessage("OK:未绑定，无待验证码");
        }
        return true;
    }
}