package io.github.mocchikon.hentie.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The xerial driver fails with {@code SQLITE_CANTOPEN} on a missing parent folder instead of creating it,
 * which is exactly a fresh install.
 * <p>
 * A {@link BeanFactoryPostProcessor} because it runs before any regular singleton, so no bean ordering can
 * open a connection first.
 */
@Component
public class DatabaseDirectory implements BeanFactoryPostProcessor
{
    private static final Logger log = LoggerFactory.getLogger(DatabaseDirectory.class);

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException
    {
        // Not @Value: this runs too early for placeholder resolution to be reliable.
        Path directory = directoryOf(beanFactory.getBean(Environment.class).getProperty("spring.datasource.url"));
        if (directory == null || Files.isDirectory(directory))
        {
            return;
        }
        try
        {
            Files.createDirectories(directory);
            log.info("Created the database folder {}", directory);
        }
        catch (IOException e)
        {
            // Say what is wrong here rather than as SQLITE_CANTOPEN later.
            throw new IllegalStateException("Could not create the database folder " + directory, e);
        }
    }

    /**
     * {@code null} when there is nothing to create: not SQLite, a URL that names no file ({@code :memory:},
     * {@code :resource:}, a temp file), or a file at the filesystem root.
     */
    static Path directoryOf(String jdbcUrl)
    {
        if (jdbcUrl == null || !jdbcUrl.toLowerCase().startsWith(SQLITE_PREFIX))
        {
            return null;
        }
        String file = jdbcUrl.substring(SQLITE_PREFIX.length());
        int query = file.indexOf('?');   // the pragmas
        if (query >= 0)
        {
            file = file.substring(0, query);
        }
        if (file.isBlank() || file.startsWith(":"))
        {
            return null;
        }
        return Paths.get(file).toAbsolutePath().normalize().getParent();
    }
}
