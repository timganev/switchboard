package io.github.timganev.switchboard.ledger.api;

import io.github.timganev.switchboard.commons.kafka.Redrive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/dead-letters")
class DeadLetterController {

    private final Redrive redrive;

    DeadLetterController(Redrive redrive) {
        this.redrive = redrive;
    }

    @GetMapping
    List<Redrive.Parked> parked(@RequestParam(defaultValue = "100") int limit) {
        return redrive.parked(limit);
    }

    @PostMapping("/redrive")
    Redriven redrive() {
        return new Redriven(redrive.redrive());
    }

    record Redriven(int records) {
    }
}
