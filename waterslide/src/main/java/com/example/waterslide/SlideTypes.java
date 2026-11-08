package com.example.waterslide;

/**
 * All slide types. The index is saved in the world (anchor block state), so only ever ADD types at the end.
 * Each type is just a route recipe; geometry/mesh/collision are generated from it.
 */
public final class SlideTypes {
    public static final SlideSpec[] SPECS = {
            // 0 - classic yellow double helix with poles (the first one)
            new SlideSpec("classic").trough(3.6, 3.9, 70).start(-14, -20).bank(6.0).deck(6.5, 1.3)
                    .colors(0xF5BC14, 0xF5BC14, 0)
                    .straight(2.4, 0.03).straight(17.6, 0.25)
                    .arc(14, 540, 0.13, SlideSpec.POLE).arc(14, -540, 0.13, SlideSpec.POLE)
                    .straight(25, 0.11, SlideSpec.LEGS).straight(5, 0.015, SlideSpec.LEGS),

            // 1 - "drop": steep start, S-curves, tight loop, return curve, run-out (blue with green bands)
            new SlideSpec("drop").trough(2.2, 2.45, 72).start(0, 0).bank(6.0)
                    .colors(0x2F7FE0, 0x3CC8A0, 3.0)
                    .straight(3, 0.03).straight(16, 0.62).straight(8, 0.06)
                    .arc(10, 110, 0.07).arc(10, -160, 0.07).straight(10, 0.07)
                    .arc(5.5, 380, 0.17).straight(8, 0.07)
                    .arc(7, -200, 0.08).straight(14, 0.06).straight(4, 0.015),

            // 2 - "spiral": short drop into a tight 3-turn helix, then out (red)
            new SlideSpec("spiral").trough(2.0, 2.25, 72).start(0, 0).bank(6.5)
                    .colors(0xD8302E, 0xE8564A, 4.0)
                    .straight(3, 0.03).straight(6, 0.22)
                    .arc(6, -90, 0.12).arc(4.6, 1080, 0.19)
                    .arc(7, -120, 0.10).straight(8, 0.08).straight(3, 0.015),

            // 3 - "serpentine": a chain of S-bends (orange with light bands)
            new SlideSpec("serpentine").trough(2.2, 2.45, 72).start(0, 0).bank(6.0)
                    .colors(0xF0901E, 0xFFC45A, 2.5)
                    .straight(3, 0.03).straight(8, 0.24)
                    .arc(6.5, 90, 0.12).arc(6.5, -180, 0.12).arc(6.5, 180, 0.12).arc(6.5, -180, 0.12).arc(6.5, 90, 0.12)
                    .straight(10, 0.08).straight(3, 0.015),

            // 4 - first, shorter Dark Carnival slide. Kept unchanged (only slower) so slides already placed in a world keep
            //     their collision; the spawner now places type 5.
            new SlideSpec("dark_carnival_v1").trough(2.2, 2.45, 72).start(0, 0).bank(6.0).deck(6.0, 1.3)
                    .colors(0x4A2A6A, 0x8E1424, 2.0).tints(0xE0B0FF, 0xE0B050).carnival().speed(0.36)
                    .straight(3, 0.03).straight(10, 0.33).straight(7, 0.05)
                    .arc(9, 110, 0.08).straight(14, 0.05)
                    .arc(9, -140, 0.08).straight(10, 0.06)
                    .arc(7, 80, 0.09).straight(9, 0.06).straight(4, 0.015)
                    .obstacle(SlideSpec.OB_WHEEL, 16.5)
                    .obstacle(SlideSpec.OB_RAMP, 39.3)
                    .obstacle(SlideSpec.OB_HAMMER, 78.0)
                    .obstacle(SlideSpec.OB_HOOP, 95.0).obstacle(SlideSpec.OB_HOOP, 97.5).obstacle(SlideSpec.OB_HOOP, 100.0),

            // 5 - "dark carnival": violet with blood-red bands, gold supports, slow (0.45x) so the course can be enjoyed,
            //     ~180 blocks: paddle wheel, kicker jump through a ring of fire, jester hammer, boost hoops,
            //     clown-head bumpers, a turnstile, a tunnel of terror on the big curve, a second wheel and hammer,
            //     more hoops, and a finish arch
            new SlideSpec("dark_carnival").trough(2.2, 2.45, 72).start(0, 0).bank(6.0).deck(6.0, 1.3)
                    .colors(0x4A2A6A, 0x8E1424, 2.0).tints(0xE0B0FF, 0xE0B050).carnival().speed(0.36)
                    .straight(3, 0.03).straight(10, 0.33).straight(7, 0.05)        //   0 -  20
                    .arc(9, 110, 0.08).straight(14, 0.05)                          //  20 -  51.3
                    .arc(9, -140, 0.08).straight(10, 0.06)                         //  51.3 - 83.3
                    .arc(7, 80, 0.09).straight(9, 0.06)                            //  83.3 - 102
                    .arc(8, -100, 0.08).straight(14, 0.05)                         // 102 - 130
                    .arc(10, 150, 0.07).straight(12, 0.05)                         // 130 - 168.2
                    .straight(8, 0.04).straight(4, 0.015)                          // 168.2 - 180.2
                    .obstacle(SlideSpec.OB_WHEEL, 16.5)
                    .obstacle(SlideSpec.OB_RAMP, 39.3)
                    .obstacle(SlideSpec.OB_HAMMER, 78.0)
                    .obstacle(SlideSpec.OB_HOOP, 95.0).obstacle(SlideSpec.OB_HOOP, 97.5).obstacle(SlideSpec.OB_HOOP, 100.0)
                    .obstacle(SlideSpec.OB_BUMPERS, 119.0).obstacle(SlideSpec.OB_BUMPERS, 123.0)
                    .obstacle(SlideSpec.OB_SPINNER, 127.5)
                    .obstacle(SlideSpec.OB_TUNNEL, 143.0)
                    .obstacle(SlideSpec.OB_WHEEL, 159.5)
                    .obstacle(SlideSpec.OB_HAMMER, 165.0)
                    .obstacle(SlideSpec.OB_HOOP, 170.0).obstacle(SlideSpec.OB_HOOP, 172.5),

            // 6..8 - the long Dark Carnival slide: three sections placed end to end (~540 blocks, downhill all the way).
            //     Section 1: the course with curves (net turn 0, so it ends heading the same way it starts).
            new SlideSpec("dark_carnival_long_1").trough(2.2, 2.45, 72).start(0, 0).bank(6.0).deck(6.0, 1.3)
                    .colors(0x4A2A6A, 0x8E1424, 2.0).tints(0xE0B0FF, 0xE0B050).carnival().speed(0.36)
                    .straight(3, 0.03).straight(10, 0.33).straight(7, 0.05, SlideSpec.LEGS)   //   0 -  20
                    .arc(9, 90, 0.08).straight(12, 0.05, SlideSpec.LEGS)                     //  20 -  46.1
                    .arc(9, -90, 0.08).straight(12, 0.06, SlideSpec.LEGS)                    //  46.1 - 72.3
                    .arc(8, -90, 0.08).straight(10, 0.06, SlideSpec.LEGS)                    //  72.3 - 94.9
                    .arc(8, 90, 0.08).straight(14, 0.05, SlideSpec.LEGS)                     //  94.9 - 121.4
                    .arc(10, 60, 0.07).arc(10, -60, 0.07)                                    // 121.4 - 142.4
                    .straight(10, 0.05, SlideSpec.LEGS).straight(6, 0.04, SlideSpec.LEGS)     // 142.4 - 158.4
                    .obstacle(SlideSpec.OB_WHEEL, 16.5)
                    .obstacle(SlideSpec.OB_RAMP, 36.0)
                    .obstacle(SlideSpec.OB_HAMMER, 66.0)
                    .obstacle(SlideSpec.OB_HOOP, 87.0).obstacle(SlideSpec.OB_HOOP, 89.5).obstacle(SlideSpec.OB_HOOP, 92.0)
                    .obstacle(SlideSpec.OB_BUMPERS, 111.0).obstacle(SlideSpec.OB_BUMPERS, 115.0)
                    .obstacle(SlideSpec.OB_SPINNER, 119.0)
                    .obstacle(SlideSpec.OB_TUNNEL, 132.0)
                    .obstacle(SlideSpec.OB_WHEEL, 146.0)
                    .obstacle(SlideSpec.OB_HAMMER, 150.5)
                    .obstacle(SlideSpec.OB_HOOP, 155.0),

            //     Section 2: a long straight descent full of obstacles.
            new SlideSpec("dark_carnival_long_2").trough(2.2, 2.45, 72).start(0, 0).bank(6.0).deck(0, 0)
                    .colors(0x4A2A6A, 0x8E1424, 2.0).tints(0xE0B0FF, 0xE0B050).carnival().speed(0.36)
                    .straight(190, 0.04, SlideSpec.LEGS)
                    .obstacle(SlideSpec.OB_WHEEL, 12.0)
                    .obstacle(SlideSpec.OB_BUMPERS, 24.0).obstacle(SlideSpec.OB_BUMPERS, 28.0)
                    .obstacle(SlideSpec.OB_HOOP, 38.0).obstacle(SlideSpec.OB_HOOP, 40.5)
                    .obstacle(SlideSpec.OB_RAMP, 52.0)
                    .obstacle(SlideSpec.OB_HAMMER, 68.0)
                    .obstacle(SlideSpec.OB_SPINNER, 82.0)
                    .obstacle(SlideSpec.OB_HAMMER, 94.0)
                    .obstacle(SlideSpec.OB_TUNNEL, 110.0)
                    .obstacle(SlideSpec.OB_WHEEL, 128.0)
                    .obstacle(SlideSpec.OB_BUMPERS, 140.0).obstacle(SlideSpec.OB_BUMPERS, 144.0)
                    .obstacle(SlideSpec.OB_SPINNER, 156.0)
                    .obstacle(SlideSpec.OB_HOOP, 168.0).obstacle(SlideSpec.OB_HOOP, 170.5)
                    .obstacle(SlideSpec.OB_WHEEL, 182.0),

            //     Section 3: the last straight descent, more obstacles, the finish arch.
            new SlideSpec("dark_carnival_long_3").trough(2.2, 2.45, 72).start(0, 0).bank(6.0).deck(0, 0)
                    .colors(0x4A2A6A, 0x8E1424, 2.0).tints(0xE0B0FF, 0xE0B050).carnival().speed(0.36).finish()
                    .straight(186, 0.04, SlideSpec.LEGS).straight(6, 0.015)
                    .obstacle(SlideSpec.OB_HOOP, 10.0)
                    .obstacle(SlideSpec.OB_RAMP, 22.0)
                    .obstacle(SlideSpec.OB_WHEEL, 40.0)
                    .obstacle(SlideSpec.OB_HAMMER, 55.0)
                    .obstacle(SlideSpec.OB_SPINNER, 70.0)
                    .obstacle(SlideSpec.OB_BUMPERS, 84.0).obstacle(SlideSpec.OB_BUMPERS, 88.0)
                    .obstacle(SlideSpec.OB_TUNNEL, 104.0)
                    .obstacle(SlideSpec.OB_HAMMER, 122.0)
                    .obstacle(SlideSpec.OB_WHEEL, 136.0)
                    .obstacle(SlideSpec.OB_SPINNER, 150.0)
                    .obstacle(SlideSpec.OB_HAMMER, 162.0)
                    .obstacle(SlideSpec.OB_HOOP, 172.0).obstacle(SlideSpec.OB_HOOP, 174.5).obstacle(SlideSpec.OB_HOOP, 177.0),

            // 9..10 - the Dark Carnival slide with steep drops (~270 blocks, two sections): after every bend the trough
            //     plunges down at 60-66 degrees (1.4 blocks across, grade 2.3, smoothed over only 0.7 blocks so it stays
            //     steep), then a gentle stretch with obstacles until the next bend.
            //     Section 1: start platform, 4 bends, 4 drops.
            new SlideSpec("dark_carnival_drops_1").trough(2.2, 2.45, 72).start(0, 0).bank(6.0).deck(6.0, 1.3)
                    .colors(0x4A2A6A, 0x8E1424, 2.0).tints(0xE0B0FF, 0xE0B050).carnival().speed(0.36).gradeSmooth(0.7)
                    .straight(3, 0.03).straight(8, 0.33).straight(4, 0.04)          //   0 -  15
                    .arc(9, 90, 0.06).straight(1.4, 2.3)                             //  15 -  30.5  drop
                    .straight(12, 0.02, SlideSpec.LEGS)                               //  30.5 - 42.5
                    .arc(9, -90, 0.06).straight(1.4, 2.3)                            //  42.5 - 58.1 drop
                    .straight(12, 0.02, SlideSpec.LEGS)                               //  58.1 - 70.1
                    .arc(8, -90, 0.06).straight(1.4, 2.3)                            //  70.1 - 84.1 drop
                    .straight(12, 0.02, SlideSpec.LEGS)                               //  84.1 - 96.1
                    .arc(8, 90, 0.06).straight(1.4, 2.3)                             //  96.1 - 110.1 drop
                    .straight(22, 0.02, SlideSpec.LEGS)                               // 110.1 - 132.1
                    .obstacle(SlideSpec.OB_WHEEL, 11.5)
                    .obstacle(SlideSpec.OB_TUNNEL, 22.0)
                    .obstacle(SlideSpec.OB_RAMP, 33.5)
                    .obstacle(SlideSpec.OB_HAMMER, 63.0)
                    .obstacle(SlideSpec.OB_HOOP, 67.0).obstacle(SlideSpec.OB_HOOP, 69.0)
                    .obstacle(SlideSpec.OB_BUMPERS, 88.0).obstacle(SlideSpec.OB_BUMPERS, 91.5)
                    .obstacle(SlideSpec.OB_SPINNER, 95.0)
                    .obstacle(SlideSpec.OB_WHEEL, 115.0)
                    .obstacle(SlideSpec.OB_HAMMER, 120.0)
                    .obstacle(SlideSpec.OB_HOOP, 125.0).obstacle(SlideSpec.OB_HOOP, 127.5)
                    .obstacle(SlideSpec.OB_SPINNER, 130.5),

            //     Section 2: an S-bend and three more bends, each with its drop, then the finish.
            new SlideSpec("dark_carnival_drops_2").trough(2.2, 2.45, 72).start(0, 0).bank(6.0).deck(0, 0)
                    .colors(0x4A2A6A, 0x8E1424, 2.0).tints(0xE0B0FF, 0xE0B050).carnival().speed(0.36).gradeSmooth(0.7).finish()
                    .straight(3, 0.02)                                                //   0 -   3
                    .arc(9, -60, 0.06).arc(9, 60, 0.06).straight(1.4, 2.3)          //   3 -  23.2 drop
                    .straight(14, 0.02, SlideSpec.LEGS)                               //  23.2 - 37.2
                    .arc(9, 90, 0.06).straight(1.4, 2.3)                             //  37.2 - 52.7 drop
                    .straight(14, 0.02, SlideSpec.LEGS)                               //  52.7 - 66.7
                    .arc(9, -90, 0.06).straight(1.4, 2.3)                            //  66.7 - 82.3 drop
                    .straight(16, 0.02, SlideSpec.LEGS)                               //  82.3 - 98.3
                    .arc(8, 60, 0.06).arc(8, -60, 0.06).straight(1.4, 2.3)          //  98.3 - 116.4 drop
                    .straight(14, 0.02, SlideSpec.LEGS).straight(4, 0.015)            // 116.4 - 134.4
                    .obstacle(SlideSpec.OB_WHEEL, 27.0)
                    .obstacle(SlideSpec.OB_HAMMER, 33.0)
                    .obstacle(SlideSpec.OB_TUNNEL, 44.5)
                    .obstacle(SlideSpec.OB_SPINNER, 57.0)
                    .obstacle(SlideSpec.OB_BUMPERS, 61.0).obstacle(SlideSpec.OB_BUMPERS, 64.5)
                    .obstacle(SlideSpec.OB_RAMP, 85.5)
                    .obstacle(SlideSpec.OB_HOOP, 93.0).obstacle(SlideSpec.OB_HOOP, 95.5)
                    .obstacle(SlideSpec.OB_HAMMER, 121.0)
                    .obstacle(SlideSpec.OB_WHEEL, 126.0)
                    .obstacle(SlideSpec.OB_HOOP, 129.5),
    };

