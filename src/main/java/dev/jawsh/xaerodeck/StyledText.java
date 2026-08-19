package dev.jawsh.xaerodeck;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.Optional;

/** Flattens a chat Component into styled spans the app can render with colors. */
public class StyledText {
    /** Returns [{t:"text", c:"#RRGGBB"?, b:1?, i:1?, u:1?, s:1?}, ...] */
    public static JsonArray spans(Component component) {
        JsonArray spans = new JsonArray();
        component.visit((style, text) -> {
            if (!text.isEmpty()) {
                JsonObject span = new JsonObject();
                span.addProperty("t", text);
                if (style.getColor() != null) {
                    span.addProperty("c", String.format("#%06X", style.getColor().getValue() & 0xFFFFFF));
                }
                if (style.isBold()) span.addProperty("b", 1);
                if (style.isItalic()) span.addProperty("i", 1);
                if (style.isUnderlined()) span.addProperty("u", 1);
                if (style.isStrikethrough()) span.addProperty("s", 1);
                spans.add(span);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return spans;
    }
}
