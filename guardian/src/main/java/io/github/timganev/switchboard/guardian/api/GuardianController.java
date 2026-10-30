package io.github.timganev.switchboard.guardian.api;

import io.github.timganev.switchboard.commons.CallHeaders;
import io.github.timganev.switchboard.commons.chat.ChatRequest;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.commons.privacy.Restoration;
import io.github.timganev.switchboard.commons.privacy.RestoreRequest;
import io.github.timganev.switchboard.guardian.Guardian;
import io.github.timganev.switchboard.guardian.events.DecisionEvents;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
class GuardianController {

    private final Guardian guardian;
    private final DecisionEvents decisionEvents;

    GuardianController(Guardian guardian, DecisionEvents decisionEvents) {
        this.guardian = guardian;
        this.decisionEvents = decisionEvents;
    }

    @PostMapping("/inspect")
    Inspection inspect(@RequestBody ChatRequest request,
                       @RequestHeader(name = CallHeaders.CALL_ID, required = false) String callId,
                       @RequestHeader(name = CallHeaders.SESSION, required = false) String sessionId) {
        Inspection inspection = guardian.inspect(request);
        decisionEvents.decided(callId, sessionId, inspection);
        return inspection;
    }

    @PostMapping("/restore")
    Restoration restore(@RequestBody RestoreRequest request) {
        return guardian.restore(request.original(), request.response());
    }
}