    /** Types that existed before the Dark Carnival slide: their collision palette must never change (saved worlds). */
    public static final int LEGACY_TYPES = 4;

    /**
     * Slides made of several sections placed end to end with the same facing: the 540-block one (kept for slides
     * already in a world) and the current one with steep drops.
     */
    public static final int[][] CHAINS = {{6, 7, 8}, {9, 10}};
    /** Per chain: anchor of each section relative to the first section's anchor (local blocks: x, z; same height). */
    public static final int[][][] CHAIN_OFFSETS = new int[CHAINS.length][][];

    public static final SlideGeometry[] GEO = new SlideGeometry[SPECS.length];

    static {
        for (int c = 0; c < CHAINS.length; c++) {
            CHAIN_OFFSETS[c] = new int[CHAINS[c].length][2];
            linkChain(CHAINS[c], CHAIN_OFFSETS[c]);
        }
        for (int i = 0; i < SPECS.length; i++) GEO[i] = new SlideGeometry(SPECS[i]);
    }

    /** The chain the type belongs to, or null. */
    public static int[] chainOf(int type) {
        for (int[] ch : CHAINS) for (int t : ch) if (t == type) return ch;
        return null;
    }

    /** Section offsets of the chain the type belongs to, or null. */
    public static int[][] offsetsOf(int type) {
        for (int c = 0; c < CHAINS.length; c++) for (int t : CHAINS[c]) if (t == type) return CHAIN_OFFSETS[c];
        return null;
    }

