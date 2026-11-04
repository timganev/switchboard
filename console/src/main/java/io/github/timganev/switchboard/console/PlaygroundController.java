package io.github.timganev.switchboard.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@RestController
@RequestMapping("/api")
class PlaygroundController {

    private static final Logger log = LoggerFactory.getLogger(PlaygroundController.class);

    private final Playground playground;

    PlaygroundController(Playground playground) {
        this.playground = playground;
    }

    @PostMapping("/playground")
    Playground.Result run(@RequestBody PromptRequest request) {
        return playground.run(request.prompt());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Map<String, String>> unreachable(RestClientException exception) {
        log.warn("Playground call failed: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "The guardian or the gateway did not answer"));
    }

    record PromptRequest(String prompt) {
    }
}
