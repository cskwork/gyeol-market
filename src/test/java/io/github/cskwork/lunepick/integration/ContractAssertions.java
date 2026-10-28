package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ContractAssertions {
    private final ObjectMapper mapper;

    ContractAssertions(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    JsonNode fixture(String name) throws Exception {
        return mapper.readTree(Files.readString(Path.of("contract", name + ".json")));
    }

    JsonNode response(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    void check(String name, MvcResult result) throws Exception {
        var expected = fixture(name);
        assertThat(result.getResponse().getStatus())
                .as(name)
                .isEqualTo(expected.get("status").asInt());
        var actual = response(result);
        shape(expected.get("body"), actual, name);
        assertThat(actual.get("success").asBoolean())
                .isEqualTo(expected.at("/body/success").asBoolean());
        if (expected.get("status").asInt() >= 400)
            assertThat(actual.at("/error/code").asText())
                    .as(name)
                    .isEqualTo(expected.at("/body/error/code").asText());
        if (expected.has("expectedHeaders"))
            expected.get("expectedHeaders")
                    .properties()
                    .forEach(
                            field -> {
                                if (field.getKey().equalsIgnoreCase("Retry-After"))
                                    assertThat(result.getResponse().getHeader(field.getKey()))
                                            .matches("[1-9][0-9]*");
                                else
                                    assertThat(result.getResponse().getHeader(field.getKey()))
                                            .isEqualTo(field.getValue().asText());
                            });
    }

    private void shape(JsonNode expected, JsonNode actual, String path) {
        assertThat(actual).as(path).isNotNull();
        if (expected.isObject()) {
            Set<String> wanted = new HashSet<>(), found = new HashSet<>();
            expected.properties().forEach(e -> wanted.add(e.getKey()));
            actual.properties().forEach(e -> found.add(e.getKey()));
            wanted.remove("traceId");
            found.remove("traceId");
            assertThat(found).as(path).isEqualTo(wanted);
            expected.properties()
                    .forEach(
                            e -> {
                                if (!e.getKey().equals("traceId"))
                                    shape(
                                            e.getValue(),
                                            actual.get(e.getKey()),
                                            path + "." + e.getKey());
                            });
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as(path).isTrue();
            if (!expected.isEmpty()) {
                assertThat(actual.size()).as(path).isGreaterThan(0);
                actual.forEach(item -> shape(expected.get(0), item, path + "[]"));
            }
        } else if (expected.isNull()) assertThat(actual.isNull()).as(path).isTrue();
        else if (expected.isNumber()) assertThat(actual.isNumber()).as(path).isTrue();
        else if (expected.isBoolean()) assertThat(actual.isBoolean()).as(path).isTrue();
        else {
            assertThat(actual.isString()).as(path).isTrue();
            if (path.endsWith("At"))
                assertThatCode(() -> Instant.parse(actual.asText())).doesNotThrowAnyException();
        }
    }
}
