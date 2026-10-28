package io.github.cskwork.lunepick.member.internal.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Sample credentials never enter production migrations or production startup. */
@Component
@Profile("(local | test) & !prod & !production")
class DemoMemberInitializer implements ApplicationRunner {
    private final AuthService auth;
    private final AuthTransactions transactions;

    DemoMemberInitializer(AuthService auth, AuthTransactions transactions) {
        this.auth = auth;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (transactions.credentials("demo@example.com").isEmpty()) {
            auth.signup(new AuthDtos.SignupRequest("demo@example.com", "demo1234", "데모 회원", null));
        }
    }
}
