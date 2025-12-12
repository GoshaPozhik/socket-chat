package chat.client;

import java.awt.*;

public final class ColorUtil {
    private ColorUtil() {}

    public static Color colorForName(String name) {
        int h = Math.abs(name.hashCode());
        int r = 80 + (h % 150);
        int g = 80 + ((h / 7) % 150);
        int b = 80 + ((h / 13) % 150);
        return new Color(r, g, b);
    }
}
