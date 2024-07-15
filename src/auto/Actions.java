package auto;

import haven.*;
import me.ender.ClientUtils;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static auto.InvHelper.*;

public class Actions {
    public static void fuelGob(GameUI gui, String name, String fuel, int count) {
	List<ITarget> targets = GobHelper.getNearest(gui, name, 1, 33);
	
	if(!targets.isEmpty()) {
	    Bot.process(targets).actions(fuelWith(gui, fuel, count)).start(gui.ui);
	} else {
	    gui.error("Cannot find target to add fuel to");
	}
    }
    
    public static void pickup(GameUI gui, String filter) {
	pickup(gui, filter, Integer.MAX_VALUE);
    }
    
    static void pickup(GameUI gui, String filter, int limit) {
	pickup(gui, GobHelper.resIdStartsWith(filter), limit);
    }
    
    static void pickup(GameUI gui, Predicate<Gob> filter) {
	pickup(gui, filter, Integer.MAX_VALUE);
    }
    
    static void pickup(GameUI gui, Predicate<Gob> filter, int limit) {
	List<ITarget> targets = gui.ui.sess.glob.oc.stream()
	    .filter(filter)
	    .filter(gob -> PositionHelper.distanceToPlayer(gob) <= CFG.AUTO_PICK_RADIUS.get())
	    .filter(BotUtil::isOnRadar)
	    .sorted(PositionHelper.byDistanceToPlayer)
	    .limit(limit)
	    .map(GobTarget::new)
	    .collect(Collectors.toList());
	
	Bot.process(targets).actions(
	    ITarget::rclick_shift,
	    (target, bot) -> Targets.gob(target).waitRemoval()
	).start(gui.ui);
    }
    
    public static void pickup(GameUI gui) {
	pickup(gui, GobHelper.gobIs(GobTag.PICKUP));
    }
    
    public static void openGate(GameUI gui) {
	List<ITarget> targets = gui.ui.sess.glob.oc.stream()
	    .filter(GobHelper.gobIs(GobTag.GATE))
	    .filter(gob -> !gob.isVisitorGate())
	    .filter(gob -> PositionHelper.distanceToPlayer(gob) <= 35)
	    .sorted(PositionHelper.byDistanceToPlayer)
	    .limit(1)
	    .map(GobTarget::new)
	    .collect(Collectors.toList());
	
	Bot.process(targets).actions(ITarget::rclick).start(gui.ui, true);
    }
    
    public static void refillDrinks(GameUI gui) {
	if(gui.hand() != null || gui.cursor != null) {
	    gui.error("You must have empty cursor to refill drinks!");
	    return;
	}
	
	Coord2d waterTile = null;
	Gob barrel = null;
	boolean needWalk = false;
	Gob player = gui.map.player();
	Bot.BotAction interact;
	
	if(MapHelper.isPlayerOnFreshWaterTile(gui)) {
	    waterTile = player.rc;
	} else {
	    needWalk = true;
	    List<ITarget> objs = GobHelper.getNearest(gui, 1, 32, GobTag.HAS_WATER);
	    if(!objs.isEmpty()) {
		barrel = Targets.gob(objs.get(0));
	    }
	    if(barrel == null) {
		waterTile = MapHelper.nearbyWaterTile(gui);
	    }
	}
	
	final Coord2d tile = barrel != null ? barrel.rc : waterTile;
	
	if(waterTile != null) {
	    interact = (t, b) -> gui.map.wdgmsg("itemact", Coord.z, tile.floor(OCache.posres), 0);
	} else if(barrel != null) {
	    final Gob gob = barrel;
	    interact = (t, b) -> gob.itemact(UI.MOD_META);
	} else {
	    gui.error("You must be near tile or barrel with fresh water to refill drinks!");
	    return;
	}
	
	List<ITarget> targets = Stream.of(INVENTORY_CONTAINED(gui), BELT_CONTAINED(gui))
	    .flatMap(x -> x.get().stream())
	    .filter(InvHelper::isDrinkContainer)
	    .filter(InvHelper::isNotFull)
	    .map(ContainedTarget::new)
	    .collect(Collectors.toList());
	
	if(targets.isEmpty()) {
	    gui.error("No non-full drink containers to refill!");
	    return;
	}
	
	Bot refillBot = Bot.process(targets).actions(
	    ITarget::take,
	    BotUtil.WaitHeldChanged,
	    interact,
	    BotUtil.doWait(70),
	    ITarget::putBack,
	    BotUtil.WaitHeldChanged
	);
	if(needWalk) {
	    refillBot.setup(
		(t, b) -> gui.map.click(tile, 1, Coord.z, tile.floor(OCache.posres), 1, 0),
		GobHelper.waitGobPose(player, 1500, "/walking", "/running"),
		GobHelper.waitGobNoPose(player, 1500, "/walking", "/running")
	    );
	}
	refillBot.start(gui.ui, true);
    }
    
