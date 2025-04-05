package se.plilja;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class TomcatMetricsWithoutManager implements MeterBinder, AutoCloseable {
    private final MBeanServer mBeanServer;
    private final Iterable<Tag> tags;
    private final Set<NotificationListener> notificationListeners;
    private volatile String jmxDomain;

    public TomcatMetricsWithoutManager(Iterable<Tag> tags) {
        this(tags, getMBeanServer());
    }

    public TomcatMetricsWithoutManager(Iterable<Tag> tags, MBeanServer mBeanServer) {
        this.notificationListeners = ConcurrentHashMap.newKeySet();

        this.tags = tags;
        this.mBeanServer = mBeanServer;


    }

    public static void monitor(MeterRegistry registry, String... tags) {
        monitor(registry, (Iterable) Tags.of(tags));
    }

    public static void monitor(MeterRegistry registry, Iterable<Tag> tags) {
        (new TomcatMetricsWithoutManager(tags)).bindTo(registry);
    }

    public static MBeanServer getMBeanServer() {
        List<MBeanServer> mBeanServers = MBeanServerFactory.findMBeanServer((String) null);
        return !mBeanServers.isEmpty() ? (MBeanServer) mBeanServers.get(0) : ManagementFactory.getPlatformMBeanServer();
    }

    public void bindTo(MeterRegistry registry) {
        this.registerGlobalRequestMetrics(registry);
        this.registerServletMetrics(registry);
        this.registerCacheMetrics(registry);
        this.registerThreadPoolMetrics(registry);
    }

    private void registerThreadPoolMetrics(MeterRegistry registry) {
        this.registerMetricsEventually(":type=ThreadPool,name=*", (name, allTags) -> {
            Gauge.builder("tomcat.threads.config.max", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "maxThreads"))).tags(allTags).baseUnit("threads").register(registry);
            Gauge.builder("tomcat.threads.busy", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "currentThreadsBusy"))).tags(allTags).baseUnit("threads").register(registry);
            Gauge.builder("tomcat.threads.current", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "currentThreadCount"))).tags(allTags).baseUnit("threads").register(registry);
            Gauge.builder("tomcat.connections.current", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "connectionCount"))).tags(allTags).baseUnit("connections").register(registry);
            Gauge.builder("tomcat.connections.keepalive.current", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "keepAliveCount"))).tags(allTags).baseUnit("connections").register(registry);
            Gauge.builder("tomcat.connections.config.max", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "maxConnections"))).tags(allTags).baseUnit("connections").register(registry);
        });
    }

    private void registerCacheMetrics(MeterRegistry registry) {
        this.registerMetricsEventually(":type=StringCache", (name, allTags) -> {
            FunctionCounter.builder("tomcat.cache.access", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "accessCount"))).tags(allTags).register(registry);
            FunctionCounter.builder("tomcat.cache.hit", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "hitCount"))).tags(allTags).register(registry);
        });
    }

    private void registerServletMetrics(MeterRegistry registry) {
        this.registerMetricsEventually(":j2eeType=Servlet,name=*,*", (name, allTags) -> {
            FunctionCounter.builder("tomcat.servlet.error", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "errorCount"))).tags(allTags).register(registry);
            FunctionTimer.builder("tomcat.servlet.request", this.mBeanServer, (s) -> this.safeLong(() -> s.getAttribute(name, "requestCount")), (s) -> this.safeDouble(() -> s.getAttribute(name, "processingTime")), TimeUnit.MILLISECONDS).tags(allTags).register(registry);
            TimeGauge.builder("tomcat.servlet.request.max", this.mBeanServer, TimeUnit.MILLISECONDS, (s) -> this.safeDouble(() -> s.getAttribute(name, "maxTime"))).tags(allTags).register(registry);
        });
    }

    private void registerGlobalRequestMetrics(MeterRegistry registry) {
        this.registerMetricsEventually(":type=GlobalRequestProcessor,name=*", (name, allTags) -> {
            FunctionCounter.builder("tomcat.global.sent", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "bytesSent"))).tags(allTags).baseUnit("bytes").register(registry);
            FunctionCounter.builder("tomcat.global.received", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "bytesReceived"))).tags(allTags).baseUnit("bytes").register(registry);
            FunctionCounter.builder("tomcat.global.error", this.mBeanServer, (s) -> this.safeDouble(() -> s.getAttribute(name, "errorCount"))).tags(allTags).register(registry);
            FunctionTimer.builder("tomcat.global.request", this.mBeanServer, (s) -> this.safeLong(() -> s.getAttribute(name, "requestCount")), (s) -> this.safeDouble(() -> s.getAttribute(name, "processingTime")), TimeUnit.MILLISECONDS).tags(allTags).register(registry);
            TimeGauge.builder("tomcat.global.request.max", this.mBeanServer, TimeUnit.MILLISECONDS, (s) -> this.safeDouble(() -> s.getAttribute(name, "maxTime"))).tags(allTags).register(registry);
        });
    }

    private void registerMetricsEventually(final String namePatternSuffix, final BiConsumer<ObjectName, Iterable<Tag>> perObject) {
        if (this.getJmxDomain() != null) {
            Set<ObjectName> objectNames = this.mBeanServer.queryNames(this.getNamePattern(namePatternSuffix), (QueryExp) null);
            if (!objectNames.isEmpty()) {
                objectNames.forEach((objectName) -> perObject.accept(objectName, Tags.concat(this.tags, this.nameTag(objectName))));
                return;
            }
        }

        NotificationListener notificationListener = new NotificationListener() {
            public void handleNotification(Notification notification, Object handback) {
                MBeanServerNotification mBeanServerNotification = (MBeanServerNotification) notification;
                ObjectName objectName = mBeanServerNotification.getMBeanName();
                perObject.accept(objectName, Tags.concat(TomcatMetricsWithoutManager.this.tags, TomcatMetricsWithoutManager.this.nameTag(objectName)));
                if (!TomcatMetricsWithoutManager.this.getNamePattern(namePatternSuffix).isPattern()) {
                    try {
                        TomcatMetricsWithoutManager.this.mBeanServer.removeNotificationListener(MBeanServerDelegate.DELEGATE_NAME, this);
                        TomcatMetricsWithoutManager.this.notificationListeners.remove(this);
                    } catch (ListenerNotFoundException | InstanceNotFoundException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        };
        this.notificationListeners.add(notificationListener);
        NotificationFilter notificationFilter = (notification) -> {
            if (!"JMX.mbean.registered".equals(notification.getType())) {
                return false;
            } else {
                ObjectName objectName = ((MBeanServerNotification) notification).getMBeanName();
                return this.getNamePattern(namePatternSuffix).apply(objectName);
            }
        };

        try {
            this.mBeanServer.addNotificationListener(MBeanServerDelegate.DELEGATE_NAME, notificationListener, notificationFilter, (Object) null);
        } catch (InstanceNotFoundException e) {
            throw new RuntimeException("Error registering MBean listener", e);
        }
    }

    private ObjectName getNamePattern(String namePatternSuffix) {
        try {
            return new ObjectName(this.getJmxDomain() + namePatternSuffix);
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException("Error registering Tomcat JMX based metrics", e);
        }
    }

    private String getJmxDomain() {
        if (this.jmxDomain == null) {
            if (this.hasObjectName("Tomcat:type=Server")) {
                this.jmxDomain = "Tomcat";
            } else if (this.hasObjectName("Catalina:type=Server")) {
                this.jmxDomain = "Catalina";
            }
        }

        return this.jmxDomain;
    }

    public void setJmxDomain(String jmxDomain) {
        this.jmxDomain = jmxDomain;
    }

    private boolean hasObjectName(String name) {
        try {
            return this.mBeanServer.queryNames(new ObjectName(name), (QueryExp) null).size() == 1;
        } catch (MalformedObjectNameException ex) {
            throw new RuntimeException(ex);
        }
    }

    private double safeDouble(Callable<Object> callable) {
        try {
            return Double.parseDouble(callable.call().toString());
        } catch (Exception var3) {
            return Double.NaN;
        }
    }

    private long safeLong(Callable<Object> callable) {
        try {
            return Long.parseLong(callable.call().toString());
        } catch (Exception var3) {
            return 0L;
        }
    }

    private Iterable<Tag> nameTag(ObjectName name) {
        String nameTagValue = name.getKeyProperty("name");
        return (Iterable<Tag>) (nameTagValue != null ? Tags.of("name", nameTagValue.replaceAll("\"", "")) : Collections.emptyList());
    }

    public void close() {
        for (NotificationListener notificationListener : this.notificationListeners) {
            try {
                this.mBeanServer.removeNotificationListener(MBeanServerDelegate.DELEGATE_NAME, notificationListener);
            } catch (ListenerNotFoundException | InstanceNotFoundException ex) {
                throw new RuntimeException(ex);
            }
        }

    }
}

