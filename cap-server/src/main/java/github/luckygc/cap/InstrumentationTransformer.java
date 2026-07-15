package github.luckygc.cap;

/**
 * 同步转换 library 生成的 instrumentation JavaScript。
 *
 * <p>实现会在调用线程中运行并能看到完整脚本，包括 nonce 相关内容。同一 {@link Cap}
 * 可被并发调用，实现必须可信且线程安全；其阻塞和外部副作用由调用方负责。本库不提供超时、内存限制或 JVM sandbox，只会处理实现抛出的异常并校验返回值与大小。
 */
@FunctionalInterface
public interface InstrumentationTransformer {

    /** 在调用线程中转换完整脚本；实现必须是可信、同步、线程安全且有界的。 */
    String transform(String script, int level);
}
