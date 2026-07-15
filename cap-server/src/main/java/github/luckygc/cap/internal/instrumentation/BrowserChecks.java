package github.luckygc.cap.internal.instrumentation;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToIntFunction;

/** 构造 capjs-core 0.1.1 的浏览器自动化标记抽样检查。 */
final class BrowserChecks {

    private static final String[] WINDOW_PROP_MARKERS = {
        "_Selenium_IDE_Recorder",
        "_selenium",
        "calledSelenium",
        "__webdriverFunc",
        "__lastWatirAlert",
        "__lastWatirConfirm",
        "__lastWatirPrompt",
        "_WEBDRIVER_ELEM_CACHE",
        "ChromeDriverw",
        "awesomium",
        "CefSharp",
        "RunPerfTest",
        "fmget_targets",
        "geb",
        "spawn",
        "domAutomation",
        "domAutomationController",
        "wdioElectron",
        "callPhantom",
        "_phantom",
        "__nightmare",
        "nightmare",
        "__playwright__binding__",
        "__pwInitScripts"
    };
    private static final String[] DOC_PROP_MARKERS = {
        "__selenium_evaluate",
        "selenium-evaluate",
        "__selenium_unwrapped",
        "__webdriver_script_fn",
        "__driver_evaluate",
        "__webdriver_evaluate",
        "__fxdriver_evaluate",
        "__driver_unwrapped",
        "__webdriver_unwrapped",
        "__fxdriver_unwrapped",
        "__webdriver_script_func",
        "__webdriver_script_function"
    };
    private static final String[] ATTR_SUBSTRING_MARKERS = {"selenium", "webdriver", "driver"};
    private static final String[] STACK_SUBSTRING_MARKERS = {
        "pptr:", "UtilityScript.", "PhantomJS"
    };
    private static final String[] WINDOW_PREFIX_MARKERS = {"puppeteer_", "cdc_", "$cdc_"};
    private static final String[] UA_TOKEN_MARKERS = {
        "HeadlessChrome", "PhantomJS", "SlimerJS", "headless"
    };

    private BrowserChecks() {}

