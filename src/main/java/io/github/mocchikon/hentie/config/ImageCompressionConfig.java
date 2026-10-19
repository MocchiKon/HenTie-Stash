package io.github.mocchikon.hentie.config;

import io.github.mocchikon.hentie.service.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Image Compression's own pool, one image per thread at 100%. Separate from every other pool because encoding
 * is CPU-bound and takes seconds to minutes per page: shared, one gallery could hold all of the app's spare
 * threads.
 *
 * <p>The pool is sized to the machine; the Settings percentage limits how many of its threads run at once
 * (see {@link ImageCompressionExecutor}).
 */
@Configuration
public class ImageCompressionConfig
{
    @Bean(destroyMethod = "shutdownNow")
    public ImageCompressionExecutor imageCompressionExecutor(AppProperties appProperties,
                                                             SettingsService settingsService)
    {
        int configured = appProperties.getImageCompression().getThreads();
        int maxThreads = Math.max(1, configured > 0 ? configured : Runtime.getRuntime().availableProcessors());
        var counter = new AtomicInteger();
        ThreadFactory factory = runnable ->
        {
            var thread = new Thread(runnable, "image-compressor-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ImageCompressionExecutor(maxThreads, settingsService::getImageCompressionCpuPercent, factory);
    }
}
