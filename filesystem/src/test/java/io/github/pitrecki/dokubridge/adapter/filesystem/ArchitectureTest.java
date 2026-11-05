package io.github.pitrecki.dokubridge.adapter.filesystem;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class ArchitectureTest {
    @Test
    void filesystemImplementationStaysInTheContentFeaturePackage() {
        var adapter = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.pitrecki.dokubridge.adapter.filesystem");
        classes().should().resideInAPackage("..adapter.filesystem.content..").check(adapter);
    }

    @Test
    void adapterUsesDomainAndOutboundPortsWithoutDependingOnApplicationOrInboundPorts() {
        var adapter = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.pitrecki.dokubridge.adapter.filesystem");
        classes().should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..",
                        "..core.domain..",
                        "..core.port.out..",
                        "..adapter.filesystem..")
                .check(adapter);
    }
}
