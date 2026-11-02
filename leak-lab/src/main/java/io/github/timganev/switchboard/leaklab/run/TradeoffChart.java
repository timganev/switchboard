package io.github.timganev.switchboard.leaklab.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Privacy against utility as an SVG: who was recognised on one axis, the judge's grade on the other; top left is best.
 */
public final class TradeoffChart {

    private static final int WIDTH = 640;
    private static final int HEIGHT = 400;
    private static final int LEFT = 64;
    private static final int RIGHT = 120;
    private static final int TOP = 24;
    private static final int BOTTOM = 56;
    private static final double MIN_GRADE = 1;
    private static final double MAX_GRADE = 5;
    private static final int DOT = 6;
    private static final int EDGE = 8;
    private static final int LABEL_HEIGHT = 16;
    private static final int LABEL_GAP = 10;
    private static final double CHARACTER_WIDTH = 9;

    private static final String STYLE = """
            <style>
              svg { --surface: #fcfcfb; --text: #0b0b0b; --muted: #52514e; --grid: #e4e3df; --mark: #2a78d6; }
              @media (prefers-color-scheme: dark) {
                svg { --surface: #1a1a19; --text: #f0efec; --muted: #c3c2b7; --grid: #3a3936; --mark: #3987e5; }
              }
              text { font: 12px system-ui, -apple-system, "Segoe UI", sans-serif; fill: var(--muted); }
              .label { font-weight: 600; fill: var(--text); }
              .grid { stroke: var(--grid); stroke-width: 1; }
              .chance { stroke: var(--muted); stroke-width: 1; stroke-dasharray: 4 4; }
              .dot { fill: var(--mark); stroke: var(--surface); stroke-width: 2; }
            </style>
            """;

    private TradeoffChart() {
    }

    public static String svg(List<Score> scores, double chance) {
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\" role=\"img\" aria-label=\"%s\">\n"
                .formatted(WIDTH, HEIGHT, WIDTH, HEIGHT, "Answer quality against people re-identified, per strategy"));
        svg.append(STYLE);
        svg.append("<rect width=\"%d\" height=\"%d\" fill=\"var(--surface)\"/>\n".formatted(WIDTH, HEIGHT));
        for (int percent = 0; percent <= 100; percent += 25) {
            double x = x(percent / 100.0);
            svg.append(line("grid", x, TOP, x, HEIGHT - BOTTOM));
            svg.append(text(x, HEIGHT - BOTTOM + 18, "middle", percent + "%", null));
        }
        for (int grade = (int) MIN_GRADE; grade <= MAX_GRADE; grade++) {
            double y = y(grade);
            svg.append(line("grid", LEFT, y, WIDTH - RIGHT, y));
            svg.append(text(LEFT - 10, y + 4, "end", String.valueOf(grade), null));
        }
        double chanceX = x(chance);
        svg.append(line("chance", chanceX, TOP, chanceX, HEIGHT - BOTTOM));
        svg.append(text(chanceX + 6, HEIGHT - BOTTOM - 8, "start", "blind guess", null));
        svg.append(text(LEFT + (WIDTH - LEFT - RIGHT) / 2.0, HEIGHT - 12, "middle", "People re-identified by the attacker", null));
        svg.append("<text x=\"16\" y=\"%s\" text-anchor=\"middle\" transform=\"rotate(-90 16 %s)\">Answer quality (judge, 1 to 5)</text>\n"
                .formatted(number(TOP + (HEIGHT - TOP - BOTTOM) / 2.0), number(TOP + (HEIGHT - TOP - BOTTOM) / 2.0)));
        // Strategies that scored the same share one dot and one label.
        Map<String, List<Score>> points = scores.stream().collect(Collectors.groupingBy(
                score -> number(x(score.reidentificationRate())) + "," + number(y(score.quality())), LinkedHashMap::new, Collectors.toList()));
        List<double[]> placed = new ArrayList<>();
        points.values().forEach(point -> placed.add(new double[]{
                x(point.getFirst().reidentificationRate()) - DOT, y(point.getFirst().quality()) - DOT, 2 * DOT, 2 * DOT}));
        for (List<Score> point : points.values().stream().sorted(Comparator.comparingDouble(point -> -point.getFirst().quality())).toList()) {
            Score first = point.getFirst();
            double x = x(first.reidentificationRate());
            double y = y(first.quality());
            String name = point.stream().map(score -> score.strategy().name()).collect(Collectors.joining(" · "));
            String tooltip = point.stream().map(score -> "%s: %d%% re-identified, quality %s, %d ms".formatted(score.strategy(),
                    Math.round(100 * score.reidentificationRate()), number(score.quality()), score.meanLatencyMs())).collect(Collectors.joining("; "));
            svg.append("<circle class=\"dot\" cx=\"%s\" cy=\"%s\" r=\"%d\"><title>%s</title></circle>\n".formatted(number(x), number(y), DOT, tooltip));
            double[] label = label(x, y, name.length() * CHARACTER_WIDTH, placed);
            placed.add(label);
            svg.append(text(label[0], label[1] + 12, "start", name, "label"));
        }
        return svg.append("</svg>\n").toString();
    }

    // Right of the dot, else left of it, else pushed down; never outside the plot or over another label or dot.
    private static double[] label(double x, double y, double width, List<double[]> placed) {
        double top = y - LABEL_HEIGHT / 2.0;
        for (double shift = 0; ; shift += LABEL_HEIGHT) {
            for (double left : new double[]{x + LABEL_GAP, x - LABEL_GAP - width}) {
                double[] box = {left, top + shift, width, LABEL_HEIGHT};
                if (left >= LEFT && left + width <= WIDTH - EDGE && placed.stream().noneMatch(other -> overlaps(box, other))) {
                    return box;
                }
            }
        }
    }

    private static boolean overlaps(double[] a, double[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    private static double x(double rate) {
        return LEFT + rate * (WIDTH - LEFT - RIGHT);
    }

    private static double y(double grade) {
        return TOP + (MAX_GRADE - grade) / (MAX_GRADE - MIN_GRADE) * (HEIGHT - TOP - BOTTOM);
    }

    private static String line(String type, double x1, double y1, double x2, double y2) {
        return "<line class=\"%s\" x1=\"%s\" y1=\"%s\" x2=\"%s\" y2=\"%s\"/>\n".formatted(type, number(x1), number(y1), number(x2), number(y2));
    }

    private static String text(double x, double y, String anchor, String content, String type) {
        return "<text%s x=\"%s\" y=\"%s\" text-anchor=\"%s\">%s</text>\n"
                .formatted(type == null ? "" : " class=\"" + type + "\"", number(x), number(y), anchor, content);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
