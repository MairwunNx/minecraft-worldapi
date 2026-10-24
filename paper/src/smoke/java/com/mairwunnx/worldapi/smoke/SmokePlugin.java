package com.mairwunnx.worldapi.smoke;

import com.mairwunnx.worldapi.expansion.Event;
import com.mairwunnx.worldapi.expansion.EventType;
import com.mairwunnx.worldapi.expansion.ExpansionApi;
import com.mairwunnx.worldapi.expansion.Resource;
import com.mairwunnx.worldapi.expansion.WorldApiExpansion;
import com.mairwunnx.worldapi.expansion.WorldApiExpansions;
import java.util.List;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A plugin for the smoke test only, never released: one expansion WorldAPI finds through META-INF/services
 * ({@link FoundExpansion}) and one this plugin registers in code, which also publishes an event on the first tick.
 */
public final class SmokePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        WorldApiExpansions.register(new WorldApiExpansion() {
            @Override
            public String id() {
                return "smoke_code";
            }

            @Override
            public String requiresApi() {
                return ExpansionApi.VERSION;
            }

            @Override
            public List<Resource> resources() {
                return List.of(Resource.of("worlds", "The worlds' names", request -> getServer().getWorlds().stream().map(it -> it.getName()).toList()).onServerThread());
            }

            @Override
            public List<EventType> events() {
                return List.of(EventType.of("ping", "Sent once, on the first tick").recorded());
            }
        });
        getServer().getScheduler().runTask(this, () ->
            getLogger().info("Published ping: " + WorldApiExpansions.publish("smoke_code", Event.of("ping", Map.of("n", 1)).world("world"))));
    }
}
