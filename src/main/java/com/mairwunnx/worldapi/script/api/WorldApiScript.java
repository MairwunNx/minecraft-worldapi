package com.mairwunnx.worldapi.script.api;

/**
 * A script WorldAPI compiles and loads at runtime. Implement {@link #run} for a one-off job
 * that returns a value, or {@link #enable} and {@link #disable} for a script that stays loaded
 * and reacts to events. Register listeners and tasks through the {@link ScriptContext}: that's
 * how unloading finds and removes them.
 */
public interface WorldApiScript {
    /** One-off execution. The return value goes back to the caller as JSON. */
    default Object run(ScriptContext ctx) throws Exception {
        return null;
    }

    /** Called once when a resident script loads, on the server thread. */
    default void enable(ScriptContext ctx) throws Exception {
    }

    /** Called before unloading; listeners and tasks from the context are removed afterwards anyway. */
    default void disable() throws Exception {
    }
}
