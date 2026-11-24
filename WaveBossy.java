package pl.wavebossy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

public class WaveBossy extends JavaPlugin implements Listener {

    // 10 mechanik ataku x 10 poziomow sily = 100 atakow (indeks 0..99)
    static final String[] PATTERNS = {"SLAM", "LIGHTNING", "SUMMON", "FIREBALL", "BLINK",
            "POISON", "FREEZE", "LAUNCH", "WITHER", "QUAKE"};
    static final String[] ANIMS = {"NONE", "RING", "PILLAR", "SPIRAL", "BURST",
            "RAIN", "SOUL", "CRACK", "HELIX", "WAVE"};
    static final int ATTACK_COUNT = 100;
    static final String[] SC = {"ᴀ","ʙ","ᴄ","ᴅ","ᴇ","ꜰ","ɢ","ʜ","ɪ","ᴊ","ᴋ","ʟ","ᴍ","ɴ","ᴏ","ᴘ","ǫ","ʀ","ѕ","ᴛ","ᴜ","ᴠ","ᴡ","x","ʏ","ᴢ"};

    static class Boss {
        final String id;
        String name;
        double hp = 300, armor = 0;
        int attack = 0;              // 0..99
        String anim = "RING";
        boolean bar = true, board = true, showHp = true;
        int respawn = 1;
        // stan runtime (nie zapisuje się)
        Location loc;
        LivingEntity zombie;
        ArmorStand nameStand, hpStand;
        BossBar bossBar;
        Scoreboard sb;
        int cd;
        Boss(String id, String name) { this.id = id; this.name = name; }
    }

    private final Map<String, Boss> bosses = new LinkedHashMap<>();
    private final Map<UUID, String> openGui = new HashMap<>();
    private final Map<UUID, String> editing = new HashMap<>();
    private final Map<UUID, Location> signPos = new HashMap<>();
    private final Set<UUID> creating = new HashSet<>();
    private final Random random = new Random();
    private File dataFile;

