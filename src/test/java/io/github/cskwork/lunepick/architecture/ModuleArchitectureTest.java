package io.github.cskwork.lunepick.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.cskwork.lunepick.LunepickApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModuleArchitectureTest {
    @Test
    void moduleBoundariesAndDocumentation() {
        var modules = ApplicationModules.of(LunepickApplication.class).verify();
        new Documenter(
                        modules,
                        Documenter.Options.defaults().withOutputFolder("target/modulith-docs"))
                .writeDocumentation();
    }

    @Test
    void moduleLayersPointInward() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.github.cskwork.lunepick");
        noClasses()
                .that()
                .resideInAPackage("..internal.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..internal.application..", "..internal.web..", "..internal.persistence..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..internal.application..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..internal.web..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..internal.persistence..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..internal.web..")
                .check(classes);
    }
}
