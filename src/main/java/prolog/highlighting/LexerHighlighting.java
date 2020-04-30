package prolog.highlighting;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import ru.prolog.syntaxmodel.recognizers.Lexer;
import ru.prolog.syntaxmodel.tree.Token;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Подсветка по лексемам
 */
public class LexerHighlighting implements Highlighter {
    /**
     * Последний обработанный код
     */
    protected String lastParsedCode = "";

    /**
     * Первый токен при последнем парсинге
     */
    protected Token firstToken;

    /**
     * Последний токен при последнем парсинге
     */
    protected Token lastToken;

    @Override
    public HighlightingResult computeHighlighting(String text) {
        if (text.isEmpty()) return new HighlightingResult(0, StyleSpans.singleton(Collections.emptyList(), 0));

        ChangedCode changed = computeChange(text);
        Lexer lexer = getLexerForChangedText(text, changed);

        firstToken = null;
        lastToken = null;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int tokensLength = 0;
        while (!lexer.isClosed()) {
            Token token = lexer.nextToken();
            if (token == null) break;
            if (token.getTokenType() == null) {
                addSpan(spansBuilder, token, "unknown", tokensLength);
            } else {
                switch (token.getTokenType()) {
                    case LB:
                    case RB:
                        addSpan(spansBuilder, token, "bracket", tokensLength);
                        break;
                    case RSQB:
                    case LSQB:
                    case TAILSEP:
                        addSpan(spansBuilder, token, "sbracket", tokensLength);
                        break;
                    case DOT:
                    case COMMA:
                    case SEMICOLON:
                    case IF_SIGN:
                    case IF_KEYWORD:
                    case AND_KEYWORD:
                    case OR_KEYWORD:
                        addSpan(spansBuilder, token, "rule_sep", tokensLength);
                        break;
                    case SINGLE_COMMENT:
                    case MULTILINE_COMMENT:
                        addSpan(spansBuilder, token, "comment", tokensLength);
                        break;
                    case INTEGER:
                    case REAL:
                        addSpan(spansBuilder, token, "number", tokensLength);
                        break;
                    case STRING:
                    case CHAR:
                        addSpan(spansBuilder, token, "string", tokensLength);
                        break;
                    case VARIABLE:
                        addSpan(spansBuilder, token, "variable", tokensLength);
                        break;
                    case SYMBOL:
                    case CUT_SIGN:
                        addSpan(spansBuilder, token, "name", tokensLength);
                        break;
                    case ANONYMOUS:
                        addSpan(spansBuilder, token, "anonymous", tokensLength);
                        break;
                    case DOMAINS_KEYWORD:
                    case DATABASE_KEYWORD:
                    case PREDICATES_KEYWORD:
                    case CLAUSES_KEYWORD:
                    case GOAL_KEYWORD:
                        addSpan(spansBuilder, token, "header", tokensLength);
                        break;
                    case STAR_MULTIPLY:
                    case PLUS:
                    case MINUS:
                    case DIVIDE:
                    case GREATER:
                    case LESSER:
                    case EQUALS:
                        addSpan(spansBuilder, token, "math", tokensLength);
                        break;
                    default:
                        spansBuilder.add(Collections.emptyList(), token.length());
                }
            }
            tokensLength += token.length();
            if (firstToken == null) firstToken = token;
            lastToken = token;
        }
        if (tokensLength == 0) {
            spansBuilder.add(Collections.emptyList(), 0);
        }
        lastParsedCode = text;
        if(changed == null) return new HighlightingResult(0, spansBuilder.create());
        return new HighlightingResult(changed.firstChanged, spansBuilder.create());
    }

    @Override
    public StyleSpans<Collection<String>> computeHighlightingFull(String text) {
        lastParsedCode = "";
        firstToken = null;
        lastToken = null;
        return computeHighlighting(text).styleSpans;
    }

    @Override
    public String getMessageForPos(int pos) {
        Token token = firstToken;
        for (int i = 0; token != null; i += token.length(), token = token.getNext()) {
            if (i <= pos && i + token.length() > pos) {
                if(token.getTokenType() == null) return "Unknown character " + token.getText();
                if (token.getHint() != null) return token.getHint().errorText;
                return null;
            }
        }
        return null;
    }

    @Override
    public List<HighlightingResult> changeStylesOnCursor(int pos) {
        return null;
    }

    protected ChangedCode computeChange(String newText) {
        String oldText = lastParsedCode;
        if (oldText.isEmpty()) return null;

        // Индекс первого изменившегося символа в коде (с начала текста)
        int firstChanged;
        for (firstChanged = 0; firstChanged < oldText.length() && firstChanged < newText.length(); firstChanged++) {
            if (oldText.charAt(firstChanged) != newText.charAt(firstChanged)) break;
        }

        // Индекс последнего изменившегося символа в коде (с конца текста)
        int lastChanged;
        for (lastChanged = 0; lastChanged < oldText.length() - firstChanged && lastChanged < newText.length() - firstChanged; lastChanged++) {
            if (oldText.charAt(oldText.length() - 1 - lastChanged) != newText.charAt(newText.length() - 1 - lastChanged))
                break;
        }

        Token before = firstToken;
        if (before != null) {
            for (int i = 0; i < newText.length(); i += before.length(), before = before.getNext()) {
                if (before.getNext() == null || i + before.length() >= firstChanged) {
                    before = before.getPrev();
                    firstChanged = i;
                    break;
                }
            }
        }

        Token after = lastToken;
        if (after != null) {
            for (int i = 0; i < newText.length(); i += after.length(), after = after.getPrev()) {
                if (after.getNext() == null || i + after.length() >= lastChanged) {
                    after = after.getNext();
                    lastChanged = i;
                    break;
                }
            }
        }
        return new ChangedCode(firstChanged, lastChanged, before, after);
    }

    protected Lexer getLexerForChangedText(String newText, ChangedCode change) {
        if(change == null) return new Lexer(newText);
        return new Lexer(newText, change.before, change.after, change.firstChanged, newText.length() - change.firstChanged - change.lastChanged);
    }

    private void addSpan(StyleSpansBuilder<Collection<String>> spansBuilder, Token token, String style, int start) {
        if (token.isPartial()) {
            spansBuilder.add(Arrays.asList("error", style), token.length());
        } else {
            spansBuilder.add(Collections.singleton(style), token.length());
        }
    }

    private static class ChangedCode {
        final int firstChanged;
        final int lastChanged;
        final Token before;
        final Token after;

        private ChangedCode(int firstChanged, int lastChanged, Token before, Token after) {
            this.firstChanged = firstChanged;
            this.lastChanged = lastChanged;
            this.before = before;
            this.after = after;
        }
    }
}
