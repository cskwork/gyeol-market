package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.cskwork.lunepick.catalog.ProductChanged;
import io.github.cskwork.lunepick.search.internal.application.SearchIndexWorker;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendIntegrationTest extends EmbeddedDatabase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate transactions;
    @Autowired AdjustableClock clock;
    @Autowired SearchIndexWorker indexWorker;
    @Autowired EntityManagerFactory entityManagerFactory;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        static JdbcStatementCounter statementCounter() {
            return new JdbcStatementCounter();
        }

        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }
    }

    static class AdjustableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-28T00:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @AfterEach
    void resetClock() {
        clock.now = Instant.parse("2026-10-28T00:00:00Z");
    }

    static Stream<String> publicFixtures() {
        return Stream.of(
                "categories",
                "category-products",
                "products",
                "rankings",
                "best-sellers",
                "product-detail",
                "product-reviews",
                "search-products",
                "autocomplete",
                "popular",
                "error-product-not-found",
                "error-category-not-found",
                "error-invalid-parameter",
                "error-page-garbage",
                "error-size-fractional");
    }

    @ParameterizedTest(name = "HTTP contract: {0}")
    @MethodSource("publicFixtures")
    void catalogAndSearchMatchContractFixtures(String name) throws Exception {
        var fixture = fixture(name);
        var result = perform(fixture, null, null);
        assertContract(fixture, result);
        // Pin seed values as well as shape. Numeric scale is intentionally not part of JSON's
        // number type.
        if (fixture.get("status").asInt() == 200)
            assertJsonValues(fixture.get("body"), json(result), "$");
    }

    @Test
    void spaEntryAndDeepLinksReturnHtmlAndMissingAssetsStay404() throws Exception {
        for (String path : List.of("/app", "/app/", "/app/cart"))
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(Matchers.containsString("id=\"root\"")));
        mvc.perform(get("/app/assets/missing.js")).andExpect(status().isNotFound());
    }

    @Test
    void flywaySeedAndIndexesAreRealPostgres() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT current_setting('server_version_num')", String.class))
                .isEqualTo("140022");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from flyway_schema_history where success",
                                Integer.class))
                .isEqualTo(17);
        assertThat(jdbc.queryForObject("select count(*) from brand", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from category", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from product", Integer.class))
                .isEqualTo(13);
        assertThat(jdbc.queryForObject("select count(*) from product_option", Integer.class))
                .isEqualTo(14);
        assertThat(jdbc.queryForObject("select count(*) from product_image", Integer.class))
                .isEqualTo(15);
        assertThat(jdbc.queryForObject("select count(*) from search_documents", Integer.class))
                .isEqualTo(13);
        assertThat(jdbc.queryForObject("select count(*) from product_review", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from pg_extension where extname='pg_trgm'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select indexdef from pg_indexes where"
                                        + " indexname='idx_search_full_text'",
                                String.class))
                .contains("USING gin (search_vector)");
    }

    @Test
    void pagedCatalogHasStableSortsFiltersAndNoCategoryJoinDuplicates() throws Exception {
        assertThat(ids(getJson("/api/products?sort=LATEST")))
                .containsExactly(13L, 12L, 11L, 10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
        assertThat(ids(getJson("/api/products?sort=RATING")))
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L);
        assertThat(ids(getJson("/api/products?sort=POPULAR&size=3"))).containsExactly(5L, 6L, 3L);
        assertThat(ids(getJson("/api/products?categoryId=2&brandId=4&sort=PRICE_DESC")))
                .containsExactly(9L, 7L, 8L);
        mvc.perform(get("/api/products?categoryId=bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products?page=-3&size=101")).andExpect(status().isBadRequest());
        assertThat(ids(getJson("/api/products?page=2147483647&size=100"))).isEmpty();
        assertThat(ids(getJson("/api/products?sort=PRICE_ASC&page=1&size=2")))
                .containsExactly(8L, 7L);
    }

    @Test
    void pagingRejectsGarbageAndPreservesNormalIntegerRequests() throws Exception {
        for (String path :
                List.of(
                        "/api/products?page=1&size=2e0",
                        "/api/products?size=0x2",
                        "/api/products?page=0.5",
                        "/api/products?size=1.5",
                        "/api/search/products?size=2e0",
                        "/api/products?page=Infinity",
                        "/api/products?size=",
                        "/api/products?page=-1")) {
            mvc.perform(get(path))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        }
        assertThat(ids(getJson("/api/products?page=1&size=2"))).containsExactly(11L, 10L);
    }

    @Test
    void productListQueryCountStaysBoundedAsPageGrows() throws Exception {
        for (int size : List.of(1, 13)) {
            JdbcStatementCounter.start();
            try {
                assertThat(getJson("/api/products?size=" + size).at("/data").size())
                        .isEqualTo(size);
            } finally {
                assertThat(JdbcStatementCounter.stop())
                        .as("queries for page size %s", size)
                        .isEqualTo(3);
            }
        }
    }

    @Test
    void searchSupportsKoreanLiteralsAndCaseInsensitiveAutocomplete() throws Exception {
        assertThat(ids(getJson("/api/search/products?keyword=" + enc("밍 시카")))).containsExactly(3L);
        assertThat(
                        ids(
                                getJson(
                                        "/api/search/products?keyword="
                                                + enc("  더마하랑  ")
                                                + "&sort=RELEVANCE&brandId=999")))
                .containsExactly(3L, 13L);
        assertThat(ids(getJson("/api/search/products?categoryId=2&sort=PRICE_ASC")))
                .containsExactly(8L, 7L, 1L, 9L);
        for (String literal : List.of("%", "_", "!")) {
            assertThat(ids(getJson("/api/search/products?keyword=" + enc(literal)))).isEmpty();
            assertThat(
                            getJson("/api/search/autocomplete?prefix=" + enc(literal))
                                    .at("/data/suggestions")
                                    .size())
                    .isZero();
        }
        assertThat(getJson("/api/search/autocomplete?prefix=spf").at("/data/suggestions").size())
                .isEqualTo(1);
        assertThat(getJson("/api/search/autocomplete?prefix=SPF").at("/data/suggestions").size())
                .isEqualTo(1);
        for (String invalid : List.of("-1", "-100", "0", "-0.5", "Infinity", "0x2"))
            mvc.perform(get("/api/search/autocomplete?prefix=크&size=" + invalid))
                    .andExpect(status().isBadRequest());
    }

    @Test
    void productChangedUpdatesAndDeletesProjectionAfterCommitThroughModuleBoundary()
            throws Exception {
        String before = jdbc.queryForObject("select name from product where id=13", String.class);
        try {
            transactions.executeWithoutResult(
                    status -> {
                        jdbc.update(
                                "update product set name=? where id=13",
                                "projection-contract-marker");
                        events.publishEvent(new ProductChanged(13));
                        assertThat(
                                        jdbc.queryForObject(
                                                "select product_name from search_documents where"
                                                        + " product_id=13",
                                                String.class))
                                .isNotEqualTo("projection-contract-marker");
                    });
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                indexWorker.processBatch();
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select product_name from search_documents"
                                                                + " where product_id=13",
                                                        String.class))
                                        .isEqualTo("projection-contract-marker");
                            });
            assertThat(ids(getJson("/api/search/products?keyword=projection-contract-marker")))
                    .containsExactly(13L);
            transactions.executeWithoutResult(
                    status -> events.publishEvent(new ProductChanged(999999)));
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from search_documents where product_id=999999",
                                    Integer.class))
                    .isZero();
        } finally {
            transactions.executeWithoutResult(
                    status -> {
                        jdbc.update("update product set name=? where id=13", before);
                        events.publishEvent(new ProductChanged(13));
                    });
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                indexWorker.processBatch();
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select product_name from search_documents"
                                                                + " where product_id=13",
                                                        String.class))
                                        .isEqualTo(before);
                            });
        }
    }

    @Test
    void nonemptyReviewsMatchPageConsumersAndKeepPrivateMemberNamesHidden() throws Exception {
        var fixture = fixture("product-reviews-example");
        var row = fixture.at("/body/data/0");
        jdbc.update(
                "insert into"
                        + " product_review(id,product_id,member_name,rating,title,content,image_urls,created_at)"
                        + " values(?,1,?,?,?,?,?::jsonb,?::timestamptz)",
                row.get("id").asLong(),
                "private-author",
                row.get("rating").decimalValue(),
                row.get("title").asText(),
                row.get("body").asText(),
                row.get("imageUrls").toString(),
                row.get("createdAt").asText());
        try {
            transactions.executeWithoutResult(status -> events.publishEvent(new ProductChanged(1)));
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                indexWorker.processBatch();
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select review_count from search_documents"
                                                                + " where product_id=1",
                                                        Long.class))
                                        .isEqualTo(1);
                            });
            var result = perform(fixture, null, null);
            assertContract(fixture, result);
            assertJsonValues(fixture.get("body"), json(result), "$");
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("private-author", "memberName");
            var catalog = getJson("/api/products/1").get("data");
            var searched = getJson("/api/search/products?keyword=SPF").at("/data/0");
            assertThat(searched.get("rating").decimalValue())
                    .isEqualByComparingTo(catalog.get("rating").decimalValue());
            assertThat(searched.get("reviewCount").asLong()).isEqualTo(1);
        } finally {
            jdbc.update("delete from product_review where id=?", row.get("id").asLong());
            transactions.executeWithoutResult(status -> events.publishEvent(new ProductChanged(1)));
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                indexWorker.processBatch();
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select review_count from search_documents"
                                                                + " where product_id=1",
                                                        Long.class))
                                        .isZero();
                            });
        }
    }

    @Test
    void signupLoginRotationReuseRevocationAndLogoutMatchContract() throws Exception {
        var signup = perform(fixture("signup"), null, null);
        assertContract(fixture("signup"), signup);
        assertContract(
                fixture("error-email-duplicated"),
                perform(fixture("error-email-duplicated"), null, null));
        var login = perform(fixture("login"), null, null);
        assertContract(fixture("login"), login);
        String access = json(login).at("/data/accessToken").asText();
        String refresh = json(login).at("/data/refreshToken").asText();
        assertThat(
                        new String(
                                Base64.getUrlDecoder().decode(access.split("\\.")[0]),
                                StandardCharsets.UTF_8))
                .contains("RS256");
        assertThat(jdbc.queryForList("select token_hash from refresh_tokens", String.class))
                .allSatisfy(
                        hash -> {
                            assertThat(hash).matches("[0-9a-f]{64}");
                            assertThat(hash).isNotEqualTo(refresh);
                        });
        assertContract(
                fixture("error-forbidden"), perform(fixture("error-forbidden"), access, null));
        var rotated = perform(fixture("refresh"), null, Map.of("refreshToken", refresh));
        assertContract(fixture("refresh"), rotated);
        String rotatedRefresh = json(rotated).at("/data/refreshToken").asText();
        String rotatedAccess = json(rotated).at("/data/accessToken").asText();
        assertThat(rotatedRefresh).isNotEqualTo(refresh);
        assertThat(rotatedAccess).isNotEqualTo(access);
        // Beyond the bounded grace, reuse must revoke the complete family.
        clock.now = clock.now.plusSeconds(4);
        assertInvalidRefresh(refresh);
        assertInvalidRefresh(rotatedRefresh);
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + rotatedAccess))
                .andExpect(status().isUnauthorized());
        var secondLogin = perform(fixture("login"), null, null);
        String secondAccess = json(secondLogin).at("/data/accessToken").asText();
        String secondRefresh = json(secondLogin).at("/data/refreshToken").asText();
        assertContract(fixture("logout"), perform(fixture("logout"), secondAccess, null));
        assertInvalidRefresh(secondRefresh);
    }

    @Test
    void authFailureEnvelopesAndValidationNeverEchoCredentials() throws Exception {
        for (String name :
                List.of(
                        "error-invalid-credentials",
                        "error-invalid-token",
                        "error-unauthorized",
                        "error-validation")) {
            var fixture = fixture(name);
            var result = perform(fixture, null, null);
            assertContract(fixture, result);
            if (name.equals("error-validation")) {
                assertThat(result.getResponse().getContentAsString()).doesNotContain("short");
                assertThat(json(result).at("/error/fieldErrors/0/rejectedValue").isNull()).isTrue();
            }
        }
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer invalid.jwt.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void simultaneousRefreshReturnsSameSuccessorAndKeepsSessionValid() throws Exception {
        var session = demoLogin();
        String refresh = session.at("/data/refreshToken").asText();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<MvcResult> request =
                    () -> {
                        start.await();
                        return perform(fixture("refresh"), null, Map.of("refreshToken", refresh));
                    };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            List<MvcResult> results =
                    List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().map(r -> r.getResponse().getStatus()))
                    .containsExactly(200, 200);
            var winner =
                    results.stream()
                            .filter(r -> r.getResponse().getStatus() == 200)
                            .findFirst()
                            .orElseThrow();
            assertThat(json(results.get(0)).at("/data/refreshToken").asText())
                    .isEqualTo(json(results.get(1)).at("/data/refreshToken").asText());
            assertContract(
                    fixture("refresh"),
                    perform(
                            fixture("refresh"),
                            null,
                            Map.of(
                                    "refreshToken",
                                    json(winner).at("/data/refreshToken").asText())));
        }
    }

    @Test
    void tokenTwoGenerationsOldRevokesFamilyEvenInsideGrace() throws Exception {
        var session = demoLogin();
        String initial = session.at("/data/refreshToken").asText();
        var second = perform(fixture("refresh"), null, Map.of("refreshToken", initial));
        var third =
                perform(
                        fixture("refresh"),
                        null,
                        Map.of("refreshToken", json(second).at("/data/refreshToken").asText()));
        assertInvalidRefresh(initial);
        assertInvalidRefresh(json(third).at("/data/refreshToken").asText());
    }

    @Test
    void customerJwtCannotReadMetricsAndHealthIsPublic() throws Exception {
        var session = demoLogin();
        mvc.perform(
                        get("/actuator/metrics")
                                .header(
                                        "Authorization",
                                        "Bearer " + session.at("/data/accessToken").asText()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void accessAndRefreshExpiryUseInjectedClock() throws Exception {
        var session = demoLogin();
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        mvc.perform(
                        post("/api/auth/logout")
                                .header(
                                        "Authorization",
                                        "Bearer " + session.at("/data/accessToken").asText()))
                .andExpect(status().isUnauthorized());
        clock.now = clock.now.plus(Duration.ofDays(31));
        assertInvalidRefresh(session.at("/data/refreshToken").asText());
    }

    @Test
    void loginIgnoresExpiredBearerAndHttpFailuresStayInEnvelope() throws Exception {
        mvc.perform(
                        post("/api/auth/login")
                                .header("Authorization", "Bearer expired.jwt.value")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "demo@example.com",
                                                        "password",
                                                        "demo1234"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString());
        var login = demoLogin();
        String token = login.at("/data/accessToken").asText();
        mvc.perform(post("/api/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("plain"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/api/products/999999999999999999999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/products/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void catalogEtagsRevalidateAndAuthResponsesAreNeverStored() throws Exception {
        var first = mvc.perform(get("/api/products?size=1")).andExpect(status().isOk()).andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        mvc.perform(get("/api/products?size=1").header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "demo@example.com",
                                                        "password",
                                                        "demo1234"))))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
        mvc.perform(get("/api/products/999999"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void publicHealthOpenApiAndRequestIdAreAvailable() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").isString());
        mvc.perform(get("/api/products").header("X-Request-Id", "contract-request-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "contract-request-123"));
        mvc.perform(get("/api/products").header("X-Request-Id", "unsafe request id"))
                .andExpect(
                        header().string("X-Request-Id", Matchers.matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void openApiDescribesAllThirtyStorefrontOperationsAndTheWebhook() throws Exception {
        var document =
                json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        int operations = 0;
        for (var path : document.get("paths").properties()) {
            if (!path.getKey().startsWith("/api/")) continue;
            for (String method : List.of("get", "post", "patch", "delete")) {
                var operation = path.getValue().get(method);
                if (operation == null) continue;
                operations++;
                assertThat(operation.path("summary").asText())
                        .as(method + " " + path.getKey())
                        .isNotBlank();
            }
        }
        assertThat(operations).isEqualTo(31);
        assertThat(
                        document.at(
                                        "/paths/~1api~1auth~1login/post/responses/429/headers/Retry-After")
                                .isMissingNode())
                .isFalse();
    }

    private JsonNode demoLogin() throws Exception {
        return json(
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of(
                                                                "email",
                                                                "demo@example.com",
                                                                "password",
                                                                "demo1234"))))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private void assertInvalidRefresh(String token) throws Exception {
        var result = perform(fixture("error-invalid-token"), null, Map.of("refreshToken", token));
        assertContract(fixture("error-invalid-token"), result);
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode getJson(String path) throws Exception {
        return json(mvc.perform(get(URI.create(path))).andExpect(status().isOk()).andReturn());
    }

    private List<Long> ids(JsonNode node) {
        List<Long> result = new ArrayList<>();
        node.get("data").forEach(item -> result.add(item.get("productId").asLong()));
        return result;
    }

    private JsonNode fixture(String name) throws Exception {
        return mapper.readTree(Files.readString(Path.of("contract", name + ".json")));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult perform(JsonNode fixture, String accessToken, Object requestOverride)
            throws Exception {
        MockHttpServletRequestBuilder request =
                request(fixture.get("method").asText(), URI.create(fixture.get("path").asText()));
        Object body = requestOverride != null ? requestOverride : fixture.get("request");
        if (body != null)
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body));
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(request).andReturn();
    }

    private void assertContract(JsonNode fixture, MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(fixture.get("path").asText())
                .isEqualTo(fixture.get("status").asInt());
        assertShape(fixture.get("body"), json(result), "$");
        if (!fixture.at("/body/success").asBoolean()) {
            assertThat(json(result).at("/error/code").asText())
                    .isEqualTo(fixture.at("/body/error/code").asText());
        }
    }

    private void assertShape(JsonNode expected, JsonNode actual, String path) {
        assertThat(actual).as(path).isNotNull();
        if (expected.isObject()) {
            // traceId is generated per request and optional in the client type.
            Set<String> fields = new HashSet<>();
            actual.properties().forEach(e -> fields.add(e.getKey()));
            Set<String> wanted = new HashSet<>();
            expected.properties().forEach(e -> wanted.add(e.getKey()));
            fields.remove("traceId");
            wanted.remove("traceId");
            assertThat(fields).as(path).isEqualTo(wanted);
            expected.properties()
                    .forEach(
                            e -> {
                                if (!e.getKey().equals("traceId"))
                                    assertShape(
                                            e.getValue(),
                                            actual.get(e.getKey()),
                                            path + "." + e.getKey());
                            });
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as(path).isTrue();
            if (!expected.isEmpty())
                actual.forEach(n -> assertShape(expected.get(0), n, path + "[]"));
        } else if (expected.isNull()) assertThat(actual.isNull()).as(path).isTrue();
        else if (expected.isNumber()) assertThat(actual.isNumber()).as(path).isTrue();
        else if (expected.isBoolean()) assertThat(actual.isBoolean()).as(path).isTrue();
        else {
            assertThat(actual.isString()).as(path).isTrue();
            if (path.endsWith("createdAt"))
                assertThatCode(() -> Instant.parse(actual.asText())).doesNotThrowAnyException();
        }
    }

    private void assertJsonValues(JsonNode expected, JsonNode actual, String path) {
        if (expected.isObject())
            expected.properties()
                    .forEach(
                            e ->
                                    assertJsonValues(
                                            e.getValue(),
                                            actual.get(e.getKey()),
                                            path + "." + e.getKey()));
        else if (expected.isArray()) {
            assertThat(actual.size()).as(path).isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++)
                assertJsonValues(expected.get(i), actual.get(i), path + "[" + i + "]");
        } else if (expected.isNumber())
            assertThat(actual.decimalValue())
                    .as(path)
                    .isEqualByComparingTo(expected.decimalValue());
        else assertThat(actual).as(path).isEqualTo(expected);
    }
}
