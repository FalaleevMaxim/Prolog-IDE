package prolog.highlighting;

import javafx.scene.control.ContextMenu;
import org.fxmisc.richtext.model.StyleSpans;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Без подсветки синтаксиса
 */
public class NoHighlighting implements Highlighter {
    private volatile boolean noRuns;

    @Override
    public HighlightingResult computeHighlighting(String text) {
        int length = 0;
        if (noRuns) {
            length = text.length();
            noRuns = false;
        }
        return new HighlightingResult(0, StyleSpans.singleton(Collections.emptyList(), length));
    }

    @Override
    public StyleSpans<Collection<String>> computeHighlightingFull(String text) {
        return StyleSpans.singleton(Collections.emptyList(), text.length());
    }

    @Override
    public String getMessageForPos(int pos) {
        return null;
    }

    @Override
    public List<HighlightingResult> changeStylesOnCursor(int pos) {
        return null;
    }

    @Override
    public ContextMenu getContextMenu(int pos) {
        return null;
    }

    @Override
    public void close() {

    }
}
