package io.github.timganev.switchboard.trace.live;

import io.github.timganev.switchboard.trace.TraceStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live view: the latest calls rendered on the server, then new ones pushed over server-sent events.
 */
@Controller
class ConsoleController {

    private static final int ROWS = 50;

    private final TraceStore store;
    private final LiveFeed liveFeed;

    ConsoleController(TraceStore store, LiveFeed liveFeed) {
        this.store = store;
        this.liveFeed = liveFeed;
    }

    @GetMapping("/")
    String console(@RequestParam(required = false) String team, Model model) {
        String filter = team == null || team.isBlank() ? null : team.strip();
        model.addAttribute("team", filter);
        model.addAttribute("calls", store.recent(filter, ROWS).stream().map(CallView::of).toList());
        return "console";
    }

    @GetMapping("/live/stream")
    @ResponseBody
    SseEmitter stream(@RequestParam(required = false) String team) {
        return liveFeed.subscribe(team == null || team.isBlank() ? null : team.strip());
    }
}
