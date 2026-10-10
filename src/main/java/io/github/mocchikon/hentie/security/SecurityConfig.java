package io.github.mocchikon.hentie.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/** {@code /data/**} images need auth too. Turning login off is handled by {@link LoginToggleFilter}. */
@Configuration
@EnableWebSecurity
public class SecurityConfig
{
    @Bean
    public PasswordEncoder passwordEncoder()
    {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginToggleFilter loginToggleFilter,
                                                   FailedLoginGuard failedLoginGuard) throws Exception
    {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", FailedLoginGuard.SHUT_DOWN_PAGE, "/css/**", "/js/**", "/images/**",
                                "/favicon.ico")
                        .permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(failedLoginGuard)
                        .failureHandler(failedLoginGuard)
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout"))
                .addFilterBefore(loginToggleFilter, AuthorizationFilter.class);

        return http.build();
    }
}
