package dev.jawsh.xaerodeck;

import java.util.ArrayList;
import java.util.List;

/** Shared route generators used by the API and Xaero map integration. */
public class RouteGen {
    public static List<double[]> circle(double cx, double cz, double radius, int points) {
        List<double[]> pts = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            pts.add(new double[]{cx + radius * Math.cos(a), cz + radius * Math.sin(a)});
        }
        return pts;
    }

    public static List<double[]> spiral(double cx, double cz, double spacing, int loops) {
        List<double[]> pts = new ArrayList<>();
        double b = spacing / (2 * Math.PI);
        double theta = 2 * Math.PI * 0.75;
        double maxTheta = 2 * Math.PI * loops;
        while (theta <= maxTheta && pts.size() < 512) {
            double r = b * theta;
            pts.add(new double[]{cx + r * Math.cos(theta), cz + r * Math.sin(theta)});
            theta += Math.max(0.12, 120.0 / Math.max(r, 40));
        }
        return pts;
    }
}
