package io.github.mocchikon.hentie.security;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.AppShutdown;
import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shuts the app down after {@code app.security.max-failed-logins} wrong passwords in a row, so a password on the LAN
 * cannot be guessed at leisure: every few guesses need the user to start the app again. Anyone who can reach the login
 * page can stop the app this way, which is why Settings can turn it off.
 *
 * <p>The count lives in memory: a restart is what the guesser has to wait for, so it starts over with one. A login
 * resets it, so the user's own typos never add up. Only counted while login is required: without a password there is
 * nothing to guess, and a failed attempt says nothing.
 *
 * <p>The last attempt is answered with a page, not a redirect: once shutdown starts, the server takes no new request.
 */
@Component
public class FailedLoginGuard implements AuthenticationFailureHandler, AuthenticationSuccessHandler
{
    private static final Logger log = LoggerFactory.getLogger(FailedLoginGuard.class);

    /** Permitted without login, and shows anything only once the guard has tripped. */
    public static final String SHUT_DOWN_PAGE = "/login/shut-down";

    private final AppProperties appProperties;
    private final SettingsService settingsService;
    private final AppShutdown appShutdown;

    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicBoolean tripped = new AtomicBoolean();

    private final SimpleUrlAuthenticationFailureHandler toLoginPage = new SimpleUrlAuthenticationFailureHandler(
            "/login?error");
    private final SimpleUrlAuthenticationFailureHandler toShutDownPage = new SimpleUrlAuthenticationFailureHandler(
            SHUT_DOWN_PAGE);
    private final SimpleUrlAuthenticationSuccessHandler toHome = new SimpleUrlAuthenticationSuccessHandler("/");

    public FailedLoginGuard(AppProperties appProperties, SettingsService settingsService, AppShutdown appShutdown)
    {
        this.appProperties = appProperties;
        this.settingsService = settingsService;
        this.appShutdown = appShutdown;
        toShutDownPage.setUseForward(true);
        toHome.setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException
    {
        OptionalInt limit = limit();
        if (limit.isEmpty())
        {
            toLoginPage.onAuthenticationFailure(request, response, exception);
            return;
        }
        int failed = failures.incrementAndGet();
        log.warn("Wrong password from {} ({} of {} before the app shuts down)", request.getRemoteAddr(), failed,
                limit.getAsInt());
        if (failed < limit.getAsInt())
        {
            toLoginPage.onAuthenticationFailure(request, response, exception);
            return;
        }
        tripped.set(true);
        appShutdown.shutDownSoon("after " + failed + " wrong passwords in a row, the last from "
                + request.getRemoteAddr());
        toShutDownPage.onAuthenticationFailure(request, response, exception);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException
    {
        failures.set(0);
        toHome.onAuthenticationSuccess(request, response, authentication);
    }

    /** How many more wrong passwords the app takes before it shuts down; empty when it never does. */
    public OptionalInt attemptsLeft()
    {
        OptionalInt limit = limit();
        return limit.isEmpty() ? limit : OptionalInt.of(Math.max(0, limit.getAsInt() - failures.get()));
    }

    public boolean isTripped()
    {
        return tripped.get();
    }

    private OptionalInt limit()
    {
        int max = appProperties.getSecurity().getMaxFailedLogins();
        return max > 0 && settingsService.isShutDownOnFailedLogins() && settingsService.isLoginRequired()
                ? OptionalInt.of(max) : OptionalInt.empty();
    }
}
