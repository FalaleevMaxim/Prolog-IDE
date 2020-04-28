package prolog.highlighting;

import org.fxmisc.richtext.model.StyleSpans;

import java.util.Collection;

/**
 * Интерфейс объекта, управляющего подсветкой синтаксиса
 */
public interface Highlighter {
    /**
     * Вычисляет подсветку синтаксиса для всего кода в редакторе
     *
     * @param text Текст в редакторе
     * @return Разметка синтаксиса для всего текста
     */
    HighlightingResult computeHighlighting(String text);

    /**
     * Получить текст (ошибки) для всплывающего сообщения при наведении на указанную позицию в тексте
     *
     * @param pos Номер символа в тексте.
     * @return Сообщение, которое следует отобразить, или {@code null} если такого сообщения нет.
     */
    String getMessageForPos(int pos);

    /**
     * Результат подсветки синтаксиса
     */
    class HighlightingResult {
        /**
         * Разметка для участка текста
         */
        public final StyleSpans<Collection<String>> styleSpans;

        /**
         * Индекс начала участка текста, для которого установить {@link #styleSpans}.
         */
        public final int start;

        public HighlightingResult(int start, StyleSpans<Collection<String>> styleSpans) {
            this.start = start;
            this.styleSpans = styleSpans;
        }
    }
}
