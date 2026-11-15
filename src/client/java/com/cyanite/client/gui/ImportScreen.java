package com.cyanite.client.gui;

import com.cyanite.client.config.Imports;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Asks whether to copy other clients' setups into Cyanite ({@link Imports}), then says what came over.
 * Shown on the title screen the first time one is found, and from Profiles' "Import". With more than one
 * client, each has a tick box.
 */
public class ImportScreen extends Screen {
    private static final int LINE = 11;
    private static final int GAP = 6;
    private static final int BOX_ROW = 20;
    private static final int BODY = 0xFFB8C0CC;
    private static final int DIM = 0xFF8A93A3;

    private final Screen parent;
    private final List<Imports.Preview> found;
    private final Set<Imports.Source> chosen = EnumSet.noneOf(Imports.Source.class);
    private Imports.Outcome outcome;

    /** A line of text, a blank, or a client's tick box; each as tall as it draws. */
    private record Line(FormattedCharSequence text, int color, Imports.Source box, int height) {
    }

    private final List<Line> lines = new ArrayList<>();
    private int top;
    private int maxListLines;
    private Button copyButton;

    /** Every client here with something to copy, asked about before or not. */
    public ImportScreen(Screen parent) {
        this(parent, Imports.found(true));
    }

    public ImportScreen(Screen parent, List<Imports.Preview> found) {
        super(Component.literal("Import settings"));
        this.parent = parent;
        this.found = found;
        for (Imports.Preview p : found) chosen.add(p.source());
    }

