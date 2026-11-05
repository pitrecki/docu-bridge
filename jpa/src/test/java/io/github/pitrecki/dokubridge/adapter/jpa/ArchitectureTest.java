package io.github.pitrecki.dokubridge.adapter.jpa;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class ArchitectureTest {
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
