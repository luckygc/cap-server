package github.luckygc.cap;

@FunctionalInterface
public interface InstrumentationTransformer {

    String transform(String script, int level);
}
