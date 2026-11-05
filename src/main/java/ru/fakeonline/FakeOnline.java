package ru.fakeonline;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class FakeOnline extends JavaPlugin implements TabExecutor {

    private final Random rnd = new Random();
    private final List<String> active = new ArrayList<>();
    private final Set<String> spawned = new HashSet<>();
    private final Map<String, List<BukkitTask>> botTasks = new HashMap<>();
    private final Map<String, BukkitTask> pendingDespawn = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private File stateFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "state.yml");

        PluginCommand cmd = getCommand("bots");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        long delay = Math.max(1, getConfig().getLong("start-delay-seconds", 20)) * 20L;
        tasks.add(Bukkit.getScheduler().runTaskLater(this, this::startup, delay));
    }

    @Override
    public void onDisable() {
        tasks.forEach(BukkitTask::cancel);
        botTasks.values().forEach(l -> l.forEach(BukkitTask::cancel));
        pendingDespawn.values().forEach(BukkitTask::cancel);
        if (getConfig().getBoolean("despawn-on-disable", false)) {
            for (String n : new ArrayList<>(spawned)) exec(fill("despawn", n));
        }
        tasks.clear();
        botTasks.clear();
        pendingDespawn.clear();
        active.clear();
        spawned.clear();
    }

    private void startup() {
        checkFpp();
        if (getConfig().getBoolean("despawn-all-on-start", true)) {
            exec(fill("despawn-all", ""));
        }
        tasks.add(Bukkit.getScheduler().runTaskLater(this, () -> apply(loadCount()), 60L));
    }

    private void checkFpp() {
        String tpl = getConfig().getString("commands.spawn", "");
        if (tpl == null || tpl.isBlank()) return;
        String first = tpl.trim().split("\\s+")[0].replaceFirst("^/", "");
        if (Bukkit.getCommandMap().getCommand(first) == null) {
            getLogger().warning("Команда '" + first + "' не найдена. FPP установлен и загружен? "
                    + "Если команды у вас называются иначе, поправьте раздел commands в config.yml.");
        }
    }

    private int loadCount() {
        YamlConfiguration st = YamlConfiguration.loadConfiguration(stateFile);
        return st.getInt("count", getConfig().getInt("count", 12));
    }

    private void saveCount(int n) {
        YamlConfiguration st = new YamlConfiguration();
        st.set("count", n);
        try {
            st.save(stateFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить state.yml: " + e.getMessage());
        }
    }

    private void apply(int wanted) {
        List<String> names = getConfig().getStringList("bots");
        int target = Math.max(0, Math.min(wanted, names.size()));
        long cursor = 0;

        while (active.size() > target) {
            String n = active.get(rnd.nextInt(active.size()));
            removeBot(n, cursor);
            cursor += gapTicks();
        }

        List<String> free = new ArrayList<>(names);
        free.removeAll(active);
        Collections.shuffle(free, rnd);
        int i = 0;
        while (active.size() < target && i < free.size()) {
            addBot(free.get(i++), cursor);
            cursor += gapTicks();
        }
    }

    private long gapTicks() {
        int min = Math.max(0, getConfig().getInt("gap-min-seconds", 3));
        int max = Math.max(min, getConfig().getInt("gap-max-seconds", 12));
        return 20L * (min + rnd.nextInt(max - min + 1));
    }

    private void addBot(String name, long delay) {
        BukkitTask pd = pendingDespawn.remove(name);
        if (pd != null) {
            pd.cancel();
            active.add(name);
            return;
        }

        active.add(name);
        long roamDelay = getConfig().getLong("roam-delay-ticks", 60);
        List<BukkitTask> list = new ArrayList<>();
        list.add(Bukkit.getScheduler().runTaskLater(this, () -> {
            exec(fill("spawn", name));
            spawned.add(name);
        }, delay));
        list.add(Bukkit.getScheduler().runTaskLater(this, () -> {
            exec(fill("roam", name));
            botTasks.remove(name);
        }, delay + roamDelay));
        botTasks.put(name, list);
    }

    private void removeBot(String name, long delay) {
        active.remove(name);
        List<BukkitTask> pend = botTasks.remove(name);
        if (pend != null) pend.forEach(BukkitTask::cancel);

        if (spawned.contains(name)) {
            BukkitTask t = Bukkit.getScheduler().runTaskLater(this, () -> {
                exec(fill("despawn", name));
                spawned.remove(name);
                pendingDespawn.remove(name);
            }, delay);
            pendingDespawn.put(name, t);
        }
    }

    private World world() {
        String w = getConfig().getString("world", "");
        World world = (w == null || w.isBlank()) ? null : Bukkit.getWorld(w);
        return world != null ? world : Bukkit.getWorlds().get(0);
    }

    private String fill(String key, String name) {
        String tpl = getConfig().getString("commands." + key, "");
        if (tpl == null || tpl.isBlank()) return "";

        World w = world();
        Location c = w.getSpawnLocation();
        int off = Math.max(0, getConfig().getInt("spawn-offset", 3));
        int x = c.getBlockX() + (off > 0 ? rnd.nextInt(off * 2 + 1) - off : 0);
        int z = c.getBlockZ() + (off > 0 ? rnd.nextInt(off * 2 + 1) - off : 0);
        int rmin = Math.max(1, getConfig().getInt("roam-radius-min", 12));
        int rmax = Math.max(rmin, getConfig().getInt("roam-radius-max", 25));
        int radius = rmin + rnd.nextInt(rmax - rmin + 1);

        return tpl
                .replace("{name}", name)
                .replace("{world}", w.getName())
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(c.getBlockY()))
                .replace("{z}", String.valueOf(z))
                .replace("{cx}", String.valueOf(c.getBlockX()))
                .replace("{cy}", String.valueOf(c.getBlockY()))
                .replace("{cz}", String.valueOf(c.getBlockZ()))
                .replace("{radius}", String.valueOf(radius));
    }

    private void exec(String cmd) {
        if (cmd == null || cmd.isBlank()) return;
        cmd = cmd.trim().replaceFirst("^/", "");
        if (getConfig().getBoolean("log-commands", true)) {
            getLogger().info("> " + cmd);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        int max = getConfig().getStringList("bots").size();

        if (a.length == 0) {
            s.sendMessage("Ботов в сети: " + active.size() + " из " + max
                    + (active.isEmpty() ? "" : " (" + String.join(", ", active) + ")"));
            s.sendMessage("Изменить количество: /" + label + " <число от 0 до " + max + ">");
            return true;
        }

        if (a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            s.sendMessage("Конфиг перезагружен. Уже созданные боты не затронуты.");
            return true;
        }

        int n;
        try {
            n = Integer.parseInt(a[0]);
        } catch (NumberFormatException e) {
            s.sendMessage("Использование: /" + label + " <число от 0 до " + max + "> | reload");
            return true;
        }
        if (n < 0 || n > max) {
            s.sendMessage("Число должно быть от 0 до " + max + " (столько ников в config.yml).");
            return true;
        }

        int before = active.size();
        saveCount(n);
        apply(n);
        s.sendMessage("Ботов было: " + before + ", станет: " + n + ". Изменения применятся постепенно.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) return List.of("reload");
        return List.of();
    }
}
