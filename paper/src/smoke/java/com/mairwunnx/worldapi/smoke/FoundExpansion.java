package com.mairwunnx.worldapi.smoke;

import com.mairwunnx.worldapi.expansion.ExpansionApi;
import com.mairwunnx.worldapi.expansion.Resource;
import com.mairwunnx.worldapi.expansion.WorldApiExpansion;
import java.util.List;
import java.util.Map;

/** The expansion WorldAPI finds by itself, named in META-INF/services. */
public final class FoundExpansion implements WorldApiExpansion {
    @Override
    public String id() {
        return "smoke_found";
    }

    @Override
    public String requiresApi() {
        return ExpansionApi.VERSION;
    }

    @Override
    public String description() {
        return "The smoke test's expansion";
    }

    @Override
    public List<Resource> resources() {
        return List.of(Resource.of("echo/{word}", "The word, the limit, and who asks", request -> Map.of(
            "word", request.path("word"), "limit", request.query("limit", 5, 1, 10), "anonymous", request.anonymous())).publicly());
    }
}
