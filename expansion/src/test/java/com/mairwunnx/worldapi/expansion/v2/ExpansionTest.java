package com.mairwunnx.worldapi.expansion.v2;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExpansionTest {
    record BossKilled(PlayerRef killer, String type, int level, Place place) {}

    record Other(int n) {}

    enum Sort { KILLS, LEVEL }

    static final class Bosses extends Expansion {
        final Param<Integer> limit = integer("limit", "How many hunters", 1, 50, 10);
        final Param<Sort> sort = choice("sort", "How to sort", Sort.class, Sort.KILLS);
        final Param<PlayerRef> player = player("player", "A player’s name or UUID");
        final Param<Boolean> all = flag("all", "Every hunter");
        final Resource hunters = resource("/hunters/", "The best hunters", req -> List.of()).query(limit, sort, all).returnsListOf(BossKilled.class).publicly();
        final Resource card = resource("players/{player}", "A card", req -> req.get(player)).onServerThread();
        final Event<BossKilled> killed = event("boss.killed", BossKilled.class, "Players killed a boss").publicly().recorded();
        final Event<Other> spawned = event("boss.spawned", Other.class, "A boss appeared");

        Bosses() {
            super("mobosses", "Mo’Bosses", "Bosses");
        }
    }

    @AfterEach
    void unbind() {
        Registry.bind(null);
        Registry.registered().forEach(it -> Registry.unregister(it.id()));
    }

    @Test
    void fieldsDeclareWhatAnExpansionShares() {
        Bosses bosses = new Bosses();
        assertEquals("mobosses", bosses.id());
        assertEquals("Mo’Bosses", bosses.name());
        assertEquals(List.of(bosses.hunters, bosses.card), bosses.resources());
        assertEquals("hunters", bosses.hunters.path());
        assertEquals(List.of(bosses.limit, bosses.sort, bosses.all), bosses.hunters.params());
        assertEquals(List.of(bosses.player), bosses.card.params());
        assertTrue(bosses.hunters.isPublic());
        assertTrue(bosses.hunters.returnsList());
        assertTrue(bosses.card.serverThread());
        assertFalse(bosses.card.isPublic());
        assertEquals(List.of(bosses.killed, bosses.spawned), bosses.events());
        assertTrue(bosses.killed.isPublic() && bosses.killed.isRecorded());
        assertFalse(bosses.spawned.isPublic() || bosses.spawned.isRecorded());
        assertEquals("plain", new Expansion("plain") {}.name());
    }

    @Test
    void parametersReadWhatClientsSend() {
        Bosses bosses = new Bosses();
        assertEquals(10, bosses.limit.parse(null));
        assertEquals(50, bosses.limit.parse("900"));
        assertEquals(1, bosses.limit.parse("-3"));
        assertEquals("limit must be a whole number", assertThrows(IllegalArgumentException.class, () -> bosses.limit.parse("five")).getMessage());
        assertEquals(Sort.LEVEL, bosses.sort.parse("Level"));
        assertEquals(Sort.KILLS, bosses.sort.parse(""));
        assertEquals(List.of("kills", "level"), bosses.sort.choices());
        assertThrows(IllegalArgumentException.class, () -> bosses.sort.parse("deaths"));
        assertTrue(bosses.all.parse(""));
        assertFalse(bosses.all.parse(null));
        assertFalse(bosses.all.parse("no"));
        assertThrows(IllegalArgumentException.class, () -> bosses.all.parse("maybe"));
        assertThrows(UnsupportedOperationException.class, () -> bosses.player.parse("Steve"));
        assertNull(bosses.player.parse(null));
    }

    @Test
    void brokenDeclarationsFailWhileTheExpansionIsBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new Expansion("Not An Id") {});
        assertEquals("Declare the parameter player before players/{player}", assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Resource card = resource("players/{player}", "", req -> null);
        }).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Event<Other> a = event("twice", Other.class, "");
            final Event<Other> b = event("twice", Other.class, "");
        });
        assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Param<String> a = text("q", "");
            final Param<String> b = text("q", "");
        });
        assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Param<Integer> limit = integer("limit", "", 1, 50, 100);
        });
        Bosses other = new Bosses();
        assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Resource a = resource("a", "", req -> null).query(other.limit);
        });
        assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Event<Other> a = event("Bad Name", Other.class, "");
        });
    }

    @Test
    void actionsDeclareTheirBodyAndPermission() {
        Expansion afks = new Expansion("afks") {
            final Param<PlayerRef> player = player("player", "A player");
            final Param<String> reason = text("reason", "Why").required();
            final Param<Place> at = place("at", "Where");
            final Action enter = action("players/{player}/afk", "Puts a player into AFK", req -> null).body(reason, at).permission(Permission.PLAYERS_MODERATE).onServerThread();
            final Action leave = remove("players/{player}/afk", "Takes a player out of AFK", req -> null).permission(Permission.PLAYERS_MODERATE);
        };
        List<Action> actions = afks.actions();
        assertEquals(List.of(Action.Method.POST, Action.Method.DELETE), actions.stream().map(Action::method).toList());
        assertEquals(List.of("player", "reason", "at"), actions.getFirst().params().stream().map(Param::name).toList());
        assertEquals(Permission.PLAYERS_MODERATE, actions.get(1).permission());
        assertTrue(actions.getFirst().serverThread());
        assertEquals("reason is required", assertThrows(IllegalArgumentException.class, () -> afks.params().get(1).parse(null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Param<String> q = text("q", "");
            final Action a = remove("a", "", req -> null).body(q);
        });
        assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Param<Place> at = place("at", "");
            final Action a = action("a", "", req -> null).query(at);
        });
        assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Action a = action("a", "", req -> null);
            final Action b = action("a", "", req -> null);
        });
    }

    @Test
    void aResourceOrActionSetsItsOwnRequiredFallbackAndRange() {
        Bosses bosses = new Bosses();
        Expansion local = new Expansion("local") {
            final Param<Integer> limit = integer("limit", "How many", 1, 500, 10);
            final Param<String> type = text("type", "A boss type");
            final Resource board = resource("board", "", req -> null).query(limit).orElse(limit, 3).between(limit, 1, 50);
            final Action spawn = action("spawn", "", req -> null).body(type).required(type).permission(Permission.WORLD_WRITE);
        };
        Resource board = local.resources().getFirst();
        Param<?> limit = board.params().getFirst();
        assertEquals(3, board.parse(limit, null));
        assertEquals(50, board.parse(limit, "900"));
        assertEquals(500, limit.parse("900"), "the declaration keeps its own range");
        assertEquals(List.of(1L, 50L), List.of(board.minOf(limit), board.maxOf(limit)));
        Action spawn = local.actions().getFirst();
        assertTrue(spawn.requires(spawn.params().getFirst()));
        assertEquals("type is required", assertThrows(IllegalArgumentException.class, () -> spawn.parse(spawn.params().getFirst(), null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Param<String> q = text("q", "");
            final Resource r = resource("r", "", req -> null).required(q);
        });
        assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
            final Param<Integer> q = integer("q", "", 1, 5, 1);
            final Resource r = resource("r", "", req -> null).query(q).between(q, 5, 1);
        });
        assertNotNull(bosses.hunters);
    }

    @Test
    void aRegisteredExpansionDoesntChange() {
        Bosses bosses = new Bosses();
        bosses.register();
        assertThrows(IllegalStateException.class, () -> bosses.spawned.publicly());
        assertThrows(IllegalStateException.class, () -> bosses.hunters.onServerThread());
    }

    @Test
    void eventsReachTheHostOnlyWhileItRunsAndTheExpansionIsRegistered() {
        List<String> seen = new ArrayList<>();
        Bosses bosses = new Bosses();
        BossKilled data = new BossKilled(new PlayerRef(UUID.randomUUID(), "Steve"), "zombie", 40, new Place("world", 1, 64, 2));
        assertFalse(bosses.killed.publish(data), "no WorldAPI yet");
        bosses.register();
        assertFalse(Expansion.available());
        Registry.bind(new Registry.Host() {
            @Override
            public void register(Expansion expansion) {
                seen.add("register " + expansion.id());
            }

            @Override
            public void unregister(String id) {
                seen.add("unregister " + id);
            }

            @Override
            public void publish(Event<?> event, Record payload) {
                seen.add(event.name() + " " + ((BossKilled) payload).level());
            }
        });
        assertTrue(Expansion.available());
        assertTrue(bosses.killed.publish(data));
        // Another instance with the same id, as META-INF/services makes one, publishes the same way.
        assertTrue(new Bosses().killed.publish(data));
        assertThrows(IllegalArgumentException.class, () -> bosses.killed.publish(null));
        bosses.unregister();
        assertFalse(bosses.killed.publish(data));
        assertEquals(List.of("register mobosses", "boss.killed 40", "boss.killed 40", "unregister mobosses"), seen);
    }

    @Test
    void playersAndPlacesNeedTheirKeys() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerRef(null, "Steve"));
        assertThrows(IllegalArgumentException.class, () -> new Place(null, 0, 0, 0));
        assertEquals(Permission.PLAYERS_PRIVATE, Permission.of("players:private"));
    }
}
