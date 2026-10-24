# WorldAPI Expansion API

`com.mairwunnx:worldapi-expansion` on Maven Central. Within a major version the API only grows: an expansion built against an older version runs on every newer WorldAPI of that major. The wiki's “Expansions” says how to use it.

## 1.0.0

The first version, with WorldAPI 3.4.0.

- `WorldApiExpansion`: an id, which names the expansion's resources at `/extensions/<id>/...` and its topics `ext.<id>.<event>`, a description, its resources, and its event types.
- `Resource`: a path with `{parameters}`, a description, and a handler that answers with plain Java values or a `CompletionStage` of them; `publicly()` opens it to visitors without a token, `onServerThread()` runs the handler on the server thread.
- `Request`: path and query parameters, a clamped whole number from the query, and who asks: `anonymous()`, `can(permission)`, `seesCoordinates()`.
- `EventType` and `Event`: what an expansion publishes, `publicly()` and `recorded()`, with the player and the world it's about.
- `WorldApiExpansions`: `register`, `unregister`, and `publish` from code, or `META-INF/services/com.mairwunnx.worldapi.expansion.WorldApiExpansion` for WorldAPI to find by itself.