    /**
     * Fits the chain sections together: every section ends exactly where the next one starts (same height, same
     * spot, same heading), with all anchors at ground level. Heights are solved from the last section up: each
     * section ends as high above the ground as the rest of the chain still has to drop.
     */
    private static void linkChain(int[] chain, int[][] offset) {
        int n = chain.length;
        double[] drop = new double[n];
        for (int k = 0; k < n; k++) {
            SlideGeometry g = new SlideGeometry(SPECS[chain[k]], false);
            drop[k] = g.py[0] - g.py[g.n - 1];
        }
        double below = 0.5D;   // the end of the whole slide, just above the ground
        for (int k = n - 1; k >= 0; k--) {
            SPECS[chain[k]].endHeight(below);
            below += drop[k];
        }
        double ox = 0.0D, oz = 0.0D;   // where the current section starts, relative to the first anchor
        for (int k = 0; k < n; k++) {
            SlideSpec s = SPECS[chain[k]];
            if (k > 0) {
                int ax = (int) Math.round(ox), az = (int) Math.round(oz);
                offset[k][0] = ax;
                offset[k][1] = az;
                s.start(ox - ax, oz - az);
            }
            SlideGeometry g = new SlideGeometry(s, false);
            ox = offset[k][0] + g.px[g.n - 1];
            oz = offset[k][1] + g.pz[g.n - 1];
        }
    }

    /** Index of the type within its chain, or -1. */
    public static int chainIndex(int type) {
        int[] ch = chainOf(type);
        if (ch == null) return -1;
        for (int k = 0; k < ch.length; k++) if (ch[k] == type) return k;
        return -1;
    }

    public static int count() {
        return SPECS.length;
    }

    public static SlideGeometry get(int type) {
        return GEO[type >= 0 && type < GEO.length ? type : 0];
    }

    private SlideTypes() {
    }
}
