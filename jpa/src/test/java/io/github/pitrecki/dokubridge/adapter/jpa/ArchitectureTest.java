package io.github.pitrecki.dokubridge.adapter.jpa;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class ArchitectureTest {
    @Test
    void repositoriesAndPersistenceMappingStayInTheirFeaturePackages() {
        var adapter = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.pitrecki.dokubridge.adapter.jpa");
        classes().that().haveSimpleNameStartingWith("Jpa")
                .should().resideInAnyPackage("..adapter.jpa.document", "..adapter.jpa.version",
                        "..adapter.jpa.upload", "..adapter.jpa.audit")
                .check(adapter);
        classes().that().haveSimpleNameNotStartingWith("Jpa")
                .should().resideInAnyPackage("..adapter.jpa.document.persistence..", "..adapter.jpa.version.persistence..",
                        "..adapter.jpa.upload.persistence..", "..adapter.jpa.audit.persistence..")
                .check(adapter);
    }

    @Test
    void adapterUsesDomainAndOutboundPortsWithoutDependingOnApplicationOrInboundPorts() {
        var adapter = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.pitrecki.dokubridge.adapter.jpa");
        classes().should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "..core.domain..",
                        "..core.port.out..",
                        "..adapter.jpa..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "org.springframework..",
                        "com.fasterxml.jackson..")
                .check(adapter);
    }
}
