# WorldAPI Expansion API

`com.mairwunnx:worldapi-expansion` on Maven Central. Within a major version the API only grows: an expansion built against an older version runs on every newer WorldAPI of that major. The wiki's “Expansions” says how to use it.

## 2.0.0

API 2, in the package `com.mairwunnx.worldapi.expansion.v2`. An expansion is a class or a Kotlin `object`, and its fields declare what it shares, the way Exposed declares a table's columns. The compiler now ties an event to its declaration and its data. API 1 stays in its package for plugins built against it.

- `Expansion`: extend it with an id, a name, and a description, then declare parameters, resources, and events as fields. `register()` and `unregister()` on the expansion itself, `requiresApi()` gone: WorldAPI tells a too new expansion by the classes and methods it misses.
- `Event<T>`: an event with a record for its data, published through its own field, `killed.publish(BossKilled(...))`. Its record's shape reaches `GET /extensions` and the MCP server.
- `Param<T>`: a declared parameter, `integer`, `text`, `flag`, `choice` of an enum, or `player` by name or UUID, read with `req[limit]` in Kotlin or `req.get(limit)` in Java. A path takes it by name, a query through `query(...)`, and `GET /extensions` lists them, so descriptions no longer carry `?limit=1..50`.
- `Resource`: answers with records, or plain values as before; `returns` and `returnsListOf` name the record for the schema.
- `PlayerRef` and `Place`: a player in an event hides it while the player is vanished, wherever it sits; a place is left out for clients that mustn't see coordinates, in events and answers alike.
- `Permission`: WorldAPI's permissions for `Request.can`, instead of strings.
- `META-INF/services/com.mairwunnx.worldapi.expansion.v2.Expansion` names a Kotlin `object` too: WorldAPI takes its `INSTANCE`.

## 1.0.0

The first version, with WorldAPI 3.4.0.

- `WorldApiExpansion`: an id, which names the expansion's resources at `/extensions/<id>/...` and its topics `ext.<id>.<event>`, a description, its resources, and its event types.
- `Resource`: a path with `{parameters}`, a description, and a handler that answers with plain Java values or a `CompletionStage` of them; `publicly()` opens it to visitors without a token, `onServerThread()` runs the handler on the server thread.
- `Request`: path and query parameters, a clamped whole number from the query, and who asks: `anonymous()`, `can(permission)`, `seesCoordinates()`.
- `EventType` and `Event`: what an expansion publishes, `publicly()` and `recorded()`, with the player and the world it's about.
- `WorldApiExpansions`: `register`, `unregister`, and `publish` from code, or `META-INF/services/com.mairwunnx.worldapi.expansion.WorldApiExpansion` for WorldAPI to find by itself.
