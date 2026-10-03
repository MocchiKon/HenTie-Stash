package io.github.mocchikon.hentie.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Image Compression's own pool, one image per thread. Separate from every other pool because encoding is
 * CPU-bound and takes seconds to minutes per page: shared, one gallery could hold all of the app's spare threads.
 *
 * <p>Daemon threads, so unfinished pages never keep the JVM alive; an interrupted page just stays as it was.
 */
@Configuration
public class ImageCompressionConfig
{
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService imageCompressionExecutor(AppProperties appProperties)
    {
        int configured = appProperties.getImageCompression().getThreads();
        int threads = configured > 0 ? configured : Runtime.getRuntime().availableProcessors();
        var counter = new AtomicInteger();
        ThreadFactory factory = runnable ->
        {
            var thread = new Thread(runnable, "image-compressor-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(Math.max(1, threads), factory);
    }
}
