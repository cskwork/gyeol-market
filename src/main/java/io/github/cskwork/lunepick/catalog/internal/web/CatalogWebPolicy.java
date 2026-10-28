package io.github.cskwork.lunepick.catalog.internal.web;

import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class CatalogWebPolicy implements WebPolicyContributor {
    @Override
    public List<PublicEndpoint> publicEndpoints() {
        return List.of(
                new PublicEndpoint(HttpMethod.GET, "/api/categories/**", false),
                new PublicEndpoint(HttpMethod.GET, "/api/products/**", false));
    }

    @Override
    public List<String> etagPaths() {
        return List.of("/api/products/*", "/api/categories/*");
    }
}