    public static void selectFlower(GameUI gui, long gobid, String option) {
	List<ITarget> targets = gui.ui.sess.glob.oc.stream()
	    .filter(gob -> gob.id == gobid)
	    .map(GobTarget::new)
	    .collect(Collectors.toList());
	
	selectFlower(gui, option, targets);
    }
    
    public static void selectFlowerOnItems(GameUI gui, String option, List<WItem> items) {
	List<ITarget> targets = items.stream()
	    .map(ItemTarget::new)
	    .collect(Collectors.toList());
	
	selectFlower(gui, option, targets);
    }
    
    public static void selectFlower(GameUI gui, String option, List<ITarget> targets) {
	Bot.process(targets)
	    .actions(ITarget::rclick, BotUtil.selectFlower(option))
	    .start(gui.ui);
    }
    
    public static void drink(GameUI gui) {
	Collection<Supplier<List<WItem>>> everywhere = Arrays.asList(HANDS(gui), INVENTORY(gui), BELT(gui));
	ClientUtils.chainOptionals(
	    () -> findFirstThatContains("Tea", everywhere),
	    () -> findFirstThatContains("Water", everywhere)
	).ifPresent(Actions::drink);
    }
    
    public static void drink(WItem item) {
	Bot.process(Targets.of(item))
	    .actions(ITarget::rclick, BotUtil.selectFlower("Drink"))
	    .start(item.ui, true);
    }
    
    public static void aggroOne(GameUI gui) {aggro(gui, 1, false);}
    
    public static void aggroAll(GameUI gui) {aggro(gui, Integer.MAX_VALUE, true);}
    
    public static void aggro(GameUI gui, int limit, boolean nearPlayer) {
	if(nearPlayer) {
	    aggro(gui, GobHelper.getNearest(gui, limit, 165, GobHelper.gobIsAny(GobTag.AGGRO_TARGET), GobHelper::isNotFriendlySteed));
	} else {
	    PositionHelper.mapPosOfMouse(gui)
		.thenAccept(mc -> aggro(gui, GobHelper.getNearestToPoint(gui, limit, mc, 33, 
		    GobHelper.gobIsAny(GobTag.AGGRO_TARGET, GobTag.IN_COMBAT), GobHelper::isNotFriendlySteed)));
	}
    }
    
    public static void aggro(GameUI gui, List<ITarget> targets) {
	if(targets.isEmpty()) {
	    gui.error("No targets to aggro");
	    return;
	}
	Bot.process(targets)
	    .setup((t, b) -> gui.menu.paginafor("paginae/act/atk").button().use())
	    .actions(
		(target, bot) -> target.click(1, 0),
		BotUtil.doWait(65)//TODO: wait for relations change?
	    )
	    .cleanup((t, b) -> BotUtil.rclick(gui), ((t, b) -> gui.pathQueue.clear()))
	    .highlight(false)
	    .start(gui.ui, true);
    }
    
    private static Bot.BotAction fuelWith(GameUI gui, String fuel, int count) {
	return (target, bot) -> {
	    Supplier<List<WItem>> inventory = unstacked(INVENTORY(gui));
	    float has = countItems(fuel, inventory);
	    if(has < count) {
		bot.cancel(String.format("Not enough '%s' in inventory: found %d, need: %d", fuel, (int) has, count));
		return;
	    }
	    for (int i = 0; i < count; i++) {
		Optional<WItem> w = findFirstItem(fuel, inventory);
		if(!w.isPresent()) {
		    bot.cancel("no fuel in inventory");
		    return;
		}
		w.get().take();
		if(!BotUtil.waitHeld(gui, fuel)) {
		    bot.cancel("no fuel on cursor");
		    return;
		}
		target.interact();
		if(!BotUtil.waitHeld(gui, null)) {
		    bot.cancel("cursor is not empty");
		    return;
		}
	    }
	};
    }
}
