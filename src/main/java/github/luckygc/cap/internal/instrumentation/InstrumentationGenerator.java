package github.luckygc.cap.internal.instrumentation;

import github.luckygc.cap.InstrumentationOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** 生成 capjs-core 0.1.1 兼容的浏览器 instrumentation challenge。 */
public final class InstrumentationGenerator {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final Duration MAX_TTL = Duration.ofDays(1);
    private static final int VARIABLE_COUNT = 4;
    private static final int OPERATION_COUNT = 20;
    private static final int MAX_SCRIPT_BYTES = 262_144;
    private static final int MAX_COMPRESSED_BYTES = 262_144;
    private static final int MAX_BLOB_CHARACTERS = 349_528;
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom random;
    private final Clock clock;

    public InstrumentationGenerator() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    InstrumentationGenerator(SecureRandom random, Clock clock) {
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 使用上游默认五分钟 TTL 生成 challenge。 */
    public GeneratedInstrumentation generate(InstrumentationOptions options) {
        return generate(options, DEFAULT_TTL);
    }

    /** 使用调用方 challenge TTL 生成 challenge。 */
    public GeneratedInstrumentation generate(InstrumentationOptions options, Duration ttl) {
        Objects.requireNonNull(options, "options");
        validateTtl(ttl);
        String id = randomHex(16);
        List<String> variables = new ArrayList<>(VARIABLE_COUNT);
        for (int index = 0; index < VARIABLE_COUNT; index++) {
            variables.add(BrowserChecks.variable(random, 12));
        }
        int[] initial = {
            fastRandom(10, 250), fastRandom(10, 250), fastRandom(10, 250), fastRandom(10, 250)
        };
        int[] values = initial.clone();
        int correctKey = fastRandom(1_000, 9_000);
        int badKey;
        do {
            badKey = fastRandom(1_000, 9_000);
        } while (badKey == correctKey);
        values[0] ^= correctKey;

        String functionHelper = BrowserChecks.variable(random);
        String domHelper = BrowserChecks.variable(random);
        StringBuilder equations = new StringBuilder(8_192);
        equations.append(
                "function %1$s(a,b,c){function F(d){this.v=function(){return this.k^d;}};var p={k:c};var i=new F(a);i.k=b;F.prototype=p;return i.v()|(new F(b)).v();}"
                        .formatted(functionHelper));
        equations.append(
                "function %1$s(x,y,z){var d=document.createElement('div');d.style.display='none';document.body.appendChild(d);function A(p,v){for(var i=0;i<8;i++){var c=document.createElement('div');p.appendChild(c);c.innerText=v;if((v&1)==0)p=c;v=v>>1;}return p;}function B(n,r,s){if(!n||n==r)return s%%256;while(n.children.length>0)n.removeChild(n.lastElementChild);return B(n.parentNode,r,s+parseInt(n.innerText));}var s=B(A(A(A(d,x),y),z),d,0);d.parentNode.removeChild(d);return s;}"
                        .formatted(domHelper));
        equations.append(
                "%1$s = %1$s ^ (navigator.userAgent ? %2$d : %3$d);"
                        .formatted(variables.get(0), correctKey, badKey));

        for (int index = 0; index < OPERATION_COUNT; index++) {
            appendOperation(equations, variables, values, functionHelper, domHelper);
        }
        for (int index = 0; index < VARIABLE_COUNT; index++) {
            int salt = fastRandom(100_000, 999_999);
            equations.append(
                    "%1$s=((%1$s^%2$d)&0x7FFFFFFF)%%900000+100000;"
                            .formatted(variables.get(index), salt));
            values[index] = ((values[index] ^ salt) & 0x7fffffff) % 900_000 + 100_000;
        }

        String script =
                buildClientScript(
                        id,
                        variables,
                        initial,
                        equations.toString(),
                        options.blockAutomatedBrowsers());
        String transformed;
        try {
            transformed = options.transformer().transform(script, options.level());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("instrumentation transformer failed", exception);
        }
        if (transformed == null) {
            throw new IllegalStateException("instrumentation transformer returned null");
        }
        byte[] scriptBytes = transformed.getBytes(StandardCharsets.UTF_8);
        if (scriptBytes.length == 0 || scriptBytes.length > MAX_SCRIPT_BYTES) {
            throw new IllegalStateException("instrumentation transformer output exceeds limit");
        }
        byte[] compressed = deflateRaw(scriptBytes);
        if (compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IllegalStateException("compressed instrumentation exceeds limit");
        }
        String blob = Base64.getEncoder().encodeToString(compressed);
        if (blob.length() > MAX_BLOB_CHARACTERS) {
            throw new IllegalStateException("instrumentation blob exceeds limit");
        }
        List<Integer> expected = List.of(values[0], values[1], values[2], values[3]);
        long expires = Math.addExact(clock.millis(), ttl.toMillis());
        return new GeneratedInstrumentation(
                id, expires, expected, variables, options.blockAutomatedBrowsers(), blob);
    }

    private void appendOperation(
            StringBuilder equations,
            List<String> variables,
            int[] values,
            String functionHelper,
            String domHelper) {
        int operation = fastRandom(0, 5);
        int destination = fastRandom(0, 3);
        int source1 = fastRandom(0, 3);
        int source2 = fastRandom(0, 3);
        int source3 = fastRandom(0, 3);
        String dest = variables.get(destination);
        String src1 = variables.get(source1);
        String src2 = variables.get(source2);
        String src3 = variables.get(source3);
        switch (operation) {
            case 0 -> {
                equations.append("%1$s = ~(%1$s & %2$s);".formatted(dest, src1));
                values[destination] = ~(values[destination] & values[source1]);
            }
            case 1 -> {
                equations.append("%1$s = %1$s ^ %2$s;".formatted(dest, src1));
                values[destination] ^= values[source1];
            }
            case 2 -> {
                equations.append("%1$s = %1$s | %2$s;".formatted(dest, src1));
                values[destination] |= values[source1];
            }
            case 3 -> {
                equations.append("%1$s = %1$s & %2$s;".formatted(dest, src1));
                values[destination] &= values[source1];
            }
            case 4 -> {
                equations.append(
                        "%1$s = %2$s(%3$s, %4$s, %1$s);"
                                .formatted(dest, functionHelper, src1, src2));
                values[destination] =
                        (values[source2] ^ values[source1])
                                | (values[destination] ^ values[source2]);
            }
            default -> {
                equations.append(
                        "%1$s = %2$s(%3$s, %4$s, %5$s);"
                                .formatted(dest, domHelper, src1, src2, src3));
                values[destination] = domSumMock(values[source1], values[source2], values[source3]);
            }
        }
    }

    private String buildClientScript(
            String id,
            List<String> variables,
            int[] initial,
            String equations,
            boolean blockAutomatedBrowsers) {
        int seed = random.nextInt(1, 0x7fffffff);
        ToIntFunction<String> hash = value -> hashWith(seed, value);
        String hashFunction = BrowserChecks.variable(random);
        String hashSet = BrowserChecks.variable(random);
        String evalLocal = BrowserChecks.variable(random);
        int evalSecret = random.nextInt(1_000_000, 0x7fffffff);
        String evalA = BrowserChecks.variable(random);
        String evalB = BrowserChecks.variable(random);
        String evalC = BrowserChecks.variable(random);
        String helpers =
                "function %1$s(s){let h=%2$d>>>0;for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=(h+(h<<1)+(h<<4)+(h<<7)+(h<<8)+(h<<24))>>>0;}return h>>>0;}"
                                .formatted(hashFunction, seed)
                        + "function %1$s(a,v){for(var i=0;i<a.length;i++)if(a[i]===v)return true;return false;}"
                                .formatted(hashSet);
        String blockChecks =
                blockAutomatedBrowsers
                        ? BrowserChecks.build(
                                BrowserChecks.variable(random),
                                id,
                                hashFunction,
                                hashSet,
                                hash,
                                random)
                        : "";
        String resultKey = BrowserChecks.variable(random);
        String namesKey = BrowserChecks.variable(random);
        String outputKey = BrowserChecks.variable(random);
        List<String> environment =
                environmentChecks(
                        namesKey,
                        hashFunction,
                        hashSet,
                        hash,
                        evalLocal,
                        evalSecret,
                        evalA,
                        evalB,
                        evalC);
        BrowserChecks.shuffle(environment, random);

        return "(function(){window.onload=async function(){try {%1$sconst %2$s=await (async function(){%3$s%4$s\nvar %5$s=%6$d;var %7$s=%8$d;var %9$s=%10$d;var %11$s=%12$d;%13$s\nvar %14$s={};%14$s[\"%5$s\"]=%5$s;%14$s[\"%7$s\"]=%7$s;%14$s[\"%9$s\"]=%9$s;%14$s[\"%11$s\"]=%11$s;return %14$s;})();if (!%2$s || typeof %2$s !== 'object') return;parent.postMessage({type: 'cap:instr',nonce:\"%15$s\",result:{i:\"%15$s\",state:%2$s,ts:Date.now()}},'*');} catch {}};})();"
                .formatted(
                        helpers,
                        resultKey,
                        String.join("", environment),
                        blockChecks,
                        variables.get(0),
                        initial[0],
                        variables.get(1),
                        initial[1],
                        variables.get(2),
                        initial[2],
                        variables.get(3),
                        initial[3],
                        equations,
                        outputKey,
                        id);
    }

    private static List<String> environmentChecks(
            String key,
            String hashFunction,
            String hashSet,
            ToIntFunction<String> hash,
            String evalLocal,
            int evalSecret,
            String evalA,
            String evalB,
            String evalC) {
        List<String> checks = new ArrayList<>();
        checks.add(
                "try { const %1$sst = (new Error()).stack || ''; if (%1$sst.indexOf('node:internal') !== -1 || %1$sst.indexOf('moduleEvaluation') !== -1 || %1$sst.indexOf('loadAndEvaluateModule') !== -1 || %1$sst.indexOf('file:///') !== -1 || %1$sst.indexOf('[eval]') !== -1 || /\\(native:/.test(%1$sst)) return null; } catch { return null }"
                        .formatted(key));
        checks.add(
                "if (typeof HTMLElement !== 'function' || typeof Window !== 'function' || typeof Document !== 'function' || typeof Navigator !== 'function' || typeof Node !== 'function') return null; if (!(navigator instanceof Navigator) || !(document instanceof Document) || !(window instanceof Window) || !(document.body instanceof HTMLElement)) return null; if (globalThis !== window || window.self !== window || document.defaultView !== window) return null;");
        checks.add(
                "try { const %1$sots = Object.prototype.toString; if (%2$s(%1$sots.call(navigator)) !== %3$s || %2$s(%1$sots.call(window)) !== %4$s || %2$s(%1$sots.call(document)) !== %5$s) return null; } catch { return null }"
                        .formatted(
                                key,
                                hashFunction,
                                unsigned(hash.applyAsInt("[object Navigator]")),
                                unsigned(hash.applyAsInt("[object Window]")),
                                unsigned(hash.applyAsInt("[object HTMLDocument]"))));
        checks.add(
                "try { if (typeof EventTarget !== 'function' || !(document.body instanceof EventTarget) || !(window instanceof EventTarget)) return null; const %1$sprobe = document.createElement('div'); let %1$sfired = 0; const %1$sev = '_c' + (Date.now() & 0xffff).toString(36); const %1$sh = (e) => { if (e && e.detail === 0xc0de) %1$sfired++; }; %1$sprobe.addEventListener(%1$sev, %1$sh); %1$sprobe.dispatchEvent(new CustomEvent(%1$sev, { detail: 0xc0de })); %1$sprobe.dispatchEvent(new CustomEvent(%1$sev, { detail: 0xc0de })); %1$sprobe.removeEventListener(%1$sev, %1$sh); %1$sprobe.dispatchEvent(new CustomEvent(%1$sev, { detail: 0xc0de })); if (%1$sfired !== 2) return null; } catch { return null }"
                        .formatted(key));
        checks.add(
                "try { const %1$sgf = new Function('return this'); const %1$stg = %1$sgf(); if (%1$stg !== globalThis) return null; const %1$sleakHashes = %2$s; for (const %1$sk of Object.getOwnPropertyNames(%1$stg)) { if (%3$s(%1$sleakHashes, %4$s(%1$sk))) return null; } const %1$sfnArgs = new Function('a','b','c','return a+b+c'); if (%1$sfnArgs.length !== 3) return null; if (%1$sfnArgs(10,20,30) !== 60) return null; const %1$ssrc = Function.prototype.toString.call(%1$sfnArgs); if (%1$ssrc.indexOf('return a+b+c') === -1) return null; if (%1$ssrc.indexOf('apply') !== -1 || %1$ssrc.indexOf('callArgs') !== -1 || %1$ssrc.indexOf('Reflect.') !== -1) return null; let %1$sthrown = null; try { new Function('throw new Error(\"x\")')(); } catch (%1$se) { %1$sthrown = %1$se; } if (!%1$sthrown || !%1$sthrown.stack) return null; const %1$sst2 = %1$sthrown.stack; if (%1$sst2.indexOf('node:internal') !== -1 || %1$sst2.indexOf('moduleEvaluation') !== -1 || %1$sst2.indexOf('file:///') !== -1 || %1$sst2.indexOf('[eval]') !== -1 || /\\(native:/.test(%1$sst2)) return null; } catch { return null }"
                        .formatted(
                                key,
                                hashes(
                                        hash,
                                        "Bun",
                                        "process",
                                        "module",
                                        "require",
                                        "global",
                                        "__dirname",
                                        "Deno"),
                                hashSet,
                                hashFunction));
        checks.add(
                "try { const %1$sie = (0, eval); const %1$seg = %1$sie('this'); if (%1$seg !== globalThis) return null; const %1$sleak2 = %2$s; for (const %1$sk of Object.getOwnPropertyNames(%1$seg)) { if (%3$s(%1$sleak2, %4$s(%1$sk))) return null; } } catch { return null }"
                        .formatted(
                                key,
                                hashes(
                                        hash,
                                        "Bun",
                                        "process",
                                        "require",
                                        "global",
                                        "__dirname",
                                        "Deno"),
                                hashSet,
                                hashFunction));
        String first = evalLocal.substring(0, 2);
        String rest = evalLocal.substring(2);
        checks.add(
                "try { var %1$s = %2$d; var %3$s = '%4$s'; var %5$s = '%6$s'; var %7$s = %3$s + %5$s; var %1$sr1 = (0, eval)('typeof ' + %7$s); if (%1$sr1 !== 'undefined') return null; var %1$sr2 = eval(%7$s); if (%1$sr2 !== %2$d) return null; var %1$sr3 = eval(%3$s + %5$s + '+1'); if (%1$sr3 !== %8$d) return null; var %1$sarr = ['(', '(', ')', '=', '>', 't', 'h', 'i', 's', ')', '(', ')']; var %1$sarrow = (0, eval)(%1$sarr.join('')); if (%1$sarrow !== globalThis) return null; var %1$sr4 = eval('(function(){return ' + %7$s + '*2;})()'); if (%1$sr4 !== %9$d) return null; } catch { return null }"
                        .formatted(
                                evalLocal,
                                evalSecret,
                                evalA,
                                first,
                                evalB,
                                rest,
                                evalC,
                                evalSecret + 1,
                                (long) evalSecret * 2));
        return checks;
    }

    private static int hashWith(int seed, String value) {
        int hash = seed;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash += (hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24);
        }
        return hash;
    }

