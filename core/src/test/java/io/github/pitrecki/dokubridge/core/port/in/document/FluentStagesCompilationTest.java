package io.github.pitrecki.dokubridge.core.port.in.document;

import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.*;

class FluentStagesCompilationTest {
    @TempDir Path directory;

    @Test
    void publicFlowCompilesWithOnlyTheCoreOnTheClasspath() throws Exception {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        assertThat(compile("""
                var saved = bridge.upload(bytes).toOwner("Invoice", "42")
                        .metadata("category", "invoice").requestId(id).save();
                var version = saved.result();
                var document = saved.and();
                Map<String, String> metadata = Map.of("category", "invoice");
                document.upload(bytes).save().and()
                        .replaceMetadata(metadata).atRevision(0).save().and()
                        .archive().and().audit();
                bridge.upload(bytes).toOwner("Invoice", "42").saveAndThen(doc -> {
                    doc.upload(bytes).saveAndThen(next -> {
                        try (var input = next.content()) { input.readAllBytes(); }
                    });
                    doc.replaceMetadata(metadata).atRevision(0).saveAndThen(updated -> updated.archive());
                });
                document.versions();
                document.delete();
                """, diagnostics)).withFailMessage(diagnostics.getDiagnostics().toString()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("incompleteFlows")
    void requiredStagesCannotBeSkipped(String statement, String unavailableMethod) throws Exception {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        assertThat(compile(statement, diagnostics)).isFalse();
        assertThat(diagnostics.getDiagnostics()).anySatisfy(error -> {
            assertThat(error.getKind()).isEqualTo(Diagnostic.Kind.ERROR);
            assertThat(error.getMessage(Locale.ROOT)).contains(unavailableMethod);
        });
    }

    static Stream<Arguments> incompleteFlows() {
        return Stream.of(
                Arguments.of("bridge.upload(bytes).save();", "save"),
                Arguments.of("bridge.upload(bytes).saveAndThen(doc -> {});", "saveAndThen"),
                Arguments.of("bridge.upload(bytes).toOwner(\"Invoice\", \"42\").and();", "and"),
                Arguments.of("bridge.document(id).replaceMetadata(Map.of()).save();", "save"),
                Arguments.of("bridge.document(id).replaceMetadata(Map.of()).saveAndThen(doc -> {});", "saveAndThen"),
                Arguments.of("bridge.document(id).upload(bytes).metadata(\"key\", \"value\");", "metadata"));
    }

    private boolean compile(String body, DiagnosticCollector<JavaFileObject> diagnostics) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("A full JDK is required for the public API compilation check").isNotNull();
        var source = directory.resolve("Example.java");
        Files.writeString(source, """
                import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
                import java.util.Map;
                import java.util.UUID;
                import java.io.IOException;
                class Example {
                    void use(DocumentBridge bridge, byte[] bytes, UUID id) throws IOException {
                """ + body + "\n}\n}\n");
        var core = Path.of(DocumentBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            return compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "--release", "21", "-classpath", core.toString(), "-d", directory.toString()),
                    null, files.getJavaFileObjects(source)).call();
        }
    }
}
