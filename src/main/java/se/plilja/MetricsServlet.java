package se.plilja;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@WebServlet(name = "MetricsServlet", urlPatterns = "/metrics")
public class MetricsServlet extends HttpServlet {
    private final PrometheusMeterRegistry meterRegistry;

    public MetricsServlet() {
        meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        //TomcatMetrics.monitor(meterRegistry, null, List.of()); // THIS IS THE PROBLEM!!! compile error if I uncomment this line
        TomcatMetricsWithoutManager.monitor(meterRegistry, List.of()); // Works, same as TomcatMetrics but references to Manager removed
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Random random = new Random();
            long time = random.nextInt(10, 100);
            meterRegistry.timer("foo.bar").record(time, TimeUnit.MILLISECONDS);
            resp.setStatus(200);
            resp.setContentType("text/plain");
            Thread.sleep(random.nextInt(10, 100)); // introduce some randomness into response times to make metrics more interesting
            var writer = resp.getWriter();
            writer.println(meterRegistry.scrape());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

    }
}
