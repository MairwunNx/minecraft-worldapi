package com.mairwunnx.worldapi.smoke;

import com.mairwunnx.worldapi.expansion.Resource;
import com.mairwunnx.worldapi.expansion.WorldApiExpansion;
import java.util.List;
import java.util.Map;

/** An expansion of API 1, named in its own META-INF/services file: plugins built against it keep working. */
@SuppressWarnings("deprecation")
public final class LegacyExpansion implements WorldApiExpansion {
    @Override
    public String id() {
        return "smoke_legacy";
    }

    @Override
    public String requiresApi() {
        return "1.0.0";
    }

    @Override
    public List<Resource> resources() {
        return List.of(Resource.of("echo/{word}", "The word and who asks", request -> Map.of("word", request.path("word"), "anonymous", request.anonymous())).publicly());
    }
}
