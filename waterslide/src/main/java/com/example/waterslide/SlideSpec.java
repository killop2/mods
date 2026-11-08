package com.example.waterslide;

import java.util.ArrayList;
import java.util.List;

/**
 * Recipe for one slide type: trough cross-section, colours, and the route as a list of
 * straight pieces and arcs (left = positive degrees), each with its own downhill grade.
 * Local space: the route starts at (startX, ?, startZ) heading +Z; height is solved backwards from the exit.
 */
public final class SlideSpec {
    public static final int POLE = 1;   // arc gets a centre pole with arms (classic slide only)
    public static final int LEGS = 2;   // straight gets legs to the ground (classic slide only)

    public final String id;
    double ri = 2.2, ro = 2.45, phiDeg = 72;
    double bankPerCurve = 6.0;
    double endBottom = 0.5;
    double startX, startZ;
    double gradeSmooth = 3.0;
    double deckLength = 5.0, deckExtraWidth = 1.0;
    /** Trough colour (RGB) and optional stripe colour / stripe length in blocks (0 = plain). */
    int color = 0xF5BC14, stripeColor = 0xF5BC14;
    double stripeLength = 0;
    final List<double[]> segments = new ArrayList<>();   // {kind 0=straight 1=arc, length|radius, degrees, grade, flags}

    // ---- Dark Carnival extras
    /** Obstacle kinds: paddle wheel (slows, bumps), kicker ramp with a ring of fire (jump), swinging hammer, boost hoop. */
    public static final int OB_WHEEL = 0, OB_RAMP = 1, OB_HAMMER = 2, OB_HOOP = 3;
    /** Spring-loaded clown heads on one wall (side by index parity), a spinning turnstile, a tunnel of terror (looks only). */
    public static final int OB_BUMPERS = 4, OB_SPINNER = 5, OB_TUNNEL = 6;
    /** Multiplies the slide's acceleration and top speed (1 = normal). */
    double speedScale = 1.0D;

    SlideSpec speed(double scale) {
        speedScale = scale;
        return this;
    }

    /** The last section of a chain: draws the finish arch. */
    boolean finish;

    SlideSpec finish() {
        finish = true;
        return this;
    }

    public boolean hasFinish() {
        return finish;
    }

    /** No start platform (a continuation section of a long slide). */
    public boolean hasDeck() {
        return deckLength > 0.0D;
    }

    public double speedScale() {
        return speedScale;
    }
    /** {kind, s} - s = distance along the route where the obstacle stands. */
    final List<double[]> obstacles = new ArrayList<>();
    /** Tint of the water stream and of the supports/platform (RGB, white = untinted). */
    int waterTint = 0xFFFFFF, supportTint = 0xFFFFFF;
    /** Draws the Dark Carnival decorations (string lights, jester arch, animated obstacles). */
    boolean carnival;

    public SlideSpec(String id) {
        this.id = id;
    }

    SlideSpec obstacle(int kind, double s) {
        obstacles.add(new double[]{kind, s});
        return this;
    }

    SlideSpec tints(int water, int support) {
        waterTint = water;
        supportTint = support;
        return this;
    }

    SlideSpec carnival() {
        carnival = true;
        return this;
    }

    public List<double[]> obstacles() {
        return obstacles;
    }

    public int waterTint() {
        return waterTint;
    }

    public int supportTint() {
        return supportTint;
    }

    public boolean isCarnival() {
        return carnival;
    }

    SlideSpec trough(double ri, double ro, double phiDeg) {
        this.ri = ri;
        this.ro = ro;
        this.phiDeg = phiDeg;
        return this;
    }

    SlideSpec start(double x, double z) {
        startX = x;
        startZ = z;
        return this;
    }

    SlideSpec colors(int color, int stripe, double stripeLength) {
        this.color = color;
        this.stripeColor = stripe;
        this.stripeLength = stripeLength;
        return this;
    }

    SlideSpec bank(double perCurve) {
        bankPerCurve = perCurve;
        return this;
    }

    /** Length (blocks) over which the downhill grade is smoothed; short = sharp steep drops stay steep. */
    SlideSpec gradeSmooth(double blocks) {
        gradeSmooth = blocks;
        return this;
    }

    SlideSpec endHeight(double bottom) {
        endBottom = bottom;
        return this;
    }

    SlideSpec deck(double length, double extraWidth) {
        deckLength = length;
        deckExtraWidth = extraWidth;
        return this;
    }

    SlideSpec straight(double length, double grade) {
        return straight(length, grade, 0);
    }

    SlideSpec straight(double length, double grade, int flags) {
        segments.add(new double[]{0, length, 0, grade, flags});
        return this;
    }

    SlideSpec arc(double radius, double degrees, double grade) {
        return arc(radius, degrees, grade, 0);
    }

    SlideSpec arc(double radius, double degrees, double grade, int flags) {
        segments.add(new double[]{1, radius, degrees, grade, flags});
        return this;
    }

    public int color() {
        return color;
    }

    public int stripeColor() {
        return stripeColor;
    }

    public double stripeLength() {
        return stripeLength;
    }

    double length() {
        double l = 0;
        for (double[] s : segments) l += s[0] == 0 ? s[1] : s[1] * Math.toRadians(Math.abs(s[2]));
        return l;
    }
}
