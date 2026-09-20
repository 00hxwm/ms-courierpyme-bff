package com.courierpyme.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@RestController
public class GatewayProxyController {

    private final RestTemplate restTemplate;
    private final Map<String, String> targets;

    public GatewayProxyController(@Value("${services.shipments.url}") String shipmentsUrl,
                                  @Value("${services.catalog.url}") String catalogUrl) {
        // Cliente JDK (HTTP/1.1): con el cliente por defecto (HttpURLConnection), un 401
        // con WWW-Authenticate en POST/PUT lanza un error de reintento y se pierde el 401 real.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(factory);

        this.targets = Map.of("shipments", shipmentsUrl, "catalog", catalogUrl);
    }

    @RequestMapping("/api/{service}/**")
    public ResponseEntity<byte[]> proxy(@PathVariable String service,
                                        HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        String base = targets.get(service);
        if (base == null) {
            return json(HttpStatus.NOT_FOUND, "{\"error\":\"Ruta no encontrada\"}");
        }

        String query = request.getQueryString();
        URI uri = URI.create(base + request.getRequestURI() + (query != null ? "?" + query : ""));

        HttpHeaders headers = new HttpHeaders();
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null) {
            headers.set(HttpHeaders.AUTHORIZATION, auth);
        }
        String contentType = request.getContentType();
        if (contentType != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        }

        try {
            ResponseEntity<byte[]> r = restTemplate.exchange(
                    uri, HttpMethod.valueOf(request.getMethod()),
                    new HttpEntity<>(body, headers), byte[].class);
            return build(r.getStatusCode(), r.getHeaders().getContentType(), r.getBody());
        } catch (HttpStatusCodeException ex) {
            // Propaga tal cual 400/401/403/404/409... que responde el microservicio
            MediaType ct = ex.getResponseHeaders() != null ? ex.getResponseHeaders().getContentType() : null;
            return build(ex.getStatusCode(), ct, ex.getResponseBodyAsByteArray());
        } catch (ResourceAccessException ex) {
            return json(HttpStatus.SERVICE_UNAVAILABLE, "{\"error\":\"Servicio no disponible\"}");
        }
    }

    private ResponseEntity<byte[]> build(HttpStatusCode status, MediaType contentType, byte[] body) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (contentType != null) {
            b.contentType(contentType);
        }
        return b.body(body);
    }

    private ResponseEntity<byte[]> json(HttpStatus status, String text) {
        return build(status, MediaType.APPLICATION_JSON, text.getBytes(StandardCharsets.UTF_8));
    }
}