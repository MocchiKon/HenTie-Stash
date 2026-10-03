package io.github.mocchikon.hentie.security;

import java.io.IOException;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.mocchikon.hentie.service.SettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns login off at runtime by injecting an authenticated token, rather than rebuilding the filter chain,
 * which is fragile across Spring Security versions. The user is therefore "authenticated" even with login off.
 */
@Component
public class LoginToggleFilter extends OncePerRequestFilter
{
    private final SettingsService settingsService;

    public LoginToggleFilter(SettingsService settingsService)
    {
        this.settingsService = settingsService;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch()
    {
        return false; // so error pages are visible when login is off
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        if (!settingsService.isLoginRequired())
        {
            Authentication current = SecurityContextHolder.getContext().getAuthentication();
            if (current == null || !current.isAuthenticated() || current instanceof AnonymousAuthenticationToken)
            {
                Authentication auto = new UsernamePasswordAuthenticationToken(
                        SettingsUserDetailsService.USERNAME, null,
                        AuthorityUtils.createAuthorityList("ROLE_USER"));
                SecurityContextHolder.getContext().setAuthentication(auto);
            }
        }
        chain.doFilter(request, response);
    }
}
