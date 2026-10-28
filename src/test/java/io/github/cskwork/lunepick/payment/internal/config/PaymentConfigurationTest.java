package io.github.cskwork.lunepick.payment.internal.config;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PaymentConfigurationTest {
    private final PaymentConfiguration configuration = new PaymentConfiguration();

    @Test
    void localAndTestMayUseSyntheticSecret() {
        for (String profile : new String[] {"local", "test"}) {
            var env = new MockEnvironment();
            env.setActiveProfiles(profile);
            assertThat(
                            configuration.webhookSignature(
                                    new PaymentProperties(null), env, Clock.systemUTC()))
                    .isNotNull();
        }
    }

    @Test
    void otherProfilesRequireExplicitSecretAndRejectSyntheticDefault() {
        var env = new MockEnvironment();
        env.setActiveProfiles("production");
        for (String secret : new String[] {null, PaymentConfiguration.LOCAL_SECRET})
            assertThatThrownBy(
                            () ->
                                    configuration.webhookSignature(
                                            new PaymentProperties(secret), env, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
        assertThat(
                        configuration.webhookSignature(
                                new PaymentProperties("a".repeat(32)), env, Clock.systemUTC()))
                .isNotNull();
    }

    @Test
    void productionProfileCannotEnableDevelopmentSecretByAddingLocalProfile() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod", "local");
        assertThatThrownBy(
                        () ->
                                configuration.webhookSignature(
                                        new PaymentProperties(null), env, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void environmentVariableIsSupportedAndNeverAcceptsShortSecrets() {
        var env =
                new MockEnvironment()
                        .withProperty("LUNEPICK_PAYMENT_WEBHOOK_SECRET", "a".repeat(32));
        assertThat(
                        configuration.webhookSignature(
                                new PaymentProperties(null), env, Clock.systemUTC()))
                .isNotNull();
        env.setProperty("LUNEPICK_PAYMENT_WEBHOOK_SECRET", "short");
        assertThatThrownBy(
                        () ->
                                configuration.webhookSignature(
                                        new PaymentProperties(null), env, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PaymentProperties("short").isWebhookSecretValid()).isFalse();
        assertThat(new PaymentProperties(null).isWebhookSecretValid()).isTrue();
        assertThat(new PaymentProperties("가".repeat(11)).isWebhookSecretValid()).isTrue();
    }
}
