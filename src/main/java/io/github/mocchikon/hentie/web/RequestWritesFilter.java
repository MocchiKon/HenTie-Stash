package io.github.mocchikon.hentie.web;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.mocchikon.hentie.config.WriteGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Marks the thread as serving a request, whose first write waits only as long as a user should (see
 * {@link WriteGate}). Everything else waits as long as it must, so a new background thread needs nothing.
 */
@Component
@RequiredArgsConstructor
public class RequestWritesFilter extends OncePerRequestFilter
{
    private final WriteGate writeGate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        try (WriteGate.Scope ignored = writeGate.serveRequest())
        {
            chain.doFilter(request, response);
        }
    }
}
