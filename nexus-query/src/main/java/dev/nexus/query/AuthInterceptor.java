package dev.nexus.query;

import dev.nexus.common.Jwt;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/** Requires a valid bearer token on /v1/fleets/**; controllers then check the fleet claim. */
public class AuthInterceptor implements HandlerInterceptor {

    public static final String CLAIMS = "nexus.claims";

    private final byte[] secret;

    public AuthInterceptor(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod())) {
            return true; // CORS preflight carries no token
        }
        String h = req.getHeader("Authorization");
        String token = h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
        var claims = Jwt.verify(secret, token, System.currentTimeMillis() / 1000);
        if (claims.isEmpty()) {
            res.setStatus(401);
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"missing_or_invalid_token\"}");
            return false;
        }
        req.setAttribute(CLAIMS, claims.get());
        return true;
    }
}