    @Override
    protected void init() {
        // Long lists are cut shorter until it all fits.
        for (maxListLines = 3; ; maxListLines--) {
            lines.clear();
            if (outcome == null) ask();
            else done();
            if (contentHeight() + GAP * 2 + 20 <= height - 20 || maxListLines == 1) break;
        }

        int textH = contentHeight();
        top = Math.max(10, (height - textH - GAP * 2 - 20) / 2);
        int y = top;
        for (Line line : lines) {
            if (line.box() != null) addBox(line.box(), y);
            y += line.height();
        }
        y += GAP * 2;
        if (outcome == null && !found.isEmpty()) {
            copyButton = addRenderableWidget(Button.builder(Component.literal("Copy them"), b -> copy()).bounds(width / 2 - 102, y, 100, 20).build());
            copyButton.active = !chosen.isEmpty();
            addRenderableWidget(Button.builder(Component.literal("No thanks"), b -> onClose()).bounds(width / 2 + 2, y, 100, 20).build());
        } else {
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, y, 200, 20).build());
        }
    }

    private void addBox(Imports.Source source, int y) {
        Component label = Component.literal("From " + source.title);
        int w = 17 + 4 + font.width(label);
        addRenderableWidget(Checkbox.builder(label, font).pos(width / 2 - w / 2, y + (BOX_ROW - 17) / 2).selected(chosen.contains(source))
            .onValueChange((box, on) -> {
                if (on) chosen.add(source);
                else chosen.remove(source);
                if (copyButton != null) copyButton.active = !chosen.isEmpty();
            }).build());
    }

    private int contentHeight() {
        int h = 0;
        for (Line line : lines) h += line.height();
        return h;
    }

    // ---- Asking

    private void ask() {
        if (found.isEmpty()) {
            heading("Nothing to copy", Theme.accentText());
            para("Nothing in the other clients' folders has a match in Cyanite yet.", BODY);
            return;
        }
        boolean one = found.size() == 1;
        boolean modules = found.stream().anyMatch(p -> !p.modules().isEmpty() || !p.profiles().isEmpty());
        boolean waypoints = found.stream().anyMatch(p -> p.waypoints() > 0);
        heading(one ? found.get(0).source().title + (modules ? " settings found" : " waypoints found") : "Other clients' settings found", Theme.accentText());
        if (modules) {
            para("Copy them into Cyanite? Each module is matched to the Cyanite one that does the same job: "
                + "what you had on, your keys and the settings that mean the same thing come over."
                + (waypoints ? " Waypoints go onto the same server's list." : ""), BODY);
        } else {
            para("Copy them into Cyanite's Waypoints? Each goes onto the same server's or world's list.", BODY);
        }
        gap();

        for (Imports.Preview p : found) {
            // One client: a gap between each part. More: each client's parts together under its tick box.
            if (!one) lines.add(new Line(null, 0, p.source(), BOX_ROW));
            if (!p.modules().isEmpty()) {
                list("Ready to copy: ", p.modules(), Theme.TEXT);
                if (one) gap();
            }
            if (!p.profiles().isEmpty()) {
                boolean single = p.profiles().size() == 1;
                para("Your " + p.source().shortName + (single ? " profile " : " profiles ") + join(p.profiles())
                    + (single ? " becomes a Cyanite profile" : " become Cyanite profiles") + " to load when you like.", Theme.TEXT);
                if (one) gap();
            }
            if (p.waypoints() > 0) {
                para(waypointCount(p.waypoints(), p.places()), Theme.TEXT);
                if (one) gap();
            }
            if (!p.leftOut().isEmpty()) {
                list("No match in Cyanite: ", p.leftOut(), DIM);
                if (one) gap();
            }
            if (!one) gap();
        }
        if (found.stream().anyMatch(p -> !p.modules().isEmpty())) para("What you have now is kept as a profile first.", DIM);
        para("Not now? Profiles has an Import button.", DIM);
    }

    private void copy() {
        List<Imports.Source> copying = new ArrayList<>();
        List<Imports.Source> skipped = new ArrayList<>();
        for (Imports.Preview p : found) (chosen.contains(p.source()) ? copying : skipped).add(p.source());
        outcome = Imports.copy(copying);
        if (!skipped.isEmpty()) Imports.decline(skipped);
        rebuildWidgets();
    }

    // ---- What happened

    private void done() {
        if (outcome.problem() != null) {
            heading("Couldn't copy", Theme.DANGER);
            para(outcome.problem(), BODY);
            return;
        }
        boolean one = outcome.results().size() == 1;
        heading(one ? "Copied from " + outcome.results().get(0).source().shortName : "Copied", Theme.accentText());
        for (Imports.Result r : outcome.results()) {
            if (!one) lines.add(new Line(Component.literal(r.source().title).getVisualOrderText(), Theme.TEXT, null, LINE));
            if (r.problem() != null) {
                para(r.problem(), Theme.DANGER);
                gap();
                continue;
            }
            if (!r.modules().isEmpty()) {
                list("", r.modules(), BODY);
                para(r.on() + " switched on, " + r.binds() + (r.binds() == 1 ? " key, " : " keys, ")
                    + r.settings() + (r.settings() == 1 ? " setting" : " settings"), BODY);
            }
            if (!r.keysTaken().isEmpty()) list("Keys already used here, left off: ", r.keysTaken(), DIM);
            if (!r.profiles().isEmpty()) {
                boolean single = r.profiles().size() == 1;
                para("Added " + (single ? "the profile " : "the profiles ") + join(r.profiles())
                    + (single ? ": load it in Profiles." : ": load them in Profiles."), BODY);
            }
            if (r.waypoints() > 0) para("Added " + waypointCount(r.waypoints(), r.places()), BODY);
            if (r.modules().isEmpty() && r.profiles().isEmpty() && r.waypoints() == 0) {
                para(r.source().hasModules() ? "Nothing here has a match in Cyanite, or it's all here already." : "No new waypoints: they're all here already.", BODY);
            }
            gap();
        }
        if (outcome.waypointsOn()) para("Waypoints is switched on to show them.", DIM);
        if (outcome.backup() != null) para("What you had before is the profile " + outcome.backup() + ".", DIM);
    }

    @Override
    public void onClose() {
        // Either answer counts: these aren't asked about again. Profiles can still start it.
        if (outcome == null && !found.isEmpty()) Imports.decline(found.stream().map(Imports.Preview::source).toList());
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y = top;
        for (Line line : lines) {
            if (line.text() != null) g.drawCenteredString(font, line.text(), width / 2, y, line.color());
            y += line.height();
        }
    }

    // ---- Laying out the text

    private int wrapWidth() {
        return Math.max(120, Math.min(width - 40, 340));
    }

    private void heading(String text, int color) {
        lines.add(new Line(Component.literal(text).getVisualOrderText(), color, null, LINE));
        gap();
    }

    private void para(String text, int color) {
        for (FormattedCharSequence part : font.split(Component.literal(text), wrapWidth())) lines.add(new Line(part, color, null, LINE));
    }

    /** A list of names after a label, cut to a few lines with "and N more". */
    private void list(String label, List<String> names, int color) {
        for (int shown = names.size(); shown >= 1; shown--) {
            String text = label + (shown == names.size() ? join(names)
                : String.join(", ", names.subList(0, shown)) + " and " + (names.size() - shown) + " more");
            List<FormattedCharSequence> parts = font.split(Component.literal(text), wrapWidth());
            if (parts.size() <= maxListLines || shown == 1) {
                for (FormattedCharSequence part : parts) lines.add(new Line(part, color, null, LINE));
                return;
            }
        }
    }

    private void gap() {
        lines.add(new Line(null, 0, null, 8));
    }

    private static String waypointCount(int waypoints, int places) {
        return waypoints + (waypoints == 1 ? " waypoint" : " waypoints") + " for " + places
            + (places == 1 ? " server or world" : " servers and worlds");
    }

    private static String join(List<String> names) {
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }
}
