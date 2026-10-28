package io.github.cskwork.lunepick.search.internal.web;

import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class SearchWebPolicy implements WebPolicyContributor {
    @Override
    public List<PublicEndpoint> publicEndpoints() {
        return List.of(new PublicEndpoint(HttpMethod.GET, "/api/search/**", false));
    }

    @Override
    public List<String> etagPaths() {
        return List.of("/api/search/*");
    }
}