    static String build(
            String blocked,
            String id,
            String hashFunction,
            String hashSet,
            ToIntFunction<String> hash,
            SecureRandom random) {
        String winHashes = hashes(WINDOW_PROP_MARKERS, hash);
        String docHashes = hashes(DOC_PROP_MARKERS, hash);
        String attrHashes = hashes(ATTR_SUBSTRING_MARKERS, hash);
        String stackHashes = hashes(STACK_SUBSTRING_MARKERS, hash);
        String prefixHashes = hashes(WINDOW_PREFIX_MARKERS, hash);
        String uaHashes = hashes(UA_TOKEN_MARKERS, hash);
        List<String> checks = new ArrayList<>();

        checks.add(
                "if (!%1$s) { try { var d = Object.getOwnPropertyDescriptors(navigator); var __wh = %2$s; for (const k in d) { if (%3$s(k) === __wh) { %1$s = true; break; } } if (!%1$s) { var p = Object.getPrototypeOf(navigator); while (p && !%1$s) { for (const k of Object.getOwnPropertyNames(p)) { if (%3$s(k) === __wh) { try { if (navigator[k]) %1$s = true; } catch {} break; } } p = Object.getPrototypeOf(p); } } } catch { %1$s = true; } }"
                        .formatted(blocked, unsigned(hash.applyAsInt("webdriver")), hashFunction));
        checks.add(
                "if (!%1$s && Object.getOwnPropertyNames(navigator).length !== 0) %1$s = true;"
                        .formatted(blocked));

        String value = variable(random);
        checks.add(
                "if (!%1$s) { var %2$s = %3$s; for (const k of Object.getOwnPropertyNames(window)) { for (var pl = 4; pl <= 5; pl++) { if (%4$s(%2$s, %5$s(k.slice(0, pl)))) { %1$s = true; break; } } if (%1$s) break; } }"
                        .formatted(blocked, value, prefixHashes, hashSet, hashFunction));
        value = variable(random);
        checks.add(
                "if (!%1$s) { var %2$s = %3$s; for (const k of Object.getOwnPropertyNames(window)) { if (%4$s(%2$s, %5$s(k))) { %1$s = true; break; } } }"
                        .formatted(blocked, value, winHashes, hashSet, hashFunction));
        value = variable(random);
        checks.add(
                "if (!%1$s) { var %2$s = %3$s; for (const k of Object.getOwnPropertyNames(document)) { if (%4$s(%2$s, %5$s(k))) { %1$s = true; break; } } }"
                        .formatted(blocked, value, docHashes, hashSet, hashFunction));
        value = variable(random);
        checks.add(
                "if (!%1$s) { try { var %2$s = %3$s; var an = document.documentElement.getAttributeNames(); for (const n of an) { for (const t of n.split(/[^a-z]+/i)) { if (t && %4$s(%2$s, %5$s(t.toLowerCase()))) { %1$s = true; break; } } if (%1$s) break; } } catch { %1$s = true; } }"
                        .formatted(blocked, value, attrHashes, hashSet, hashFunction));

        value = variable(random);
        String stack = variable(random);
        checks.add(
                "if (!%1$s) { try { var %2$s = %3$s; var %4$s = (new Error()).stack || ''; for (var i = 0; i + 5 <= %4$s.length; i++) { for (var sl = 5; sl <= 14; sl++) { if (i + sl > %4$s.length) break; if (%5$s(%2$s, %6$s(%4$s.substr(i, sl)))) { %1$s = true; break; } } if (%1$s) break; } } catch {} }"
                        .formatted(blocked, value, stackHashes, stack, hashSet, hashFunction));
        checks.add(
                "if (!%1$s) { try { if (typeof window.exposedFn !== 'undefined') { var s = window.exposedFn.toString(); for (var i = 0; i + 19 <= s.length; i++) { if (%2$s(s.substr(i, 19)) === %3$s) { %1$s = true; break; } } } } catch {} }"
                        .formatted(
                                blocked,
                                hashFunction,
                                unsigned(hash.applyAsInt("exposeBindingHandle"))));
        checks.add(
                "if (!%1$s && typeof window.process !== 'undefined') { try { if (%2$s(window.process.type || '') === %3$s || (window.process.versions && window.process.versions.electron)) %1$s = true; } catch { %1$s = true; } }"
                        .formatted(blocked, hashFunction, unsigned(hash.applyAsInt("renderer"))));

        value = variable(random);
        checks.add(
                "if (!%1$s) { try { var %2$s = %3$s; var ua = navigator.userAgent || ''; for (const t of ua.split(/[\\s/(),;]/)) { if (t && %4$s(%2$s, %5$s(t))) { %1$s = true; break; } } if (!%1$s) { var av = navigator.appVersion || ''; for (const t of av.split(/[\\s/(),;]/)) { if (t && %4$s(%2$s, %5$s(t))) { %1$s = true; break; } } } } catch {} }"
                        .formatted(blocked, value, uaHashes, hashSet, hashFunction));
        checks.add(
                "if (!%1$s) { try { var c = document.createElement('canvas').getContext('webgl'); if (c) { var v = c.getParameter(c.VENDOR); var r = c.getParameter(c.RENDERER); if (%2$s(v || '') === %3$s && %2$s(r || '') === %4$s) %1$s = true; } } catch {} }"
                        .formatted(
                                blocked,
                                hashFunction,
                                unsigned(hash.applyAsInt("Brian Paul")),
                                unsigned(hash.applyAsInt("Mesa OffScreen"))));
        checks.add(
                "if (!%1$s) { try { if (document.hasFocus && document.hasFocus() && window.outerWidth === 0 && window.outerHeight === 0) %1$s = true; } catch { %1$s = true; } }"
                        .formatted(blocked));
        checks.add(
                "if (!%1$s) { try { var es = Function.prototype.toString.call(eval); var found = false; for (var i = 0; i + 13 <= es.length; i++) { if (%2$s(es.substr(i, 13)) === %3$s) { found = true; break; } } if (!found) %1$s = true; } catch {} }"
                        .formatted(
                                blocked, hashFunction, unsigned(hash.applyAsInt("[native code]"))));
        checks.add(
                "if (!%1$s) { try { if (typeof Function.prototype.bind === 'undefined') %1$s = true; } catch {} }"
                        .formatted(blocked));
        checks.add(
                "if (!%1$s) { try { if (window.external && typeof window.external.toString === 'function') { var s = window.external.toString(); for (var i = 0; i + 9 <= s.length; i++) { if (%2$s(s.substr(i, 9)) === %3$s) { %1$s = true; break; } } } } catch { %1$s = true; } }"
                        .formatted(blocked, hashFunction, unsigned(hash.applyAsInt("Sequentum"))));

        String ok = variable(random);
        String index = variable(random);
        checks.add(
                "if (!%1$s) { try { if (navigator.mimeTypes) { var %2$s = Object.getPrototypeOf(navigator.mimeTypes) === MimeTypeArray.prototype; for (var %3$s = 0; %3$s < navigator.mimeTypes.length && %2$s; %3$s++) { %2$s = Object.getPrototypeOf(navigator.mimeTypes[%3$s]) === MimeType.prototype; } if (!%2$s) %1$s = true; } } catch {} }"
                        .formatted(blocked, ok, index));

        String ua = variable(random);
        String productSub = variable(random);
        checks.add(
                "if (!%1$s) { try { var %2$s = navigator.productSub; var %3$s = navigator.userAgent || ''; if (%2$s && %4$s(%2$s) !== %5$s) { var likeBlink = false; for (const t of %3$s.toLowerCase().split(/[\\s/(),;]/)) { var hh = %4$s(t); if (hh === %6$s || hh === %7$s || hh === %8$s) { likeBlink = true; break; } } if (likeBlink) %1$s = true; } } catch {} }"
                        .formatted(
                                blocked,
                                productSub,
                                ua,
                                hashFunction,
                                unsigned(hash.applyAsInt("20030107")),
                                unsigned(hash.applyAsInt("chrome")),
                                unsigned(hash.applyAsInt("safari")),
                                unsigned(hash.applyAsInt("opera"))));

        value = variable(random);
        checks.add(
                "if (!%1$s) { try { var %2$s = Object.getOwnPropertyNames(window); for (const n of %2$s) { var u = n.lastIndexOf('_'); if (u > 3 && u < n.length - 1) { var suf = n.slice(u + 1); var hh = %3$s(suf); if (hh === %4$s || hh === %5$s || hh === %6$s) { %1$s = true; break; } } } } catch {} }"
                        .formatted(
                                blocked,
                                value,
                                hashFunction,
                                unsigned(hash.applyAsInt("Array")),
                                unsigned(hash.applyAsInt("Promise")),
                                unsigned(hash.applyAsInt("Symbol"))));

        shuffle(checks, random);
        return "let %1$s = false;try {%2$s} catch {%1$s = true} if (%1$s) {parent.postMessage({ type: 'cap:instr', nonce: \"%3$s\", result: '', blocked: true }, '*');return;}"
                .formatted(blocked, String.join("", checks.subList(0, 8)), id);
    }

    static String variable(SecureRandom random) {
        int length = 4 + (int) (random.nextDouble() * 7);
        return variable(random, length);
    }

    static String variable(SecureRandom random, int length) {
        String letters = "abcdefghijklmnopqrstuvwxyz";
        String characters = letters + "0123456789";
        StringBuilder value = new StringBuilder(length);
        value.append(letters.charAt((int) (random.nextDouble() * letters.length())));
        for (int index = 1; index < length; index++) {
            value.append(characters.charAt((int) (random.nextDouble() * characters.length())));
        }
        return value.toString();
    }

    static <T> void shuffle(List<T> values, SecureRandom random) {
        for (int index = values.size() - 1; index > 0; index--) {
            int other = (int) (random.nextDouble() * (index + 1));
            Collections.swap(values, index, other);
        }
    }

    private static String hashes(String[] values, ToIntFunction<String> hash) {
        List<String> hashes = new ArrayList<>(values.length);
        for (String value : values) {
            hashes.add(unsigned(hash.applyAsInt(value)));
        }
        return "[" + String.join(",", hashes) + "]";
    }

    private static String unsigned(int value) {
        return Long.toString(Integer.toUnsignedLong(value));
    }
}
