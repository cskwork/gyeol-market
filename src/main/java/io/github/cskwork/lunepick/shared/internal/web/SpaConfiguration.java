package io.github.cskwork.lunepick.shared.internal.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/** Non-root SPA paths; SpaController handles the empty resource path at the two entry URLs. */
@Configuration(proxyBeanMethods = false)
public class SpaConfiguration implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/app/**")
                .addResourceLocations("classpath:/static/app/")
                .resourceChain(true)
                .addResolver(
                        new PathResourceResolver() {
                            @Override
                            protected Resource getResource(String path, Resource location)
                                    throws IOException {
                                Resource asset = super.getResource(path, location);
                                if (asset != null && asset.isReadable()) return asset;
                                if (path.contains(".") || path.startsWith("assets/")) return null;
                                return super.getResource("index.html", location);
                            }
                        });
    }
}
