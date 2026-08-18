package dev.termestra.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("dev.termestra");

    @Test
    void domainDoesNotDependOnFrameworkOrInfrastructure() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..", "..adapter..", "dev.termestra.platform..",
                        "dev.termestra.bootstrap..",
                        "org.springframework..", "com.fasterxml.jackson..", "java.sql..",
                        "io.netty..", "com.pty4j..")
                .check(classes);
    }

    @Test
    void applicationCoreDoesNotDependOnAdaptersOrFrameworks() {
        noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter..", "dev.termestra.platform..", "dev.termestra.bootstrap..",
                        "org.springframework..", "java.sql..", "io.netty..", "com.pty4j..")
                .check(classes);
    }

    @Test
    void inboundAdaptersDoNotAccessOutboundAdapters() {
        noClasses()
                .that().resideInAnyPackage(
                        "..adapter.in..", "dev.termestra.platform.web..", "dev.termestra.platform.cli..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter.out..", "dev.termestra.platform.persistence..")
                .check(classes);
    }

    @Test
    void sharedKernelDoesNotDependOnContextsOrFrameworks() {
        noClasses()
                .that().resideInAPackage("dev.termestra.shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "dev.termestra.workspace..", "dev.termestra.team..", "dev.termestra.execution..",
                        "dev.termestra.tasks..", "dev.termestra.terminal..", "dev.termestra.configuration..",
                        "dev.termestra.marketplace..", "dev.termestra.auth..", "dev.termestra.platform..",
                        "dev.termestra.bootstrap..", "org.springframework..", "java.sql..")
                .check(classes);
    }
}
