package prolog.highlighting;

import org.fxmisc.richtext.model.StyleSpans;

import java.util.Collections;

/**
 * Без подсветки синтаксиса
 */
public class NoHighlighting implements Highlighter {
    @Override
    public HighlightingResult computeHighlighting(String text) {
        return new HighlightingResult(0, StyleSpans.singleton(Collections.emptyList(), text.length()));
    }

    @Override
    public String getMessageForPos(int pos) {
        return null;
    }
}
