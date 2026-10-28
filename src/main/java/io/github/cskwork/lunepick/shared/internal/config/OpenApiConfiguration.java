package io.github.cskwork.lunepick.shared.internal.config;

import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.math.BigDecimal;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("LUNEPICK storefront API").version("0.1.0"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerJwt",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")));
    }

    @Bean
    OpenApiCustomizer rateLimitResponses(List<WebPolicyContributor> contributors) {
        return api -> {
            for (var rule :
                    contributors.stream()
                            .flatMap(c -> c.rateLimitRules().stream())
                            .distinct()
                            .toList()) {
                var item = api.getPaths().get(rule.pattern());
                if (item == null) continue;
                var operation =
                        item.readOperationsMap()
                                .get(PathItem.HttpMethod.valueOf(rule.method().name()));
                if (operation == null) continue;
                operation
                        .getResponses()
                        .addApiResponse(
                                "429",
                                new ApiResponse()
                                        .description(
                                                "요청 한도 초과. error.code=RATE_LIMITED인 ApiResponse 오류"
                                                        + " 봉투를 반환합니다.")
                                        .addHeaderObject(
                                                "Retry-After",
                                                new Header()
                                                        .description("다음 요청까지 기다릴 초 단위 시간")
                                                        .schema(
                                                                new IntegerSchema()
                                                                        .minimum(BigDecimal.ONE))));
            }
        };
    }
}
