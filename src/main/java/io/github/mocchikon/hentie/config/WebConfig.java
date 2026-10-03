package io.github.mocchikon.hentie.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.mocchikon.hentie.web.JxlTranscodeInterceptor;

/**
 * <b>{@code private} is declared here, not in {@link JxlTranscodeInterceptor}</b>: the resource handler sets
 * its {@code Cache-Control} with a plain {@code setHeader}, overwriting what an interceptor set, and this is
 * the one place both variants of a {@code .jxl} URL pass through. It is right for {@code /data/**} anyway,
 * since the images need authentication.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer
{
    private final AppProperties appProperties;
    private final JxlTranscodeInterceptor jxlTranscodeInterceptor;

    public WebConfig(AppProperties appProperties, JxlTranscodeInterceptor jxlTranscodeInterceptor)
    {
        this.appProperties = appProperties;
        this.jxlTranscodeInterceptor = jxlTranscodeInterceptor;
    }

    /** An interceptor, not a filter, so it runs after Spring Security: no unauthenticated request reaches a decoder. */
    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(jxlTranscodeInterceptor).addPathPatterns("/data/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry)
    {
        Path dataDir = Paths.get(appProperties.getDataDir()).toAbsolutePath().normalize();
        String location = dataDir.toUri().toString(); // always ends with '/'

        registry.addResourceHandler("/data/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(JxlTranscodeInterceptor.CACHE_TTL).cachePrivate())
                .resourceChain(true);
    }
}
