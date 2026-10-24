package com.mairwunnx.worldapi.expansion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApiTest {
    @AfterEach
    void unbind() {
        WorldApiExpansions.bind(null);
        WorldApiExpansions.registered().forEach(it -> WorldApiExpansions.unregister(it.id()));
    }

    @Test
    void versionsMatchByMajorAndMinor() {
        assertTrue(ExpansionApi.satisfies("1.0.0", "1.0.0"));
        assertTrue(ExpansionApi.satisfies("1.3.2", "1.1.0"));
        assertTrue(ExpansionApi.satisfies("1.0.5", "1.0.9"));
        assertTrue(ExpansionApi.satisfies("1.2", "1.1.0"));
        assertFalse(ExpansionApi.satisfies("1.0.0", "1.1.0"));
        assertFalse(ExpansionApi.satisfies("2.0.0", "1.0.0"));
        assertFalse(ExpansionApi.satisfies("1.0.0", "garbage"));
        assertFalse(ExpansionApi.satisfies("1.0.0", "1.-1.0"));
        assertFalse(ExpansionApi.satisfies("1.0.0", null));
    }

    @Test
    void resourcesCheckTheirPathsAndCopyOnChange() {
        Resource plain = Resource.of("/players/{player}/", "A card", request -> request.path("player"));
        assertEquals("players/{player}", plain.path());
        assertFalse(plain.isPublic());
        assertFalse(plain.serverThread());
        Resource open = plain.publicly().onServerThread();
        assertTrue(open.isPublic());
        assertTrue(open.serverThread());
        assertFalse(plain.isPublic(), "publicly() leaves the original alone");
        assertEquals("", Resource.of("stats", null, request -> 1).description());
        for (String bad : List.of("", "Players", "players//x", "players/{Player}", "a b", "../up")) {
            assertThrows(IllegalArgumentException.class, () -> Resource.of(bad, "", request -> null), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Resource.of("stats", "", null));
    }

    @Test
    void eventTypesAndEventsCheckTheirNames() {
        EventType type = EventType.of("boss.killed", "A boss died").publicly().recorded();
        assertTrue(type.isPublic());
        assertTrue(type.isRecorded());
        assertFalse(EventType.of("full_moon", null).isRecorded());
        assertThrows(IllegalArgumentException.class, () -> EventType.of("Boss.Killed", ""));
        assertThrows(IllegalArgumentException.class, () -> EventType.of("boss..killed", ""));

        UUID id = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>(Map.of("level", 12));
        Event event = Event.of("player.level_up", data).player(id, "Steve").world("world");
        data.put("level", 13);
        assertEquals(12, event.data().get("level"), "the event keeps its own copy of the data");
        assertThrows(UnsupportedOperationException.class, () -> event.data().put("x", 1));
        assertEquals(id, event.player());
        assertEquals("Steve", event.playerName());
        assertEquals("world", event.world());
        assertNull(Event.of("full_moon", null).player());
        assertTrue(Event.of("full_moon", null).data().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Event.of("full moon", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> event.player(null, "Steve"));
    }

    @Test
    void requestsReadNumbersFromTheQuery() {
        Request request = request(Map.of("limit", "500", "bad", "five", "low", "-3"));
        assertEquals(50, request.query("limit", 10, 1, 50));
        assertEquals(10, request.query("missing", 10, 1, 50));
        assertEquals(1, request.query("low", 10, 1, 50));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> request.query("bad", 10, 1, 50));
        assertEquals("bad must be a whole number", error.getMessage());
    }

    @Test
    void theHostGetsEveryExpansionRegisteredBeforeIt() {
        WorldApiExpansions.register(expansion("first"));
        WorldApiExpansions.register(expansion("second"));
        WorldApiExpansions.register(expansion("first"));
        assertFalse(WorldApiExpansions.available());
        assertEquals(ExpansionApi.VERSION, WorldApiExpansions.apiVersion());
        assertFalse(WorldApiExpansions.publish("first", Event.of("ping", Map.of())), "no WorldAPI, no event");

        Recorder host = new Recorder();
        WorldApiExpansions.bind(host);
        assertTrue(WorldApiExpansions.available());
        assertEquals("1.9.0", WorldApiExpansions.apiVersion());
        assertEquals(List.of("register second", "register first"), host.calls, "the replaced one goes once, as the newest");

        WorldApiExpansions.register(expansion("third"));
        assertTrue(WorldApiExpansions.publish("third", Event.of("ping", Map.of("n", 1))));
        assertFalse(WorldApiExpansions.publish("nobody", Event.of("ping", Map.of())), "an id nobody registered");
        WorldApiExpansions.unregister("second");
        assertEquals(List.of("register second", "register first", "register third", "publish third ping", "unregister second"), host.calls);
        assertEquals(List.of("first", "third"), WorldApiExpansions.registered().stream().map(WorldApiExpansion::id).toList());

        WorldApiExpansions.bind(null);
        assertFalse(WorldApiExpansions.available());
        assertFalse(WorldApiExpansions.publish("third", Event.of("ping", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> WorldApiExpansions.register(null));
        assertThrows(IllegalArgumentException.class, () -> WorldApiExpansions.publish("third", null));
    }

    @Test
    void anExpansionNeedsOnlyItsIdAndVersion() {
        WorldApiExpansion bare = new WorldApiExpansion() {
            @Override
            public String id() {
                return "bare";
            }

            @Override
            public String requiresApi() {
                return ExpansionApi.VERSION;
            }
        };
        assertEquals("bare", bare.name());
        assertEquals("", bare.description());
        assertTrue(bare.resources().isEmpty());
        assertTrue(bare.events().isEmpty());
    }

    private static WorldApiExpansion expansion(String id) {
        return new WorldApiExpansion() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String requiresApi() {
                return ExpansionApi.VERSION;
            }
        };
    }

    private static Request request(Map<String, String> query) {
        return new Request() {
            @Override
            public String path(String name) {
                throw new IllegalArgumentException("No path parameter " + name);
            }

            @Override
            public String query(String name) {
                return query.get(name);
            }

            @Override
            public Map<String, String> parameters() {
                return query;
            }

            @Override
            public boolean anonymous() {
                return true;
            }

            @Override
            public boolean can(String permission) {
                return false;
            }

            @Override
            public boolean seesCoordinates() {
                return false;
            }
        };
    }

    private static final class Recorder implements WorldApiExpansions.Host {
        final List<String> calls = new ArrayList<>();

        @Override
        public void register(WorldApiExpansion expansion) {
            calls.add("register " + expansion.id());
        }

        @Override
        public void unregister(String id) {
            calls.add("unregister " + id);
        }

        @Override
        public void publish(String id, Event event) {
            calls.add("publish " + id + " " + event.name());
        }

        @Override
        public String apiVersion() {
            return "1.9.0";
        }
    }
}
