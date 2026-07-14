package github.luckygc.cap.internal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/** 仅用于验证库运行时日志内容的测试范围 SLF4J provider。 */
public final class CapturingLoggerProvider implements SLF4JServiceProvider {

    private static final List<String> MESSAGES = new CopyOnWriteArrayList<>();
    private static final Logger LOGGER = new CapturingLogger();
    private static final ILoggerFactory LOGGER_FACTORY = ignored -> LOGGER;
    private static final IMarkerFactory MARKER_FACTORY = new BasicMarkerFactory();
    private static final MDCAdapter MDC_ADAPTER = new NOPMDCAdapter();

    static void reset() {
        MESSAGES.clear();
    }

    static List<String> messages() {
        return List.copyOf(MESSAGES);
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return LOGGER_FACTORY;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return MARKER_FACTORY;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return MDC_ADAPTER;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {}

    private static final class CapturingLogger extends AbstractLogger {

        private static final long serialVersionUID = 1L;

        private CapturingLogger() {
            name = "capture";
        }

        @Override
        public boolean isTraceEnabled() {
            return true;
        }

        @Override
        public boolean isTraceEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public boolean isDebugEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled(Marker marker) {
            return true;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return CapturingLoggerProvider.class.getName();
        }

        @Override
        protected void handleNormalizedLoggingCall(
                Level level,
                Marker marker,
                String messagePattern,
                Object[] arguments,
                Throwable throwable) {
            MESSAGES.add(
                    level + " " + MessageFormatter.basicArrayFormat(messagePattern, arguments));
        }
    }
}
