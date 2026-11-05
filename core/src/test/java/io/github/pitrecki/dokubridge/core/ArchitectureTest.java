package io.github.pitrecki.dokubridge.core;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class ArchitectureTest {
    private static final JavaClasses CORE = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.pitrecki.dokubridge.core");

    @Test
    void domainAndPortsUseFeaturePackagesAroundTheCentralFacade() {
        classes().that().resideInAPackage("..core.domain..")
                .should().resideInAnyPackage("..core.domain.document..", "..core.domain.version..",
                        "..core.domain.upload..", "..core.domain.audit..", "..core.domain.content..")
                .check(CORE);
        classes().that().resideInAPackage("..core.port.in..")
                .and().doNotHaveFullyQualifiedName("io.github.pitrecki.dokubridge.core.port.in.DocumentBridge")
                .should().resideInAnyPackage("..core.port.in.document..", "..core.port.in.upload..")
                .check(CORE);
        classes().that().resideInAPackage("..core.port.out..")
                .should().resideInAnyPackage("..core.port.out.document..", "..core.port.out.version..",
                        "..core.port.out.upload..", "..core.port.out.audit..", "..core.port.out.content..")
                .check(CORE);
        classes().that().resideInAPackage("..core.application..")
                .and().doNotHaveFullyQualifiedName("io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge")
                .should().resideInAPackage("..core.application.upload..")
                .check(CORE);
    }

    @Test
    void domainDependsOnlyOnTheDomainAndJava() {
        classes().that().resideInAPackage("..core.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..core.domain..")
                .check(CORE);
    }

    @Test
    void inboundPortsDependOnlyOnDomainAndTheirOwnContracts() {
        classes().that().resideInAPackage("..core.port.in..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..core.domain..", "..core.port.in..")
                .check(CORE);
    }

    @Test
    void outboundPortsDependOnlyOnDomainAndTheirOwnContracts() {
        classes().that().resideInAPackage("..core.port.out..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..core.domain..", "..core.port.out..")
                .check(CORE);
    }

    @Test
    void applicationDependsOnlyOnDomainPortsAndJava() {
        classes().that().resideInAPackage("..core.application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..core.domain..",
                        "..core.port.in..", "..core.port.out..", "..core.application..")
                .check(CORE);
    }
}
