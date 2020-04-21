package prolog.util;

import ru.prolog.syntaxmodel.tree.Token;

/**
 * Данные, сохраняемые для или после распознавания кода в редакторе.
 */
public class CodeParseInfo {
    /**
     * Последний обработанный код
     */
    private String lastParsedCode = "";

    /**
     * Первый токен при последнем парсинге
     */
    private Token firstToken;

    /**
     * Последний токен при последнем парсинге
     */
    private Token lastToken;

    public String getLastParsedCode() {
        return lastParsedCode;
    }

    public CodeParseInfo setLastParsedCode(String lastParsedCode) {
        this.lastParsedCode = lastParsedCode;
        return this;
    }

    public Token getFirstToken() {
        return firstToken;
    }

    public CodeParseInfo setFirstToken(Token firstToken) {
        this.firstToken = firstToken;
        return this;
    }

    public Token getLastToken() {
        return lastToken;
    }

    public CodeParseInfo setLastToken(Token lastToken) {
        this.lastToken = lastToken;
        return this;
    }
}