    private static int domSumMock(int x, int y, int z) {
        MockNode root = new MockNode(null, 0);
        MockNode node = buildChain(root, x);
        node = buildChain(node, y);
        node = buildChain(node, z);
        long sum = 0;
        while (node != null && node != root) {
            sum += node.value;
            node = node.parent;
        }
        return (int) (sum % 256);
    }

    private static MockNode buildChain(MockNode parent, int value) {
        MockNode current = parent;
        int remaining = value;
        for (int index = 0; index < 8; index++) {
            MockNode child = new MockNode(current, remaining);
            if ((remaining & 1) == 0) {
                current = child;
            }
            remaining >>= 1;
        }
        return current;
    }

    private int fastRandom(int minimum, int maximum) {
        return minimum + (int) (random.nextDouble() * (maximum - minimum + 1));
    }

    private String randomHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private static byte[] deflateRaw(byte[] script) {
        Deflater deflater = new Deflater(1, true);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                DeflaterOutputStream compressed = new DeflaterOutputStream(output, deflater)) {
            compressed.write(script);
            compressed.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法压缩 instrumentation", exception);
        } finally {
            deflater.end();
        }
    }

    private static void validateTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative() || ttl.toMillis() < 1 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException(
                    "instrumentation TTL must be between 1 ms and 1 day");
        }
    }

    private static String hashes(ToIntFunction<String> hash, String... values) {
        List<String> encoded = new ArrayList<>(values.length);
        for (String value : values) {
            encoded.add(unsigned(hash.applyAsInt(value)));
        }
        return "[" + String.join(",", encoded) + "]";
    }

    private static String unsigned(int value) {
        return Long.toString(Integer.toUnsignedLong(value));
    }

    private record MockNode(MockNode parent, int value) {}

    public record GeneratedInstrumentation(
            String id,
            long expires,
            List<Integer> expectedVals,
            List<String> vars,
            boolean blockAutomatedBrowsers,
            String instrumentation) {

        public GeneratedInstrumentation {
            Objects.requireNonNull(id, "id");
            expectedVals = List.copyOf(expectedVals);
            vars = List.copyOf(vars);
            Objects.requireNonNull(instrumentation, "instrumentation");
        }
    }
}