    // ===================== START / STOP =====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "bosses.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("wavebossy").setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 10L, 10L);
        getLogger().info("WaveBossy wlaczony. Bossow: " + bosses.size());
    }

    @Override
    public void onDisable() {
        for (Boss b : bosses.values()) {
            clearRuntime(b);
            if (b.zombie != null) b.zombie.remove();
            b.zombie = null;
        }
    }

    // ===================== KOMENDY =====================

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Komenda tylko dla graczy.");
            return true;
        }
        if (a.length == 0) {
            p.sendMessage(pref() + "Wpisz /wavebossy pomoc");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "stworz" -> openCreate(p);
            case "lista" -> openList(p);
            case "edytuj" -> {
                if (a.length < 2 || !bosses.containsKey(a[1])) p.sendMessage(pref() + "Nie ma takiego bossa.");
                else openEditor(p, a[1]);
            }
            case "spawn" -> {
                Boss b = a.length < 2 ? null : bosses.get(a[1]);
                if (b == null) p.sendMessage(pref() + "Nie ma takiego bossa.");
                else spawnBoss(b, frontLoc(p));
            }
            case "reload" -> {
                reloadConfig();
                for (Boss b : bosses.values()) { clearRuntime(b); if (b.zombie != null) b.zombie.remove(); }
                bosses.clear();
                load();
                p.sendMessage(pref() + "Przeladowano config.yml i bosses.yml.");
            }
            case "pomoc" -> {
                p.sendMessage(pref() + "/wavebossy stworz §7- kreator bossa");
                p.sendMessage(pref() + "/wavebossy lista §7- lista bossow");
                p.sendMessage(pref() + "/wavebossy edytuj <id> §7- edytor");
                p.sendMessage(pref() + "/wavebossy spawn <id> §7- przywolaj bossa");
                p.sendMessage(pref() + "/wavebossy reload §7- przeladuj pliki");
            }
            default -> p.sendMessage(pref() + "Nieznana podkomenda. Wpisz /wavebossy pomoc.");
        }
        return true;
    }

    // ===================== GUI: LISTA =====================

    private void openList(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, legacy("&6" + sc("Lista bossów")));
        fill(inv);
        int i = 0;
        for (Boss b : bosses.values()) {
            if (i >= 45) break;
            boolean up = b.zombie != null && b.zombie.isValid();
            ItemStack it = item(iconFor(b.attack / 10), sc(b.name),
                    "&8ID: &f" + b.id,
                    "&cZdrowie: &f" + (int) b.hp + "  &7Zbroja: &f" + (int) b.armor,
                    "&6Atak: &f" + attackName(b.attack),
                    "&bAnimacja: &f" + b.anim,
                    "&eRespawn: &f" + b.respawn + "h",
                    "&7BossBar: " + (b.bar ? "&awłączony" : "&cwyłączony")
                            + "  &7Scoreboard: " + (b.board ? "&awłączony" : "&cwyłączony"),
                    "&7HP nad bossem: " + (b.showHp ? "&awidoczne" : "&cukryte"),
                    "",
                    up ? "&a● Żywy" : "&8● Martwy",
                    "",
                    "&7Lewy klik: &fedytuj",
                    "&7Prawy klik: &fprzywołaj");
            ItemMeta m = it.getItemMeta();
            m.setEnchantmentGlintOverride(up);
            it.setItemMeta(m);
            inv.setItem(i++, it);
        }
        if (bosses.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, sc("Brak bossów"), "Kliknij gwiazdę na dole"));
        }
        inv.setItem(49, item(Material.NETHER_STAR, sc("Stwórz nowego bossa"), "Kliknij, aby otworzyć kreator"));
        p.openInventory(inv);
        openGui.put(p.getUniqueId(), "list");
    }

    // ===================== GUI: KREATOR + TABLICZKA =====================

    private void openCreate(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, legacy("&6" + sc("Stwórz bossa")));
        fill(inv);
        inv.setItem(13, item(Material.NETHER_STAR, sc("Stwórz nowego bossa"),
                "Kliknij, aby wpisać nazwę na tabliczce"));
        p.openInventory(inv);
        openGui.put(p.getUniqueId(), "create");
    }

    private void startSign(Player p) {
        Location loc = p.getLocation().getBlock().getLocation().add(0, 3, 0);
        Block block = loc.getBlock();
        block.setType(Material.OAK_SIGN, false);
        Sign sign = (Sign) block.getState();
        Side front = Side.FRONT;
        sign.getSide(front).line(0, Component.text("↓↓↓↓↓"));
        sign.getSide(front).line(2, Component.text("↑↑↑↑↑"));
        sign.getSide(front).line(3, Component.text("Wpisz tutaj nazwe bossa"));
        sign.update(false, false);
        creating.add(p.getUniqueId());
        signPos.put(p.getUniqueId(), loc);
        p.openSign(sign, front);
    }

    @EventHandler
    public void onSign(SignChangeEvent e) {
        Player p = e.getPlayer();
        if (!creating.remove(p.getUniqueId())) return;
        Location loc = signPos.remove(p.getUniqueId());
        if (loc != null) Bukkit.getScheduler().runTask(this, () -> loc.getBlock().setType(Material.AIR, false));

        String name = PlainTextComponentSerializer.plainText().serialize(e.line(1)).trim();
        if (name.isEmpty()) {
            p.sendMessage(pref() + "Nie wpisałeś nazwy bossa.");
            return;
        }
        String id = name.toLowerCase().replace(' ', '-');
        if (bosses.containsKey(id)) {
            p.sendMessage(pref() + "Boss o takiej nazwie już istnieje.");
            return;
        }
        bosses.put(id, newBoss(id, name));
        save();
        p.sendMessage(pref() + "Utworzono bossa §e" + sc(name) + "§r.");
        Bukkit.getScheduler().runTask(this, () -> openEditor(p, id));
    }

    // ===================== GUI: EDYTOR =====================

    private void openEditor(Player p, String id) {
        Boss b = bosses.get(id);
        if (b == null) return;
        Inventory inv = Bukkit.createInventory(null, 27, legacy("&6" + sc("Edytor bossa")));
        fill(inv);
        inv.setItem(4, item(Material.BOOK, sc("Boss: " + b.name), "ID: " + b.id, "Atak: " + attackName(b.attack)));
        inv.setItem(6, item(b.showHp ? Material.LIME_DYE : Material.GRAY_DYE,
                sc("HP nad bossem: " + (b.showHp ? "widoczne" : "ukryte")), "Kliknij, aby przełączyć"));
        inv.setItem(8, item(Material.BLAZE_POWDER, sc("Przywołaj bossa"), "Kliknij, aby przywołać przed sobą"));
        inv.setItem(10, item(Material.REDSTONE, sc("Zdrowie: " + (int) b.hp), "Lewy: +100  |  Prawy: -100"));
        inv.setItem(12, item(Material.IRON_CHESTPLATE, sc("Zbroja: " + (int) b.armor), "Lewy: +1  |  Prawy: -1"));
        inv.setItem(14, item(iconFor(b.attack / 10), sc("Atak: " + attackName(b.attack)),
                "Lewy: następny (1-100)  |  Prawy: poprzedni"));
        inv.setItem(16, item(Material.FIREWORK_ROCKET, sc("Animacja: " + b.anim), "Kliknij, aby zmienić (10 animacji)"));
        inv.setItem(18, item(Material.ARROW, sc("Wróć do listy"), "Kliknij, aby wrócić do listy"));
        inv.setItem(20, item(b.bar ? Material.LIME_DYE : Material.GRAY_DYE,
                sc("BossBar: " + (b.bar ? "włączony" : "wyłączony")), "Kliknij, aby przełączyć"));
        inv.setItem(22, item(b.board ? Material.LIME_DYE : Material.GRAY_DYE,
                sc("Scoreboard: " + (b.board ? "włączony" : "wyłączony")), "Kliknij, aby przełączyć"));
        inv.setItem(24, item(Material.CLOCK, sc("Respawn: " + b.respawn + "h"), "Lewy: +1h  |  Prawy: -1h"));
        inv.setItem(26, item(Material.BARRIER, sc("Usuń bossa"), "Kliknij, aby usunąć bossa"));

        editing.put(p.getUniqueId(), id);
        p.openInventory(inv);
        openGui.put(p.getUniqueId(), "editor");
    }

    private void editorClick(Player p, String id, int slot, boolean right) {
        Boss b = bosses.get(id);
        if (b == null) return;
        switch (slot) {
            case 6 -> b.showHp = !b.showHp;
            case 8 -> {
                p.closeInventory();
                if (b.zombie != null && b.zombie.isValid()) p.sendMessage(pref() + "Boss już żyje.");
                else spawnBoss(b, frontLoc(p));
                return;
            }
            case 10 -> b.hp = clamp(b.hp + (right ? -100 : 100), 50, 100000);
            case 12 -> b.armor = clamp(b.armor + (right ? -1 : 1), 0, 30);
            case 14 -> b.attack = Math.floorMod(b.attack + (right ? -1 : 1), ATTACK_COUNT);
            case 16 -> b.anim = next(ANIMS, b.anim);
            case 18 -> { openList(p); return; }
            case 20 -> b.bar = !b.bar;
            case 22 -> b.board = !b.board;
            case 24 -> b.respawn = (int) clamp(b.respawn + (right ? -1 : 1), 1, 72);
            case 26 -> {
                removeBoss(b);
                p.closeInventory();
                p.sendMessage(pref() + "Usunięto bossa.");
                return;
            }
            default -> { return; }
        }
        save();
        openEditor(p, id);
    }

    // ===================== KLIKNIĘCIA =====================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String gui = openGui.get(p.getUniqueId());
        if (gui == null) return;
        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;
        boolean right = e.isRightClick();

        if (gui.equals("list")) {
            if (slot == 49) { openCreate(p); return; }
            if (slot >= 45) return;
            List<Boss> list = new ArrayList<>(bosses.values());
            if (slot >= list.size()) return;
            Boss b = list.get(slot);
            if (right) {
                if (b.zombie != null && b.zombie.isValid()) {
                    p.sendMessage(pref() + "Boss §e" + sc(b.name) + "§r już żyje.");
                } else {
                    p.closeInventory();
                    spawnBoss(b, frontLoc(p));
                    p.sendMessage(pref() + "Przywołano bossa §e" + sc(b.name) + "§r.");
                }
            } else {
                openEditor(p, b.id);
            }
        } else if (gui.equals("create")) {
            if (slot == 13) {
                p.closeInventory();
                Bukkit.getScheduler().runTask(this, () -> startSign(p));
            }
        } else if (gui.equals("editor")) {
            String id = editing.get(p.getUniqueId());
            if (id != null) editorClick(p, id, slot, right);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        openGui.remove(e.getPlayer().getUniqueId());
    }

    // ===================== SPAWN + ANIMACJE =====================

    private void spawnBoss(Boss b, Location loc) {
        if (b.zombie != null && b.zombie.isValid()) return;
        b.loc = loc;
        World w = loc.getWorld();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t < 40) {
                    playAnim(b.anim, w, loc, t);
                    t++;
                    return;
                }
                cancel();
                Zombie z = (Zombie) w.spawnEntity(loc, EntityType.ZOMBIE);
                z.setPersistent(true);
                z.setRemoveWhenFarAway(false);
                z.setCanPickupItems(false);
                z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(b.hp);
                z.setHealth(b.hp);
                z.getAttribute(Attribute.ARMOR).setBaseValue(b.armor);
                b.zombie = z;
                b.cd = 0;
                b.nameStand = stand(loc.clone().add(0, 2.7, 0));
                b.hpStand = stand(loc.clone().add(0, 2.45, 0));
                if (b.bar) b.bossBar = Bukkit.createBossBar(sc(b.name), BarColor.RED, BarStyle.SEGMENTED_10);
                if (b.board) b.sb = makeBoard();
                w.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 1f, 1f);
                updateHolo(b);
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void playAnim(String anim, World w, Location c, int t) {
        switch (anim) {
            case "RING" -> {
                double r = 4.0 * (1 - t / 40.0) + 0.5;
                for (int i = 0; i < 36; i++) {
                    double a = i * Math.PI / 18;
                    w.spawnParticle(Particle.FLAME, c.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), 1, 0, 0, 0, 0);
                }
            }
            case "PILLAR" -> w.spawnParticle(Particle.END_ROD, c.clone().add(0, t * 0.1, 0), 6, 0.3, 0.1, 0.3, 0.02);
            case "SPIRAL" -> {
                double a = t * 0.4;
                w.spawnParticle(Particle.FLAME, c.clone().add(Math.cos(a) * 2, t * 0.07, Math.sin(a) * 2), 2, 0, 0, 0, 0);
            }
            case "BURST" -> {
                if (t == 0) w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1);
                if (t >= 10) {
                    double r = t * 0.2;
                    for (int i = 0; i < 24; i++) {
                        double a = i * Math.PI / 12;
                        w.spawnParticle(Particle.CLOUD, c.clone().add(Math.cos(a) * r, 1, Math.sin(a) * r), 1, 0, 0, 0, 0);
                    }
                }
            }
            case "RAIN" -> {
                for (int i = 0; i < 4; i++) {
                    double x = random.nextDouble() * 12 - 6, zz = random.nextDouble() * 12 - 6;
                    w.spawnParticle(Particle.CLOUD, c.clone().add(x, Math.max(0, 8 - t * 0.2), zz), 1, 0, 0, 0, 0);
                }
            }
            case "SOUL" -> {
                for (int i = 0; i < 16; i++) {
                    double a = i * Math.PI / 8 + t * 0.1;
                    w.spawnParticle(Particle.SOUL, c.clone().add(Math.cos(a) * 3, t * 0.05, Math.sin(a) * 3), 1, 0, 0, 0, 0);
                }
            }
            case "CRACK" -> {
                for (int i = 0; i < 5; i++) {
                    double x = random.nextDouble() * 8 - 4, zz = random.nextDouble() * 8 - 4;
                    w.spawnParticle(Particle.CRIT, c.clone().add(x, 0.1, zz), 3, 0, 0, 0, 0);
                }
            }
            case "HELIX" -> {
                double a = t * 0.3;
                for (int k = 0; k < 2; k++) {
                    double aa = a + k * Math.PI;
                    w.spawnParticle(Particle.END_ROD, c.clone().add(Math.cos(aa) * 1.8, t * 0.06, Math.sin(aa) * 1.8), 1, 0, 0, 0, 0);
                }
            }
            case "WAVE" -> {
                double r = t * 0.2;
                for (int i = 0; i < 24; i++) {
                    double a = i * Math.PI / 12;
                    w.spawnParticle(Particle.DRAGON_BREATH, c.clone().add(Math.cos(a) * r, 0.1, Math.sin(a) * r), 1, 0, 0, 0, 0);
                }
            }
            default -> { }
        }
    }

    // ===================== PĘTLA (co 0.5 s) =====================

    private void tick() {
        int view = getConfig().getInt("view-range", 40);
        int cdMax = getConfig().getInt("attack-cooldown-seconds", 6) * 2;
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();

        for (Boss b : bosses.values()) {
            if (b.zombie == null) continue;
            if (!b.zombie.isValid()) {
                clearRuntime(b);
                b.zombie = null;
                continue;
            }
            LivingEntity z = b.zombie;
            Location zl = z.getLocation();
            double max = z.getAttribute(Attribute.MAX_HEALTH).getValue();
            double hp = Math.max(0, z.getHealth());
            List<Player> near = new ArrayList<>(zl.getNearbyPlayers(view));

            updateHolo(b);

            // BossBar: tworzony/usuwany na żywo zależnie od ustawienia
            if (b.bar && b.bossBar == null) b.bossBar = Bukkit.createBossBar(sc(b.name), BarColor.RED, BarStyle.SEGMENTED_10);
            if (!b.bar && b.bossBar != null) { b.bossBar.removeAll(); b.bossBar = null; }
            if (b.bossBar != null) {
                b.bossBar.setTitle(sc(b.name) + " §f" + (int) hp + "/" + (int) max);
                b.bossBar.setProgress(Math.min(1, hp / max));
                for (Player p : near) b.bossBar.addPlayer(p);
                for (Player p : new ArrayList<>(b.bossBar.getPlayers())) {
                    if (!near.contains(p)) b.bossBar.removePlayer(p);
                }
            }

            // Scoreboard: każdy boss ma własny, jeden obiekt na bossa
            if (b.board && b.sb == null) b.sb = makeBoard();
            if (!b.board && b.sb != null) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getScoreboard() == b.sb) p.setScoreboard(main);
                b.sb = null;
            }
            if (b.sb != null) {
                for (String entry : new ArrayList<>(b.sb.getEntries())) b.sb.resetScores(entry);
                Objective o = b.sb.getObjective("boss");
                if (o != null) {
                    o.getScore("§7Atak: §f" + attackName(b.attack)).setScore(3);
                    o.getScore("§cHP: §f" + (int) hp + "/" + (int) max).setScore(2);
                    o.getScore("§eRespawn: §f" + b.respawn + "h").setScore(1);
                }
                for (Player p : near) if (p.getScoreboard() != b.sb) p.setScoreboard(b.sb);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getScoreboard() == b.sb && !near.contains(p)) p.setScoreboard(main);
                }
            }

            // Ataki
            b.cd++;
            if (b.cd >= cdMax) {
                b.cd = 0;
                attack(b, z, near);
            }
        }
    }

    private void updateHolo(Boss b) {
        if (b.zombie == null || b.nameStand == null || b.hpStand == null) return;
        Location zl = b.zombie.getLocation();
        b.nameStand.teleport(zl.clone().add(0, 2.7, 0));
        b.hpStand.teleport(zl.clone().add(0, 2.45, 0));
        b.nameStand.customName(legacy("&6&l" + sc(b.name)));
        b.hpStand.customName(legacy("&c❤ " + (int) Math.max(0, b.zombie.getHealth()) + " / " + (int) b.hp));
        b.hpStand.setCustomNameVisible(b.showHp);
    }

    private ArmorStand stand(Location l) {
        ArmorStand as = (ArmorStand) l.getWorld().spawnEntity(l, EntityType.ARMOR_STAND);
        as.setInvisible(true);
        as.setMarker(true);
        as.setGravity(false);
        as.setCustomNameVisible(true);
        as.setPersistent(false);
        return as;
    }

    private Scoreboard makeBoard() {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("boss", "dummy", legacy("&6" + sc("boss")));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        return sb;
    }

    // ===================== ATAKI (10 mechanik x 10 sił) =====================

    private void attack(Boss b, LivingEntity z, List<Player> near) {
        int pattern = b.attack / 10;
        int v = b.attack % 10;           // 0..9 = poziom siły
        World w = z.getWorld();
        Location zl = z.getLocation();
        List<Player> close = near.stream().filter(p -> p.getLocation().distance(zl) <= 6 + v).toList();

        switch (PATTERNS[pattern]) {
            case "SLAM" -> {
                w.spawnParticle(Particle.EXPLOSION, zl, 1);
                w.playSound(zl, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
                for (Player p : close) {
                    p.damage(3 + v, z);
                    p.setVelocity(new Vector(0, 0.6 + 0.1 * v, 0));
                }
            }
            case "LIGHTNING" -> {
                int n = 1 + v / 3;
                for (int i = 0; i < n && !near.isEmpty(); i++) {
                    Player t = near.get(random.nextInt(near.size()));
                    w.strikeLightningEffect(t.getLocation());
                    t.damage(3 + v, z);
                }
            }
            case "SUMMON" -> {
                int n = 1 + v / 3;
                for (int i = 0; i < n; i++) {
                    w.spawnEntity(zl.clone().add(random.nextDouble() * 4 - 2, 0, random.nextDouble() * 4 - 2),
                            v % 2 == 0 ? EntityType.ZOMBIE : EntityType.SKELETON);
                }
                w.spawnParticle(Particle.SMOKE, zl, 30, 1, 1, 1, 0.05);
            }
            case "FIREBALL" -> {
                if (!near.isEmpty()) {
                    Vector dir = near.get(0).getEyeLocation().toVector()
                            .subtract(z.getEyeLocation().toVector()).normalize();
                    for (int i = 0; i < 1 + v / 4; i++) {
                        w.spawn(z.getEyeLocation(), SmallFireball.class, f -> f.setDirection(dir));
                    }
                }
            }
            case "BLINK" -> {
                if (!near.isEmpty()) {
                    Player t = near.get(random.nextInt(near.size()));
                    Location dest = t.getLocation().clone().add(1.5, 0, 0);
                    w.spawnParticle(Particle.PORTAL, zl, 40, 0.5, 1, 0.5, 0.5);
                    z.teleport(dest);
                    for (Player p : near) if (p.getLocation().distance(dest) <= 3) p.damage(2 + v, z);
                }
            }
            case "POISON" -> {
                for (Player p : close) p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60 + v * 20, v / 4));
                w.spawnParticle(Particle.SMOKE, zl, 40, 3, 1, 3, 0.02);
            }
            case "FREEZE" -> {
                for (Player p : close) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60 + v * 20, 2 + v / 5));
                w.spawnParticle(Particle.CLOUD, zl, 60, 4, 1, 4, 0.05);
            }
            case "LAUNCH" -> {
                for (Player p : close) {
                    p.setVelocity(new Vector(0, 1 + 0.1 * v, 0));
                    p.damage(2 + v / 2, z);
                }
            }
            case "WITHER" -> {
                for (Player p : close) p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40 + v * 10, v / 5));
                w.spawnParticle(Particle.SMOKE, zl, 40, 2, 1, 2, 0.02);
            }
            case "QUAKE" -> {
                double r = 4 + v;
                for (int i = 0; i < 36; i++) {
                    double a = i * Math.PI / 18;
                    w.spawnParticle(Particle.EXPLOSION, zl.clone().add(Math.cos(a) * r, 0.1, Math.sin(a) * r), 1, 0, 0, 0, 0);
                }
                for (Player p : near) if (p.getLocation().distance(zl) <= r && p.isOnGround()) p.damage(2 + v, z);
            }
            default -> { }
        }
    }

    // ===================== ŚMIERĆ + RESPAWN =====================

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        for (Boss b : bosses.values()) {
            if (b.zombie == null || !b.zombie.getUniqueId().equals(e.getEntity().getUniqueId())) continue;
            b.zombie = null;
            clearRuntime(b);
            Bukkit.broadcastMessage(pref() + "§cBoss §e" + sc(b.name) + " §czostał pokonany! Respawn za " + b.respawn + "h.");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (bosses.containsKey(b.id) && b.loc != null) spawnBoss(b, b.loc);
            }, b.respawn * 72000L);
            break;
        }
    }

    // ===================== POMOCNICZE =====================

    private void clearRuntime(Boss b) {
        if (b.nameStand != null) b.nameStand.remove();
        if (b.hpStand != null) b.hpStand.remove();
        b.nameStand = null;
        b.hpStand = null;
        if (b.bossBar != null) { b.bossBar.removeAll(); b.bossBar = null; }
        if (b.sb != null) {
            Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getScoreboard() == b.sb) p.setScoreboard(main);
            b.sb = null;
        }
    }

    private void removeBoss(Boss b) {
        clearRuntime(b);
        if (b.zombie != null) b.zombie.remove();
        b.zombie = null;
        bosses.remove(b.id);
        save();
    }

    private Boss newBoss(String id, String name) {
        Boss b = new Boss(id, name);
        var d = getConfig();
        b.hp = d.getDouble("defaults.hp", 300);
        b.armor = d.getDouble("defaults.armor", 0);
        b.attack = Math.floorMod(d.getInt("defaults.attack", 0), ATTACK_COUNT);
        b.anim = d.getString("defaults.animation", "RING");
        b.bar = d.getBoolean("defaults.bossbar", true);
        b.board = d.getBoolean("defaults.scoreboard", true);
        b.showHp = d.getBoolean("defaults.show-hp", true);
        b.respawn = d.getInt("defaults.respawn-hours", 1);
        return b;
    }

    private Location frontLoc(Player p) {
        Vector dir = p.getLocation().getDirection().setY(0).normalize().multiply(3);
        return p.getLocation().add(dir);
    }

    private String pref() {
        return getConfig().getString("prefix", "&6[WaveBossy] &r").replace('&', '§');
    }

    static String attackName(int idx) {
        return PATTERNS[idx / 10] + " " + (idx % 10 + 1);
    }

    private static Material iconFor(int pattern) {
        return switch (PATTERNS[pattern]) {
            case "LIGHTNING" -> Material.TRIDENT;
            case "SUMMON" -> Material.ZOMBIE_HEAD;
            case "FIREBALL" -> Material.FIRE_CHARGE;
            case "BLINK" -> Material.ENDER_PEARL;
            case "POISON" -> Material.SPIDER_EYE;
            case "FREEZE" -> Material.PACKED_ICE;
            case "LAUNCH" -> Material.FEATHER;
            case "WITHER" -> Material.WITHER_SKELETON_SKULL;
            case "QUAKE" -> Material.BROWN_MUSHROOM;
            default -> Material.MACE;
        };
    }

    static String sc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toLowerCase().toCharArray()) {
            if (ch >= 'a' && ch <= 'z') sb.append(SC[ch - 'a']);
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String next(String[] arr, String cur) {
        int i = Arrays.asList(arr).indexOf(cur);
        return arr[(i + 1) % arr.length];
    }

    private static void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(legacy("&f" + name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            l.add(legacy("&7" + s).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    // ===================== ZAPIS / ODCZYT =====================

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Boss b : bosses.values()) {
            String p = "bosses." + b.id + ".";
            y.set(p + "name", b.name);
            y.set(p + "hp", b.hp);
            y.set(p + "armor", b.armor);
            y.set(p + "attack", b.attack);
            y.set(p + "anim", b.anim);
            y.set(p + "bar", b.bar);
            y.set(p + "board", b.board);
            y.set(p + "show-hp", b.showHp);
            y.set(p + "respawn", b.respawn);
        }
        try {
            y.save(dataFile);
        } catch (Exception ex) {
            getLogger().warning("Nie udało się zapisać bosses.yml: " + ex.getMessage());
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("bosses");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null) continue;
            Boss b = new Boss(id, c.getString("name", id));
            b.hp = c.getDouble("hp", 300);
            b.armor = c.getDouble("armor", 0);
            Object raw = c.get("attack");
            b.attack = raw instanceof Integer i ? Math.floorMod(i, ATTACK_COUNT) : 0;
            String anim = c.getString("anim", "RING");
            b.anim = Arrays.asList(ANIMS).contains(anim) ? anim : "RING";
            b.bar = c.getBoolean("bar", true);
            b.board = c.getBoolean("board", true);
            b.showHp = c.getBoolean("show-hp", true);
            b.respawn = c.getInt("respawn", 1);
            bosses.put(id, b);
        }
    }
}
