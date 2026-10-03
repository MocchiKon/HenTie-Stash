package io.github.mocchikon.hentie.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.net.URI;

/** So a user double-clicking the jar or exe lands straight on the page. */
@Component
public class BrowserLauncher
{
    private final AppProperties appProperties;
    private final int port;

    public BrowserLauncher(AppProperties appProperties, @Value("${server.port:8080}") int port)
    {
        this.appProperties = appProperties;
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openBrowser()
    {
        if (!appProperties.isOpenBrowser())
        {
            return;
        }
        String url = "http://localhost:" + port;

        try
        {
            if (!java.awt.GraphicsEnvironment.isHeadless()
                    && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        }
        catch (Exception ignored)
        {
            // fall through to the OS command
        }

        try
        {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win"))
            {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            }
            else if (os.contains("mac"))
            {
                new ProcessBuilder("open", url).start();
            }
            else
            {
                new ProcessBuilder("xdg-open", url).start();
            }
        }
        catch (Exception ignored)
        {
            // A convenience only; never fail startup over it.
        }
    }
}
