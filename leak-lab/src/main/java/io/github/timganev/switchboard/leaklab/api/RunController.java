package io.github.timganev.switchboard.leaklab.api;

import io.github.timganev.switchboard.leaklab.run.LeakRun;
import io.github.timganev.switchboard.leaklab.run.Runs;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/runs")
class RunController {

    private final Runs runs;

    RunController(Runs runs) {
        this.runs = runs;
    }

    @PostMapping
    ResponseEntity<LeakRun> start(@RequestParam(defaultValue = "20") int cases) {
        return ResponseEntity.accepted().body(runs.start(cases));
    }

    @GetMapping("/{id}")
    ResponseEntity<LeakRun> run(@PathVariable String id) {
        return ResponseEntity.of(runs.find(id));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
