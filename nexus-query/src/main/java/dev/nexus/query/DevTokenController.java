package dev.nexus.query;

import dev.nexus.common.Jwt;
import dev.nexus.common.PingValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Issues short-lived dispatcher tokens in local/demo environments, standing in for an OIDC provider.
 * Returns 404 unless {@code nexus.query.dev-tokens=true}. See docs/adr/0005-dispatcher-auth.md.
 */
@RestController
public class DevTokenController {

    public record TokenRequest(String user, List<String> fleets) {
    }

    private final QueryProperties props;

    public DevTokenController(QueryProperties props) {
        this.props = props;
    }

    @PostMapping("/v1/dev/token")
    public ResponseEntity<?> token(@RequestBody TokenRequest req) {
        if (!props.devTokens()) {
            return ResponseEntity.notFound().build();
        }
        if (req.user() == null || !PingValidator.isSafeId(req.user()) || req.fleets() == null || req.fleets().isEmpty()
                || !req.fleets().stream().allMatch(PingValidator::isSafeId)) {
            return ResponseEntity.badRequest().body(Map.of("status", 400, "detail", "user and fleets[] required"));
        }
        long now = System.currentTimeMillis() / 1000;
        String token = Jwt.issue(props.jwtSecret().getBytes(StandardCharsets.UTF_8), req.user(), Set.copyOf(req.fleets()),
                Set.of("dispatcher"), 3600, now);
        return ResponseEntity.ok(Map.of("token", token, "expiresIn", 3600));
    }
}
