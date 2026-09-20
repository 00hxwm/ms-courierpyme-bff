package com.courierpyme.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint entryPoint = (req, res, ex) -> {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.setHeader("WWW-Authenticate", "Bearer");
            res.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Token ausente, inválido o expirado\"}");
        };
        AccessDeniedHandler deniedHandler = (req, res, ex) -> {
            res.setStatus(403);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"error\":\"forbidden\",\"message\":\"Tu rol no permite esta operación\"}");
        };

        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/error").permitAll()
                // Tracking público (debe ir antes de las reglas más amplias de /api/shipments)
                .requestMatchers(HttpMethod.GET, "/api/shipments/tracking/**").permitAll()
                // Envíos: solo Admin/Operador cambian estado; los tres roles listan y crean
                .requestMatchers(HttpMethod.PUT, "/api/shipments/*/status").hasAnyRole("Admin", "Operador")
                .requestMatchers("/api/shipments/**").hasAnyRole("Admin", "Operador", "Cliente")
                // Catálogo: lectura Admin/Operador, escritura solo Admin
                .requestMatchers(HttpMethod.GET, "/api/catalog/**").hasAnyRole("Admin", "Operador")
                .requestMatchers("/api/catalog/**").hasRole("Admin")
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(deniedHandler))
            .oauth2ResourceServer(o -> o
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(deniedHandler)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                for (String r : roles) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + r));
                }
            }
            return authorities;
        });
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:3000}") String[] origins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(Arrays.asList(origins));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}