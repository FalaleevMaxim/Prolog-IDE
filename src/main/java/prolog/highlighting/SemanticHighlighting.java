package prolog.highlighting;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import ru.prolog.syntaxmodel.recognizers.Lexer;
import ru.prolog.syntaxmodel.tree.AbstractNode;
import ru.prolog.syntaxmodel.tree.Node;
import ru.prolog.syntaxmodel.tree.Token;
import ru.prolog.syntaxmodel.tree.interfaces.Bracketed;
import ru.prolog.syntaxmodel.tree.interfaces.Named;
import ru.prolog.syntaxmodel.tree.interfaces.Separated;
import ru.prolog.syntaxmodel.tree.misc.NodeError;
import ru.prolog.syntaxmodel.tree.nodes.modules.ProgramNode;
import ru.prolog.syntaxmodel.tree.semantics.SemanticAnalyzer;
import ru.prolog.syntaxmodel.tree.semantics.SemanticInfo;
import ru.prolog.syntaxmodel.tree.semantics.attributes.SymbolValue;
import ru.prolog.syntaxmodel.tree.semantics.attributes.ToUsages;
import ru.prolog.syntaxmodel.tree.semantics.attributes.errors.AbstractSemanticError;
import ru.prolog.syntaxmodel.tree.semantics.attributes.warnings.AbstractSemanticWarning;

import java.util.*;
import java.util.stream.Collectors;

public class SemanticHighlighting implements Highlighter {
    private ProgramNode treeRoot;
    private final Map<Token, String> tokenNodeErrors = new HashMap<>();
    private final Map<Token, Collection<String>> cachedTokenStyles = new HashMap<>();
    private final Set<Token> lastHighlightedTokens = new HashSet<>();
    private SemanticAnalyzer semanticAnalyzer;

    @Override
    public HighlightingResult computeHighlighting(String text) {
        return new HighlightingResult(0, computeHighlightingFull(text));
    }

    @Override
    public StyleSpans<Collection<String>> computeHighlightingFull(String text) {
        cachedTokenStyles.clear();
        lastHighlightedTokens.clear();
        tokenNodeErrors.clear();
        Lexer lexer = new Lexer(text);
        treeRoot = new ProgramNode(null);
        treeRoot.parse(lexer);
        collectNodeErrors(treeRoot);
        semanticAnalyzer = new SemanticAnalyzer(treeRoot);
        semanticAnalyzer.performSemanticAnalysis();
        return buildStyleSpans(lexer);
    }

