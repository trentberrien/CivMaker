package dev.civmaker;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import dev.civmaker.CivStore.Plot;
import dev.civmaker.CivStore.Position;
import dev.civmaker.CivStore.Settlement;
import dev.civmaker.CivStore.Village;
import io.papermc.paper.event.entity.EntityMoveEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class DefenderGolems implements Listener {
    private static final String NAME = "Defender Golem";
    private static final double HEALTH = 100.0;
    private static final int TETHER = SettlementCapture.RADIUS;
    private static final int BOULDER_RANGE = 24;
    private static final int BAR_RANGE = 32;
    private static final int BOULDER_WINDUP = 20;
    private static final int BOULDER_COOLDOWN = 60;
    private static final int BOULDER_LIFETIME = 24;
    private static final double BOULDER_SPEED = 1.45;
    private static final double BOULDER_GRAVITY = 0.03;
    private static final float BOULDER_RADIUS = 0.75f;

    private record Throw(LivingEntity target, Location aim, int releaseTick) {}
    private record Boulder(BlockDisplay display, IronGolem golem, Vector velocity, int expires) {}

    private static final class Defender {
        final Position bell;
        final Location center;
        final Location post;
        final String villageId;
        final BossBar bar;
        final Set<LivingEntity> attackers = new HashSet<>();
        final DefenseStance.RaidAggro raidAggro;
        Throw windup;
        int nextBoulder;
        int nextMelee;

        Defender(IronGolem golem, int[] coordinates, String villageId, DefenseStance.RaidAggro raidAggro) {
            World world = golem.getWorld();
            this.bell = new Position(world.getUID(), coordinates[0], coordinates[1], coordinates[2]);
            this.center = new Location(world, bell.x() + 0.5, bell.y() + 0.5, bell.z() + 0.5);
            this.post = new Location(world, coordinates[3] + 0.5, coordinates[4], coordinates[5] + 0.5);
            this.villageId = villageId;
            this.raidAggro = raidAggro;
            this.bar = Bukkit.createBossBar(NAME, BarColor.RED, BarStyle.SEGMENTED_10);
        }

        boolean inBounds(Location location) {
            return location.getWorld() == center.getWorld() && location.distanceSquared(center) <= TETHER * TETHER;
        }
    }

    private final CivStore store;
    private final PillagerGuards guards;
    private final DefenseStance stance;
    private final NamespacedKey postKey;
    private final NamespacedKey villageKey;
    private final Map<IronGolem, Defender> loaded = new HashMap<>();
    private final List<Boulder> boulders = new ArrayList<>();

    DefenderGolems(CivMaker plugin, CivStore store, PillagerGuards guards) {
        this.store = store;
        this.guards = guards;
        stance = new DefenseStance(plugin, store, false);
        postKey = new NamespacedKey(plugin, "defender_post");
        villageKey = new NamespacedKey(plugin, "defender_village");
    }

    boolean deploy(Player player, Block block) {
        Position bell = Position.at(block);
        Settlement settlement = store.settlementAt(bell.plot());
        Village village = store.claimAt(bell.plot());
        if (block.getType() != Material.BELL || settlement == null || !bell.equals(settlement.bell())
                || village == null || village != store.registeredVillage(player.getUniqueId())) {
            player.sendMessage("Use the Defender Golem kit on your faction's settlement bell.");
            return false;
        }
        UUID assigned = settlement.defense().golem;
        if (assigned != null && (Bukkit.getEntity(assigned) != null || !defenseAreaLoaded(block.getWorld(), bell))) {
            player.sendMessage("This settlement already has a Defender Golem.");
            return false;
        }
        Location post = findPost(block);
        if (post == null) {
            player.sendMessage("Clear a 3-by-3 space with six blocks of headroom and solid ground near the bell.");
            return false;
        }
        IronGolem golem = block.getWorld().spawn(post, IronGolem.class, false, entity -> {
            var data = entity.getPersistentDataContainer();
            data.set(postKey, PersistentDataType.INTEGER_ARRAY, new int[]{
                    bell.x(), bell.y(), bell.z(), post.getBlockX(), post.getBlockY(), post.getBlockZ()});
            data.set(villageKey, PersistentDataType.STRING, village.id.toString());
            entity.getAttribute(Attribute.SCALE).setBaseValue(2.0);
            entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(HEALTH);
            entity.setHealth(HEALTH);
            entity.setPlayerCreated(true);
            entity.setPersistent(true);
            entity.setRemoveWhenFarAway(false);
            entity.setCollidable(false);
            entity.customName(Component.text(NAME));
            entity.setCustomNameVisible(true);
        });
        if (!golem.isValid()) {
            player.sendMessage("The Defender Golem could not spawn here.");
            return false;
        }
        settlement.defense().golem = golem.getUniqueId();
        store.dirty = true;
        track(golem);
        player.sendMessage("The Defender Golem is guarding this settlement bell.");
        return true;
    }

    // An absent UUID is conclusive only when every chunk the defender can occupy has loaded its entities.
    private static boolean defenseAreaLoaded(World world, Position bell) {
        for (int x = (bell.x() - TETHER) >> 4; x <= (bell.x() + TETHER) >> 4; x++) {
            for (int z = (bell.z() - TETHER) >> 4; z <= (bell.z() + TETHER) >> 4; z++) {
                if (!world.isChunkLoaded(x, z) || !world.getChunkAt(x, z).isEntitiesLoaded()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Location findPost(Block bell) {
        Location best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = -2; y <= 2; y++) {
                    int distance = x * x + y * y + z * z;
                    if (distance >= bestDistance) {
                        continue;
                    }
                    Location feet = new Location(bell.getWorld(), bell.getX() + x + 0.5, bell.getY() + y,
                            bell.getZ() + z + 0.5);
                    if (clearPost(feet)) {
                        best = feet;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean clearPost(Location feet) {
        World world = feet.getWorld();
        int x = feet.getBlockX(), y = feet.getBlockY(), z = feet.getBlockZ();
        if (y <= world.getMinHeight() || y + 5 >= world.getMaxHeight()
                || !world.isChunkLoaded((x - 1) >> 4, (z - 1) >> 4)
                || !world.isChunkLoaded((x - 1) >> 4, (z + 1) >> 4)
                || !world.isChunkLoaded((x + 1) >> 4, (z - 1) >> 4)
                || !world.isChunkLoaded((x + 1) >> 4, (z + 1) >> 4)
                || !world.getBlockAt(x, y - 1, z).getBlockData().isFaceSturdy(BlockFace.UP, BlockSupport.FULL)) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy < 6; dy++) {
                    if (!world.getBlockAt(x + dx, y + dy, z + dz).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return world.getNearbyEntities(new BoundingBox(x - 1, y, z - 1, x + 2, y + 6, z + 2),
                entity -> entity instanceof LivingEntity).isEmpty();
    }

    void track(IronGolem golem) {
        var data = golem.getPersistentDataContainer();
        int[] coordinates = data.get(postKey, PersistentDataType.INTEGER_ARRAY);
        String villageId = data.get(villageKey, PersistentDataType.STRING);
        if (coordinates != null && coordinates.length == 6 && villageId != null && !loaded.containsKey(golem)) {
            Bukkit.getMobGoals().removeAllGoals(golem);
            loaded.put(golem, new Defender(golem, coordinates, villageId, stance.raidAggro(golem)));
        }
    }

    @EventHandler
    void added(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof IronGolem golem) {
            track(golem);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void toggle(PlayerInteractEntityEvent event) {
        if (!DefenseStance.wantsToggle(event) || !(event.getRightClicked() instanceof IronGolem golem)) {
            return;
        }
        Defender defender = loaded.get(golem);
        Village village = defender == null ? null : owner(defender);
        if (village != null && store.registeredVillage(event.getPlayer().getUniqueId()) == village) {
            stance.toggle(event, golem, NAME, store.settlementAt(defender.bell.plot()));
            alwaysHostile(golem, defender);
        }
    }

    @EventHandler
    void removed(EntityRemoveEvent event) {
        Defender defender = loaded.remove(event.getEntity());
        if (defender == null) {
            return;
        }
        defender.bar.removeAll();
        if (event.getCause() != EntityRemoveEvent.Cause.UNLOAD) {
            Settlement settlement = store.settlementAt(defender.bell.plot());
            if (settlement != null && event.getEntity().getUniqueId().equals(settlement.defense().golem)) {
                settlement.defense().golem = null;
                store.dirty = true;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void died(EntityDeathEvent event) {
        Defender defender = loaded.get(event.getEntity());
        if (defender != null) {
            defender.bar.removeAll();
        }
    }

    @EventHandler(ignoreCancelled = true)
    void move(EntityMoveEvent event) {
        Defender defender = loaded.get(event.getEntity());
        if (defender != null && event.hasChangedPosition() && !defender.inBounds(event.getTo())
                && event.getTo().distanceSquared(defender.center) >= event.getFrom().distanceSquared(defender.center)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    void teleport(EntityTeleportEvent event) {
        Defender defender = loaded.get(event.getEntity());
        if (defender != null && (event.getTo() == null || !defender.inBounds(event.getTo()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    void portal(EntityPortalEvent event) {
        if (loaded.containsKey(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    void mount(EntityMountEvent event) {
        if (loaded.containsKey(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    void refresh() {
        for (IronGolem golem : List.copyOf(loaded.keySet())) {
            if (!golem.isValid() || golem.isDead()) {
                continue;
            }
            Defender defender = loaded.get(golem);
            Settlement settlement = store.settlementAt(defender.bell.plot());
            Village village = owner(defender);
            UUID assigned = settlement == null ? null : settlement.defense().golem;
            if (village == null || (assigned != null && !assigned.equals(golem.getUniqueId()))) {
                golem.remove();
                continue;
            }
            if (assigned == null) {
                settlement.defense().golem = golem.getUniqueId();
                store.dirty = true;
            }
            updateBar(golem, defender);
            if (golem.isTicking()) {
                fight(golem, defender, village);
            }
        }
    }

    boolean contests(Settlement settlement, Village village) {
        UUID assigned = settlement.defense().golem;
        if (assigned == null || !(Bukkit.getEntity(assigned) instanceof IronGolem golem)
                || golem.isDead() || !golem.isValid() || golem.getHealth() <= 0) {
            return false;
        }
        Defender defender = loaded.get(golem);
        return defender != null && defender.bell.equals(settlement.bell())
                && defender.villageId.equals(village.id.toString()) && defender.inBounds(golem.getLocation());
    }

    private static void updateBar(IronGolem golem, Defender defender) {
        Location location = golem.getLocation();
        var viewers = new HashSet<>(golem.getWorld().getNearbyPlayers(location, BAR_RANGE,
                player -> !player.isDead() && player.getLocation().distanceSquared(location) <= BAR_RANGE * BAR_RANGE));
        for (Player previous : defender.bar.getPlayers()) {
            if (!viewers.remove(previous)) {
                defender.bar.removePlayer(previous);
            }
        }
        defender.bar.setProgress(Math.clamp(golem.getHealth() / golem.getAttribute(Attribute.MAX_HEALTH).getValue(), 0, 1));
        viewers.forEach(defender.bar::addPlayer);
    }

    private void fight(IronGolem golem, Defender defender, Village village) {
        int tick = Bukkit.getCurrentTick();
        boolean alwaysHostile = alwaysHostile(golem, defender);
        var window = store.activeWindow(System.currentTimeMillis());
        double encounterRange = TETHER + BOULDER_RANGE;
        defender.attackers.removeIf(attacker -> !canFight(attacker, defender, village)
                || attacker.getLocation().distanceSquared(defender.center) > encounterRange * encounterRange);
        if (defender.windup != null) {
            Throw windup = defender.windup;
            if (!isHostile(windup.target(), defender, village, alwaysHostile, window)
                    || canSwingAt(golem, windup.target())) {
                defender.windup = null;
            } else {
                golem.lookAt(windup.aim());
                if (tick >= windup.releaseTick()) {
                    defender.windup = null;
                    defender.nextBoulder = tick + BOULDER_COOLDOWN;
                    throwBoulder(golem, windup.aim());
                }
                return;
            }
        }

        Location location = golem.getLocation();
        LivingEntity target = null;
        double nearest = BOULDER_RANGE * BOULDER_RANGE;
        var candidates = alwaysHostile || (window != null && defender.raidAggro.active(window.start()))
                ? golem.getWorld().getNearbyLivingEntities(location, BOULDER_RANGE) : defender.attackers;
        for (LivingEntity candidate : candidates) {
            double distance = candidate.getLocation().distanceSquared(location);
            if (distance <= nearest && isHostile(candidate, defender, village, alwaysHostile, window)
                    && (defender.inBounds(candidate.getLocation()) || golem.hasLineOfSight(candidate))) {
                target = candidate;
                nearest = distance;
            }
        }
        var path = golem.getPathfinder();
        if (target == null) {
            if (location.distanceSquared(defender.post) > 1) {
                path.moveTo(defender.post);
            } else {
                path.stopPathfinding();
            }
            return;
        }

        boolean inside = defender.inBounds(target.getLocation());
        boolean visible = golem.hasLineOfSight(target);
        golem.lookAt(target);
        if (canSwingAt(golem, target)) {
            path.stopPathfinding();
            if (tick >= defender.nextMelee) {
                defender.nextMelee = tick + 20;
                golem.attack(target);
            }
            return;
        }

        boolean canThrow = visible && tick >= defender.nextBoulder;
        // Use a boulder if the giant cannot walk up to a visible target on uneven terrain.
        var approach = inside && (!canThrow || nearest <= 8 * 8) ? path.findPath(target) : null;
        if (canThrow && (approach == null || !approach.canReachFinalPoint())) {
            path.stopPathfinding();
            defender.windup = new Throw(target, target.getBoundingBox().getCenter().toLocation(golem.getWorld()),
                    tick + BOULDER_WINDUP);
            golem.getWorld().playSound(location, Sound.BLOCK_STONE_BREAK, 2, 0.55f);
            golem.getWorld().spawnParticle(Particle.BLOCK, golem.getEyeLocation(), 16, 0.45, 0.35, 0.45,
                    Material.COBBLESTONE.createBlockData());
        } else if (approach != null) {
            path.moveTo(approach);
        } else {
            path.stopPathfinding();
        }
    }

    private static boolean canSwingAt(IronGolem golem, LivingEntity target) {
        if (!golem.getBoundingBox().expand(1.25).overlaps(target.getBoundingBox())) {
            return false;
        }
        Location arms = golem.getLocation().add(0, golem.getHeight() * 0.5, 0);
        Vector reach = target.getBoundingBox().getCenter().subtract(arms.toVector());
        return reach.lengthSquared() < 0.01 || golem.getWorld().rayTraceBlocks(arms, reach, reach.length(),
                FluidCollisionMode.NEVER, true) == null;
    }

    private void throwBoulder(IronGolem golem, Location aim) {
        Location launch = golem.getEyeLocation();
        if (aim.getWorld() != launch.getWorld() || aim.distanceSquared(launch) < 0.01) {
            return;
        }
        Vector displacement = aim.toVector().subtract(launch.toVector());
        double flightTicks = displacement.length() / BOULDER_SPEED;
        Vector velocity = displacement.multiply(1 / flightTicks);
        velocity.setY(velocity.getY() + BOULDER_GRAVITY * (flightTicks - 1) / 2);
        BlockDisplay display = golem.getWorld().spawn(launch, BlockDisplay.class, rock -> {
            rock.setBlock(Material.COBBLESTONE.createBlockData());
            rock.setPersistent(false);
            rock.setDisplayWidth(2 * BOULDER_RADIUS);
            rock.setDisplayHeight(2 * BOULDER_RADIUS);
            rock.setTeleportDuration(1);
            rock.setTransformationMatrix(new Matrix4f().translate(-BOULDER_RADIUS, -BOULDER_RADIUS, -BOULDER_RADIUS)
                    .scale(2 * BOULDER_RADIUS));
        });
        if (display.isValid()) {
            boulders.add(new Boulder(display, golem, velocity, Bukkit.getCurrentTick() + BOULDER_LIFETIME));
        }
        golem.getWorld().playSound(launch, Sound.ENTITY_IRON_GOLEM_ATTACK, 2, 0.65f);
    }

    void tickBoulders() {
        var iterator = boulders.iterator();
        while (iterator.hasNext()) {
            Boulder boulder = iterator.next();
            if (boulder.display().isValid() && !boulder.golem().isDead() && boulder.golem().isValid()
                    && Bukkit.getCurrentTick() < boulder.expires() && advance(boulder)) {
                continue;
            }
            boulder.display().remove();
            iterator.remove();
        }
    }

    private boolean advance(Boulder boulder) {
        Location start = boulder.display().getLocation();
        Vector velocity = boulder.velocity();
        Location end = start.clone().add(velocity);
        double distance = velocity.length();
        if (distance < 1.0e-6) {
            velocity.setY(velocity.getY() - BOULDER_GRAVITY);
            return true;
        }
        double wall = wallDistance(start, end, velocity, distance);
        var hit = wall == 0 ? null : start.getWorld().rayTraceEntities(start, velocity, Math.min(wall, distance),
                BOULDER_RADIUS, entity -> entity != boulder.golem() && entity instanceof LivingEntity living
                        && !living.isDead() && (!(living instanceof Player player) || player.getGameMode() != GameMode.SPECTATOR));
        if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
            Location impact = hit.getHitPosition().toLocation(start.getWorld());
            float attack = (float) boulder.golem().getAttribute(Attribute.ATTACK_DAMAGE).getValue();
            // Vanilla iron golems add a random integer below attack damage to half the attribute value.
            int spread = (int) attack;
            float damage = spread > 0 ? attack / 2 + ThreadLocalRandom.current().nextInt(spread) : attack;
            // Vanilla shields check the incoming direction, even when the boulder grazes the target.
            Location incoming = target.getLocation().subtract(velocity);
            target.damage(damage, DamageSource.builder(DamageType.MOB_PROJECTILE)
                    .withDirectEntity(boulder.display()).withCausingEntity(boulder.golem()).withDamageLocation(incoming).build());
            boulderImpact(impact);
            return false;
        }
        if (wall <= distance) {
            boulderImpact(start.add(velocity.clone().multiply(wall / distance)));
            return false;
        }
        velocity.setY(velocity.getY() - BOULDER_GRAVITY);
        return boulder.display().teleport(end);
    }

    private static double wallDistance(Location start, Location end, Vector velocity, double distance) {
        World world = start.getWorld();
        Vector origin = start.toVector();
        BoundingBox sweep = BoundingBox.of(start, end).expand(BOULDER_RADIUS);
        double nearest = Double.POSITIVE_INFINITY;
        for (int x = (int) Math.floor(sweep.getMinX()); x <= Math.floor(sweep.getMaxX()); x++) {
            for (int z = (int) Math.floor(sweep.getMinZ()); z <= Math.floor(sweep.getMaxZ()); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return 0;
                }
                // Fences and walls can extend above their block into the swept volume.
                for (int y = Math.max(world.getMinHeight(), (int) Math.floor(sweep.getMinY()) - 1);
                     y <= Math.min(world.getMaxHeight() - 1, Math.floor(sweep.getMaxY())); y++) {
                    for (BoundingBox shape : world.getBlockAt(x, y, z).getCollisionShape().getBoundingBoxes()) {
                        shape.shift(x, y, z).expand(BOULDER_RADIUS);
                        if (shape.contains(origin)) {
                            return 0;
                        }
                        var hit = shape.rayTrace(origin, velocity, distance);
                        if (hit != null) {
                            nearest = Math.min(nearest, hit.getHitPosition().distance(origin));
                        }
                    }
                }
            }
        }
        return nearest;
    }

    private static void boulderImpact(Location impact) {
        impact.getWorld().spawnParticle(Particle.BLOCK, impact, 24, 0.3, 0.3, 0.3,
                Material.COBBLESTONE.createBlockData());
        impact.getWorld().playSound(impact, Sound.BLOCK_STONE_BREAK, 1.5f, 0.65f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void potionSplash(PotionSplashEvent event) {
        for (LivingEntity target : event.getAffectedEntities()) {
            if (loaded.containsKey(target)) {
                event.setIntensity(target, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void potionCloud(AreaEffectCloudApplyEvent event) {
        event.getAffectedEntities().removeIf(loaded::containsKey);
    }

    boolean canDamageWithGrenade(Entity target, Player attacker) {
        Defender defender = loaded.get(target);
        return defender != null && canFight(attacker, defender, owner(defender));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    void damage(EntityDamageEvent event) {
        Defender victim = loaded.get(event.getEntity());
        Entity direct = event.getDamageSource().getDirectEntity();
        if (victim != null && (direct instanceof Arrow || direct instanceof SpectralArrow)) {
            event.setCancelled(true);
            return;
        }
        Entity source = event.getDamageSource().getCausingEntity();
        if (source instanceof IronGolem golem) {
            Defender attacker = loaded.get(golem);
            if (attacker != null && (!(event.getEntity() instanceof LivingEntity target)
                    || !isHostile(target, attacker, owner(attacker), alwaysHostile(golem, attacker),
                    store.activeWindow(System.currentTimeMillis())))) {
                event.setCancelled(true);
            }
        }
        if (victim != null && source instanceof LivingEntity living && responsiblePlayer(living) != null
                && !canFight(living, victim, owner(victim))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void provoked(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof IronGolem golem)) {
            return;
        }
        Defender defender = loaded.get(golem);
        if (defender != null && event.getFinalDamage() > 0
                && !alwaysHostile(golem, defender)
                && event.getDamageSource().getCausingEntity() instanceof LivingEntity attacker
                && !attacker.equals(event.getEntity()) && canFight(attacker, defender, owner(defender))) {
            if (!(attacker instanceof Player)) {
                defender.attackers.add(attacker);
            }
            Player player = responsiblePlayer(attacker);
            var window = store.activeWindow(System.currentTimeMillis());
            if (player != null && window != null) {
                defender.raidAggro.provoke(window.start(), player, store.memberOf(player.getUniqueId()));
            }
        }
    }

    private boolean alwaysHostile(IronGolem golem, Defender defender) {
        Settlement settlement = store.settlementAt(defender.bell.plot());
        if (defender.raidAggro.sync(settlement)) {
            defender.attackers.clear();
            defender.windup = null;
        }
        return stance.alwaysHostile(golem, settlement);
    }

    private boolean isHostile(LivingEntity target, Defender defender, Village village, boolean alwaysHostile,
                              CivStore.Window window) {
        boolean hostile;
        if (alwaysHostile) {
            hostile = target instanceof Player || target instanceof Enemy;
        } else if (target instanceof Player player) {
            hostile = window != null && defender.raidAggro.includes(window.start(), player, store.memberOf(player.getUniqueId()));
        } else {
            hostile = defender.attackers.contains(target);
        }
        return hostile && canFight(target, defender, village);
    }

    private Village owner(Defender defender) {
        Village village = store.claimAt(defender.bell.plot());
        Settlement settlement = store.settlementAt(defender.bell.plot());
        return village != null && defender.villageId.equals(village.id.toString())
                && settlement != null && defender.bell.equals(settlement.bell()) ? village : null;
    }

    private boolean canFight(LivingEntity entity, Defender defender, Village village) {
        if (village == null || !entity.isValid() || entity.isDead() || entity.getWorld() != defender.center.getWorld()) {
            return false;
        }
        if (entity instanceof Pillager guard) {
            Village guardOwner = guards.owner(guard);
            if (guardOwner != null) {
                return guardOwner != village && raidAllowsCombat(defender, entity);
            }
        }
        Player player = responsiblePlayer(entity);
        return player == null || ((player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && store.memberOf(player.getUniqueId()) != village
                && raidAllowsCombat(defender, entity));
    }

    private boolean raidAllowsCombat(Defender defender, LivingEntity entity) {
        return store.activeWindow(System.currentTimeMillis()) != null
                && store.raidCovers(defender.bell.plot()) && store.raidCovers(Plot.at(entity.getLocation()));
    }

    private static Player responsiblePlayer(LivingEntity entity) {
        return entity instanceof Player player ? player
                : entity instanceof Tameable pet && pet.getOwner() instanceof Player owner ? owner : null;
    }

    void clear() {
        boulders.forEach(boulder -> boulder.display().remove());
        boulders.clear();
        loaded.values().forEach(defender -> defender.bar.removeAll());
        loaded.clear();
    }
}
