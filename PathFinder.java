package me.prime.callbot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

final class PathFinder {

    private static final class Node {
        final int x, y, z;
        final Node parent;
        final double g, f;

        Node(int x, int y, int z, Node parent, double g, double f) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }
    }

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private PathFinder() {
    }

    static List<Vector> find(World w, Location from, Location to, int maxNodes) {
        int sx = from.getBlockX();
        int sy = from.getBlockY();
        int sz = from.getBlockZ();
        int tx = to.getBlockX();
        int tz = to.getBlockZ();
        int ty = goalY(w, tx, to.getBlockY(), tz);

        Map<Long, Node> seen = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        Map<Long, Boolean> chunks = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<Node>(Comparator.comparingDouble((Node n) -> n.f));

        Node start = new Node(sx, sy, sz, null, 0, heur(sx, sy, sz, tx, ty, tz));
        open.add(start);
        seen.put(key(sx, sy, sz), start);
        Node best = start;
        double bestH = start.f;
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            if (!closed.add(key(cur.x, cur.y, cur.z))) {
                continue;
            }
            expanded++;
            double h = heur(cur.x, cur.y, cur.z, tx, ty, tz);
            if (h < bestH) {
                bestH = h;
                best = cur;
            }
            if (Math.hypot(cur.x - tx, cur.z - tz) <= 1.5 && Math.abs(cur.y - ty) <= 2) {
                best = cur;
                break;
            }
            for (int[] d : DIRS) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];
                if (Math.abs(nx - sx) > 32 || Math.abs(nz - sz) > 32) {
                    continue;
                }
                if (!chunkLoaded(w, chunks, nx, nz)) {
                    continue;
                }
                boolean diag = d[0] != 0 && d[1] != 0;
                int ny = step(w, cur, nx, nz, diag);
                if (ny == Integer.MIN_VALUE) {
                    continue;
                }
                double cost = (diag ? 1.414 : 1.0) + (ny > cur.y ? 0.8 : 0.0) + (ny < cur.y ? 0.3 * (cur.y - ny) : 0.0);
                double g = cur.g + cost;
                long nk = key(nx, ny, nz);
                Node ex = seen.get(nk);
                if (ex != null && ex.g <= g) {
                    continue;
                }
                Node nn = new Node(nx, ny, nz, cur, g, g + heur(nx, ny, nz, tx, ty, tz));
                seen.put(nk, nn);
                open.add(nn);
            }
        }

        LinkedList<Vector> out = new LinkedList<>();
        for (Node n = best; n != null && n.parent != null; n = n.parent) {
            out.addFirst(new Vector(n.x + 0.5, n.y, n.z + 0.5));
        }
        return out;
    }

    private static int step(World w, Node c, int nx, int nz, boolean diag) {
        int y = c.y;
        if (diag) {
            if (!free(w, nx, y, c.z) || !free(w, c.x, y, nz)) {
                return Integer.MIN_VALUE;
            }
            return standable(w, nx, y, nz) ? y : Integer.MIN_VALUE;
        }
        int[] options = {y, y + 1, y - 1, y - 2, y - 3};
        for (int cy : options) {
            if (!standable(w, nx, cy, nz)) {
                continue;
            }
            if (cy > y && !passable(w, c.x, y + 2, c.z)) {
                continue;
            }
            if (cy < y) {
                boolean ok = true;
                for (int j = y + 1; j > cy + 1; j--) {
                    if (!passable(w, nx, j, nz)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
            }
            return cy;
        }
        return Integer.MIN_VALUE;
    }

    private static int goalY(World w, int x, int y, int z) {
        for (int cy = y; cy >= y - 8; cy--) {
            if (standable(w, x, cy, z)) {
                return cy;
            }
        }
        return y;
    }

    private static boolean free(World w, int x, int y, int z) {
        return passable(w, x, y, z) && passable(w, x, y + 1, z);
    }

    private static boolean standable(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() + 1 || y >= w.getMaxHeight() - 3) {
            return false;
        }
        return passable(w, x, y, z) && passable(w, x, y + 1, z) && groundMat(w.getType(x, y - 1, z));
    }

    private static boolean passable(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) {
            return false;
        }
        Material m = w.getType(x, y, z);
        if (m.isSolid()) {
            return false;
        }
        return m != Material.LAVA && m != Material.WATER && m != Material.COBWEB && m != Material.FIRE
                && m != Material.SOUL_FIRE && m != Material.SWEET_BERRY_BUSH && m != Material.POWDER_SNOW;
    }

    private static boolean groundMat(Material m) {
        return m.isSolid() && m != Material.MAGMA_BLOCK && m != Material.CACTUS;
    }

    private static boolean chunkLoaded(World w, Map<Long, Boolean> cache, int x, int z) {
        long k = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
        Boolean v = cache.get(k);
        if (v == null) {
            v = w.isChunkLoaded(x >> 4, z >> 4);
            cache.put(k, v);
        }
        return v;
    }

    private static double heur(int x, int y, int z, int tx, int ty, int tz) {
        return Math.hypot(x - tx, z - tz) + Math.abs(y - ty) * 0.5;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }
}
