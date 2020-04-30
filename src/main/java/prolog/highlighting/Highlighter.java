package prolog.highlighting;

import org.fxmisc.richtext.model.StyleSpans;

import java.util.Collection;
import java.util.List;

/**
 * Интерфейс объекта, управляющего подсветкой синтаксиса
 */
public interface Highlighter {
    /**
     * Вычисляет подсветку синтаксиса для изменившегося кода в редакторе
     *
     * @param text Текст в редакторе
     * @return Разметка синтаксиса для изменившегося текста
     */
    HighlightingResult computeHighlighting(String text);

    /**
     * Вычисляет подсветку синтаксиса для всего кода в редакторе
     *
     * @param text Текст в редакторе
     * @return Разметка синтаксиса для всего текста
     */
    StyleSpans<Collection<String>> computeHighlightingFull(String text);

    /**
     * Получить текст (ошибки) для всплывающего сообщения при наведении на указанную позицию в тексте
     *
     * @param pos Номер символа в тексте.
     * @return Сообщение, которое следует отобразить, или {@code null} если такого сообщения нет.
     */
    String getMessageForPos(int pos);

    /**
     * Получить стили, которые нужно установить в текст при установке курсора в указанной позиции
     * @param pos Позиция курсора
     * @return Стили для участков текста
     */
    List<HighlightingResult> changeStylesOnCursor(int pos);

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
