package io.github.timganev.switchboard.guardian.api;

import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.Restoration;
import io.github.timganev.switchboard.commons.privacy.RestoreRequest;
import io.github.timganev.switchboard.guardian.Guardian;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
class GuardianController {

    private final Guardian guardian;

    GuardianController(Guardian guardian) {
        this.guardian = guardian;
    }

    @PostMapping("/inspect")
    Inspection inspect(@RequestBody ChatRequest request) {
        return guardian.inspect(request);
    }

    @PostMapping("/restore")
    Restoration restore(@RequestBody RestoreRequest request) {
        return guardian.restore(request.original(), request.response());
    }
}
