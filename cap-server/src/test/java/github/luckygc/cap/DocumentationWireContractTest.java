package github.luckygc.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("接入文档 Web wire 契约测试")
class DocumentationWireContractTest {

    @Test
    @DisplayName("README 显式适配上游 snake_case 与 Java camelCase")
    void readmeDocumentsWireAdapterBoundary() throws IOException {
        String readme = Files.readString(RepositoryPaths.root().resolve("README.md"));
        String compatibility =
                Files.readString(RepositoryPaths.root().resolve("docs/protocol-compatibility.md"));

        assertThat(readme)
                .contains(
                        "record RedeemWireRequest(",
                        "boolean instr_blocked",
                        "boolean instr_timeout",
                        "record Failure(",
                        "boolean instr_error",
                        "failure.instrError()")
                .doesNotContain("records 直接作为 Web DTO");
        assertThat(compatibility)
                .contains(
                        "Web wire 使用 snake_case",
                        "Java API 使用 camelCase",
                        "`instr_blocked`",
                        "`instr_timeout`",
                        "`instr_error`");
    }

    @Test
    @DisplayName("README Web adapter 可编译且映射全部请求响应字段")
    void compilesAndExecutesDocumentedWireAdapter() throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("cap-readme-adapter-");
        try {
            Path classes = Files.createDirectories(temporaryDirectory.resolve("classes"));
            Path controller = temporaryDirectory.resolve("CapController.java");
            Files.writeString(
                    controller,
                    firstJavaBlock(Files.readString(RepositoryPaths.root().resolve("README.md"))));
            List<Path> annotations = writeSpringAnnotationStubs(temporaryDirectory);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertThat(compiler).isNotNull();
            List<String> arguments =
                    List.of(
                            "--release",
                            "17",
                            "-classpath",
                            System.getProperty("java.class.path"),
                            "-d",
                            classes.toString(),
                            controller.toString(),
                            annotations.get(0).toString(),
                            annotations.get(1).toString(),
                            annotations.get(2).toString(),
                            annotations.get(3).toString());
            assertThat(compiler.run(null, null, null, arguments.toArray(String[]::new))).isZero();

            try (URLClassLoader loader =
                    new URLClassLoader(
                            new java.net.URL[] {classes.toUri().toURL()},
                            getClass().getClassLoader())) {
                assertRequestMapping(loader);
                assertResponseMapping(loader);
            }
        } finally {
            try (var paths = Files.walk(temporaryDirectory)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(DocumentationWireContractTest::delete);
            }
        }
    }

    private static void assertRequestMapping(ClassLoader loader) throws Exception {
        Class<?> instrumentationType = loader.loadClass("CapController$InstrumentationWireResult");
        Object instrumentation =
                instrumentationType
                        .getDeclaredConstructor(String.class, Map.class, Long.class)
                        .newInstance("fixture-id", Map.of("answer", 42L), 123L);
        Class<?> requestType = loader.loadClass("CapController$RedeemWireRequest");
        Constructor<?> constructor =
                requestType.getDeclaredConstructor(
                        String.class,
                        List.class,
                        instrumentationType,
                        boolean.class,
                        boolean.class);
        Object wire =
                constructor.newInstance("fixture-token", List.of(7L), instrumentation, true, false);
        Method adapter = requestType.getDeclaredMethod("toCapRequest");
        adapter.setAccessible(true);

        RedeemRequest mapped = (RedeemRequest) adapter.invoke(wire);

        assertThat(mapped.token()).isEqualTo("fixture-token");
        assertThat(mapped.solutions()).containsExactly(7L);
        assertThat(mapped.instrBlocked()).isTrue();
        assertThat(mapped.instrTimeout()).isFalse();
        assertThat(mapped.instr()).isNotNull();
        assertThat(mapped.instr().i()).isEqualTo("fixture-id");
        assertThat(mapped.instr().state()).containsEntry("answer", 42L);
        assertThat(mapped.instr().ts()).isEqualTo(123L);
    }

    private static void assertResponseMapping(ClassLoader loader) throws Exception {
        Class<?> responseType = loader.loadClass("CapController$RedeemWireResponse");
        Method adapter = responseType.getDeclaredMethod("from", RedeemResult.class);
        adapter.setAccessible(true);

        Object success =
                adapter.invoke(
                        null,
                        new RedeemResult.Success(
                                true, "business-token", "server-key", 456L, "login", 123L));
        assertRecord(
                success,
                Map.of(
                        "success",
                        true,
                        "token",
                        "business-token",
                        "tokenKey",
                        "server-key",
                        "expires",
                        456L,
                        "scope",
                        "login",
                        "iat",
                        123L));

        Object failure =
                adapter.invoke(null, new RedeemResult.Failure(false, "instr_timeout", true, null));
        assertRecord(
                failure, Map.of("success", false, "reason", "instr_timeout", "instr_error", true));
        assertThat(accessor(failure, "error")).isNull();
    }

    private static void assertRecord(Object record, Map<String, Object> expected) throws Exception {
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            assertThat(accessor(record, entry.getKey()))
                    .as(entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    private static Object accessor(Object record, String name) throws Exception {
        return record.getClass().getDeclaredMethod(name).invoke(record);
    }

    private static String firstJavaBlock(String markdown) {
        int start = markdown.indexOf("```java\n");
        int end = markdown.indexOf("\n```", start + 8);
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("README 缺少 Java 快速开始代码块");
        }
        return markdown.substring(start + 8, end) + System.lineSeparator();
    }

    private static List<Path> writeSpringAnnotationStubs(Path root) throws IOException {
        String packageName = "org.springframework.web.bind.annotation";
        Path directory = Files.createDirectories(root.resolve(packageName.replace('.', '/')));
        List<String> names =
                List.of("PostMapping", "RequestMapping", "RestController", "RequestBody");
        for (String name : names) {
            String target =
                    name.equals("RequestBody")
                            ? "PARAMETER"
                            : name.equals("RestController") ? "TYPE" : "METHOD";
            if (name.equals("RequestMapping")) {
                target = "TYPE";
            }
            Files.writeString(
                    directory.resolve(name + ".java"),
                    "package "
                            + packageName
                            + ";\nimport java.lang.annotation.*;\n@Target(ElementType."
                            + target
                            + ")\npublic @interface "
                            + name
                            + " { String value() default \"\"; }\n");
        }
        return names.stream().map(name -> directory.resolve(name + ".java")).toList();
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