    private StyleSpans<Collection<String>> buildStyleSpans(Lexer lexer) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        lexer.setPointer(null);
        while (!lexer.isEnd()) {
            Token token = lexer.nextToken();
            if (token == null) break;
            List<String> styleClasses = new ArrayList<>();
            if (token.getTokenType() == null) {
                styleClasses.add("unknown");
            } else {
                if (tokenNodeErrors.containsKey(token)) styleClasses.add("nodeError");
                if (token.isPartial()) styleClasses.add("error");
                switch (token.getTokenType()) {
                    case LB:
                    case RB:
                        styleClasses.add("bracket");
                        break;
                    case RSQB:
                    case LSQB:
                    case TAILSEP:
                        styleClasses.add("sbracket");
                        break;
                    case DOT:
                    case COMMA:
                    case SEMICOLON:
                    case IF_SIGN:
                    case IF_KEYWORD:
                    case AND_KEYWORD:
                    case OR_KEYWORD:
                        styleClasses.add("rule_sep");
                        break;
                    case SINGLE_COMMENT:
                    case MULTILINE_COMMENT:
                        styleClasses.add("comment");
                        break;
                    case INTEGER:
                    case REAL:
                        styleClasses.add("number");
                        break;
                    case STRING:
                    case CHAR:
                        styleClasses.add("string");
                        break;
                    case VARIABLE:
                        styleClasses.add("variable");
                        break;
                    case SYMBOL:
                    case CUT_SIGN:
                        styleClasses.add("name");
                        break;
                    case ANONYMOUS:
                        styleClasses.add("anonymous");
                        break;
                    case INCLUDE_KEYWORD:
                    case DOMAINS_KEYWORD:
                    case DATABASE_KEYWORD:
                    case PREDICATES_KEYWORD:
                    case CLAUSES_KEYWORD:
                    case GOAL_KEYWORD:
                        styleClasses.add("header");
                        break;
                    case STAR_MULTIPLY:
                    case PLUS:
                    case MINUS:
                    case DIVIDE:
                    case GREATER:
                    case LESSER:
                    case EQUALS:
                        styleClasses.add("math");
                        break;
                }
                checkSemantic(token, styleClasses);
            }
            spansBuilder.add(styleClasses, token.length());
            cachedTokenStyles.put(token, styleClasses);
        }
        return spansBuilder.create();
    }

    private void checkSemantic(Token token, List<String> styleClasses) {
        for (Node node = token; token.parent() != null; node = node.parent()) {
            if(node == null) break;
            SemanticInfo semanticInfo = node.getSemanticInfo();
            List<AbstractSemanticError> errors = semanticInfo.getErrors();
            List<AbstractSemanticWarning> warnings = semanticInfo.getWarnings();
            if(!errors.isEmpty()) {
                checkNamedAndSetStyle(token, node, styleClasses, "semanticError");
            } else if(!warnings.isEmpty()) {
                checkNamedAndSetStyle(token, node, styleClasses, "warning");
            } else {
                ToUsages usages = semanticInfo.getAttribute(ToUsages.class);
                if(node instanceof Named && ((Named) node).getName() == token && usages!=null && usages.getUsages().isEmpty()) {
                    styleClasses.add("unused");
                }
                if(semanticInfo.getAttribute(SymbolValue.class) != null) {
                    styleClasses.add("string");
                }
            }
        }
    }

    private void checkNamedAndSetStyle(Token token, Node node, List<String> styleClasses, String style) {
        if(node instanceof Named && ((Named) node).getName() != token) {
            return;
        }
        styleClasses.add(style);
    }

    private void collectNodeErrors(AbstractNode node) {
        for (Map.Entry<Node, NodeError> nodeError : node.getErrors().entrySet()) {
            String text = nodeError.getValue().getText();
            Node key = nodeError.getKey();
            if (nodeError.getValue().isAfter()) {
                Token token = key.lastToken();
                addTokenError(token, text);
            } else {
                key.tokens().forEach(token -> addTokenError(token, text));
            }
        }
        node.children().stream()
                .filter(n -> n instanceof AbstractNode)
                .map(n -> (AbstractNode) n)
                .forEach(this::collectNodeErrors);
    }

    private void addTokenError(Token token, String error) {
        if (tokenNodeErrors.containsKey(token)) {
            tokenNodeErrors.put(token, tokenNodeErrors.get(token) + '\n' + error);
        } else {
            tokenNodeErrors.put(token, error);
        }
    }

    @Override
    public String getMessageForPos(int pos) {
        Token token = treeRoot.tokenByRelativePos(pos);
        if (token.getTokenType() == null) return "Unknown character " + token.getText();
        String tokenError = null;
        if (token.getHint() != null) tokenError = token.getHint().errorText;

        String nodeError = null;
        if (tokenNodeErrors.containsKey(token)) nodeError = tokenNodeErrors.get(token);

        String semanticError = null;
        String semanticWarning = null;
        if(token.parent() instanceof Named && ((Named) token.parent()).getName() == token) {
            AbstractNode parent = token.parent();
            semanticError = parent.getSemanticInfo().getErrors().stream().map(AbstractSemanticError::getMessage).collect(Collectors.joining("\n"));
            semanticWarning = parent.getSemanticInfo().getWarnings().stream().map(AbstractSemanticWarning::getMessage).collect(Collectors.joining("\n"));
        }

        StringBuilder error = new StringBuilder();
        if(tokenError != null) error.append(tokenError);
        if(nodeError != null) {
            if(error.length() > 0) error.append('\n');
            error.append(nodeError);
        }
        if(semanticError != null) {
            if(error.length() > 0) error.append('\n');
            error.append(semanticError);
        }
        if(semanticWarning != null) {
            if(error.length() > 0) error.append('\n');
            error.append(semanticWarning);
        }
        String s = error.toString();
        return s.isEmpty() ? null : s;
    }

    @Override
    public List<HighlightingResult> changeStylesOnCursor(int pos) {
        Map<Token, Collection<String>> styles = restoreLast();
        lastHighlightedTokens.clear();
        if (pos < treeRoot.length()) {
            Token token = treeRoot.tokenByRelativePos(pos);
            checkCursorStyleRules(styles, token);
        }
        pos -= 1;
        if (pos > 0 && pos < treeRoot.length()) {
            Token token = treeRoot.tokenByRelativePos(pos);
            checkCursorStyleRules(styles, token);
        }
        if (styles.isEmpty()) return null;

        List<HighlightingResult> results = new ArrayList<>();
        for (Map.Entry<Token, Collection<String>> tokenStyle : styles.entrySet()) {
            Token key = tokenStyle.getKey();
            StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
            spansBuilder.add(tokenStyle.getValue(), key.length());
            results.add(new HighlightingResult(key.startPos(), spansBuilder.create()));
        }
        return results;
    }

    private void checkCursorStyleRules(Map<Token, Collection<String>> styles, Token token) {
        checkBrackets(styles, token);
        checkSeparators(styles, token);
    }

    private void checkBrackets(Map<Token, Collection<String>> styles, Token token) {
        AbstractNode parent = token.parent();
        if (parent instanceof Bracketed) {
            Bracketed bracketed = (Bracketed) parent;
            Token otherBracket = null;
            if (bracketed.getLb() == token) {
                otherBracket = bracketed.getRb();
            } else if (bracketed.getRb() == token) {
                otherBracket = bracketed.getLb();
            }
            if (otherBracket != null) {
                styles.put(otherBracket, Collections.singleton("onCursor"));
                styles.put(token, Collections.singleton("onCursor"));
                lastHighlightedTokens.add(otherBracket);
                lastHighlightedTokens.add(token);
            }
        }
    }

    private void checkSeparators(Map<Token, Collection<String>> styles, Token token) {
        AbstractNode parent = token.parent();
        if (parent instanceof Separated) {
            Separated separated = (Separated) parent;
            List<Token> separators = separated.getSeparators();
            if (separators.contains(token)) {
                for (Token separator : separators) {
                    styles.put(separator, Collections.singleton("onCursor"));
                    lastHighlightedTokens.add(separator);
                }
            }
        }
    }

    private Map<Token, Collection<String>> restoreLast() {
        Map<Token, Collection<String>> styles = new HashMap<>();
        for (Token token : lastHighlightedTokens) {
            styles.put(token, cachedTokenStyles.get(token));
        }
        return styles;
    }
}
