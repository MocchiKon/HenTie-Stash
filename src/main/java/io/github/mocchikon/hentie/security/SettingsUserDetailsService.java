package io.github.mocchikon.hentie.security;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import io.github.mocchikon.hentie.service.SettingsService;

/** One account: the login form submits {@link #USERNAME} as a hidden field, so only a password is asked. */
@Service
public class SettingsUserDetailsService implements UserDetailsService
{
    public static final String USERNAME = "user";

    private final SettingsService settingsService;

    public SettingsUserDetailsService(SettingsService settingsService)
    {
        this.settingsService = settingsService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException
    {
        if (!USERNAME.equals(username))
        {
            throw new UsernameNotFoundException("Unknown user");
        }
        String hash = settingsService.getPasswordHash();
        if (hash == null)
        {
            throw new UsernameNotFoundException("No password configured");
        }
        return User.withUsername(USERNAME)
                .password(hash)
                .authorities(AuthorityUtils.createAuthorityList("ROLE_USER"))
                .build();
    }
}
