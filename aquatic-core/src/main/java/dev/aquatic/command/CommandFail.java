package dev.aquatic.command;

import dev.aquatic.util.Text;
import net.kyori.adventure.text.Component;

/** Throw from a command executor to abort and show the sender a message. */
public class CommandFail extends RuntimeException {

    private final transient Component component;

    /** @param miniMessage MiniMessage text, placeholders as in {@link Text#mm}. */
    public CommandFail(String miniMessage, Object... placeholders) {
        super(miniMessage, null, false, false);
        this.component = Text.mm(miniMessage, placeholders);
    }

    public CommandFail(Component component) {
        super(Text.plain(component), null, false, false);
        this.component = component;
    }

    public Component component() {
        return component;
    }
}
