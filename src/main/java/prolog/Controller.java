package prolog;

import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.Initializable;
import javafx.geometry.Point2D;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.reactfx.Subscription;
import prolog.devices.ErrorsOutputDevice;
import prolog.devices.ProgramInputDevice;
import prolog.devices.ProgramOutputDevice;
import ru.prolog.compiler.CompileException;
import ru.prolog.compiler.PrologCompiler;
import ru.prolog.etc.exceptions.model.ModelStateException;
import ru.prolog.etc.exceptions.runtime.PrologRuntimeException;
import ru.prolog.model.program.Program;
import ru.prolog.runtime.context.program.BaseProgramContextDecorator;
import ru.prolog.runtime.context.program.ProgramContext;
import ru.prolog.syntaxmodel.recognizers.Lexer;
import ru.prolog.syntaxmodel.source.UnmodifiableStringSourceCode;
import ru.prolog.syntaxmodel.tree.Token;
import ru.prolog.util.io.ErrorListener;
import ru.prolog.util.io.OutputDevice;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Controller implements Initializable{
    public ProgramInputDevice programInput;
    public ProgramOutputDevice programOutput;
    public CodeArea codeArea;
    public Button stopBtn;
    public Button debugBtn;
    public Button runBtn;
    public VBox root;
    public ErrorsOutputDevice errorsOutput;
    public Label caretPos;
    public MenuItem runMenuItem;
    public MenuItem debugMenuItem;
    public MenuItem stopMenuItem;
    public TextField stackSizeTF;

    private File file;
    private boolean fileSaved = true;
    private ProgramContext programContext;
    private Thread programThread;
    private ThreadGroup programThreadGroup;
    private volatile boolean running = false;
    private Service<Boolean> programRunService;
    private Map<Integer, Token> errorTokens = new HashMap<>();

    public File getFile(){
        if(!fileSaved) saveFile();
        if(!fileSaved) return null;
        if(file==null) requestFileName();
        if(file==null) return null;
        return file;
    }

    public void setFile(File file) {
        this.file = file;
        try {
            byte[] encoded = Files.readAllBytes(file.toPath());
            codeArea.replaceText(new String(encoded, Charset.forName("UTF-8")));
            fileSaved = true;
        } catch (IOException e) {
            alertReadError(e);
        }
    }

    public boolean isFileSaved(){
        return fileSaved;
    }

    public void setFileSaved(boolean fileSaved) {
        if(this.fileSaved==fileSaved) return;
        this.fileSaved = fileSaved;
        Stage window = (Stage) root.getScene().getWindow();
        if(fileSaved) {
            String title = window.getTitle();
            if(title.charAt(title.length()-1)=='*') {
                window.setTitle(title.substring(0, title.length() - 1));
            }
        } else {
            window.setTitle(window.getTitle() + '*');
        }
    }

    public void onRunKeyPressed(MouseEvent mouseEvent) {
        run();
    }

    public void onDebugKeyPressed(MouseEvent mouseEvent) {
        debug();
    }

    private void run(){
        run(null);
    }

    private void run(String debugFile) {
        if(running) return;
        File f = getFile();
        if(f==null) return;
        PrologCompiler compiler = new PrologCompiler(f.getAbsolutePath(), debugFile);
        Program program;
        errorsOutput.clear();
        programOutput.clear();

        errorsOutput.println("Start compiling...");
        try {
            program = compiler.compileProgram();
        } catch (IOException e) {
            alertReadError(e);
            return;
        }

        if(!compiler.getExceptions().isEmpty()){
            for (CompileException e : compiler.getExceptions()) {
                errorsOutput.println(e.toString());
            }
            for (CompileException e : compiler.getExceptions()) {
                if(e.getInterval()==null) continue;
                codeArea.selectRange(e.getInterval().getStart(), e.getInterval().getEnd()+1);
                break;
            }
            return;
        }

        errorsOutput.println("Compile finished. Validating model...");
        Collection<ModelStateException> exceptions = program.exceptions();
        if(!exceptions.isEmpty()){
            for (ModelStateException e : exceptions) {
                errorsOutput.println(e.toString());
            }
            for (ModelStateException e : exceptions) {
                if(e.getInterval()==null) continue;
                codeArea.selectRange(e.getInterval().getStart(), e.getInterval().getEnd()+1);
                break;
            }
            return;
        }

        errorsOutput.println("Validating complete. Prepare for launch...");
        program.managers().getProgramManager().addOption(ctx->new BaseProgramContextDecorator(ctx) {
            @Override
            public boolean execute() {
                boolean r = ctx.execute();
                programStopped();
                return r;
            }
        });
        program.managers().getProgramManager().addOption(ctx->{
            //ctx.getOutputDevices().removeAll();
            ctx.getOutputDevices().add(programOutput);
            //ctx.getErrorListeners().removeAll();
            ctx.getErrorListeners().add(errorsOutput);
            ctx.setInputDevice(programInput);
            return ctx;
        });

        errorsOutput.println("Creating thread...");
        programRunning();
        programContext = ((Program)program.fix()).createContext();
        programRunService = new Service<Boolean>() {
            @Override
            protected Task<Boolean> createTask() {
                return new Task<Boolean>() {
                    @Override
                    protected Boolean call() {

                        return programContext.execute();
                    }
                };
            }
        };

        programRunService.setOnRunning(event -> errorsOutput.println("Program running!"));
        programRunService.setOnFailed(event -> {
            Throwable exception = event.getSource().getException();
            if(exception instanceof StackOverflowError) {
                errorsOutput.println("Stack overflow error!");
            } else {
                errorsOutput.runtimeException(new RuntimeException("Error in program thread", exception));
            }
            programStopped();
        });
        programRunService.setOnSucceeded(event -> {
                errorsOutput.println("Program finished with result: " + event.getSource().getValue());
                programStopped();
        });
        programRunService.setOnCancelled(event -> {
            errorsOutput.println("Program terminated.");
            programStopped();
        });
        programRunService.start();
    }

    private void debug() {
        if(running) return;
        FileChooser chooser = new FileChooser();
        chooser.setInitialDirectory(Paths.get("").toAbsolutePath().toFile());
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All files", "*.*"),
                new FileChooser.ExtensionFilter("Text files", "*.txt"));
        File debug = chooser.showSaveDialog(root.getScene().getWindow());
        if(debug==null) return;
        run(debug.getAbsolutePath());
    }

    public void onStopKeyPressed(MouseEvent mouseEvent) {
        stop();
    }

    private void stop() {
        errorsOutput.println("Terminating program...");
        programRunService.cancel();
    }

    public boolean saveFile(){
        if(file==null) requestFileName();
        if(file==null) return false;
        try(PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8")))) {
            String code = codeArea.getText();
            pw.write(code);
            setFileSaved(true);
        } catch (FileNotFoundException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setHeaderText("Error writing file");
            alert.getDialogPane().setExpandableContent(new ScrollPane(new TextArea(e.toString())));
            alert.showAndWait();
            return false;
        }
        return true;
    }

    private void programRunning(){
        running = true;
        runBtn.setDisable(true);
        debugBtn.setDisable(true);
        stopBtn.setDisable(false);
        runMenuItem.setDisable(true);
        debugMenuItem.setDisable(true);
        stopMenuItem.setDisable(false);
    }

    private void programStopped(){
        programContext = null;
        runBtn.setDisable(false);
        debugBtn.setDisable(false);
        stopBtn.setDisable(true);
        runMenuItem.setDisable(false);
        debugMenuItem.setDisable(false);
        stopMenuItem.setDisable(true);
        errorsOutput.println("Program finished. ");
        running = false;
    }

    private boolean requestFileName(){
        return requestFileName(true);
    }

    private boolean requestFileName(boolean save){
        FileChooser chooser = new FileChooser();
        chooser.setInitialDirectory(Paths.get("").toAbsolutePath().toFile());
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All files", "*.*"),
                new FileChooser.ExtensionFilter("Prolog code files", "*.pro"),
                new FileChooser.ExtensionFilter("Text files", "*.txt"));
        File f = save?
                chooser.showSaveDialog(root.getScene().getWindow()):
                chooser.showOpenDialog(root.getScene().getWindow());
        if(f!=null){
            if(!f.exists()) {
                try {
                    f.createNewFile();
                } catch (IOException e) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setHeaderText("Error creating file");
                    alert.getDialogPane().setExpandableContent(new ScrollPane(new TextArea(e.toString())));
                    alert.showAndWait();
                    return false;
                }
            }
            file = f;
            ((Stage)root.getScene().getWindow()).setTitle(f.getName());
            return true;
        }
        return false;
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        codeArea.textProperty().addListener((observableValue, s, s2) -> setFileSaved(false));
        codeArea.textProperty().addListener((observableValue, s, s2) -> updateCaretPos(codeArea.getCaretPosition()));
        codeArea.caretPositionProperty().addListener((observable, oldValue, newValue) -> {
            int pos = newValue.intValue();
            updateCaretPos(pos);
        });
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        final Pattern whiteSpace = Pattern.compile( "^\\s+" );
        codeArea.addEventHandler( KeyEvent.KEY_PRESSED, KE ->
        {
            if ( KE.getCode() == KeyCode.ENTER ) {
                int caretPosition = codeArea.getCaretPosition();
                int currentParagraph = codeArea.getCurrentParagraph();
                Matcher m0 = whiteSpace.matcher( codeArea.getParagraph( currentParagraph-1 ).getSegments().get( 0 ) );
                if ( m0.find() ) Platform.runLater( () -> codeArea.insertText( caretPosition, m0.group() ) );
            }
        });
        Subscription cleanupWhenNoLongerNeedIt = codeArea

                // plain changes = ignore style changes that are emitted when syntax highlighting is reapplied
                // multi plain changes = save computation by not rerunning the code multiple times
                //   when making multiple changes (e.g. renaming a method at multiple parts in file)
                .multiPlainChanges()

                // do not emit an event until 500 ms have passed since the last emission of previous stream
                .successionEnds(Duration.ofMillis(500))

                // run the following code block when previous stream emits an event
                .subscribe(ignore -> codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText())));
        codeArea.getStylesheets().add(getClass().getResource("/editor.css").toExternalForm());

        Popup popup = new Popup();
        Label popupMsg = new Label();
        popupMsg.setStyle(
                "-fx-background-color: grey;" +
                "-fx-text-fill: darkred;" +
                "-fx-padding: 5;");
        popup.getContent().add(popupMsg);

        codeArea.setMouseOverTextDelay(Duration.ofSeconds(1));
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, e -> {
            if(errorTokens.isEmpty()) return;
            int chIdx  = e.getCharacterIndex();
            Point2D pos = e.getScreenPosition();
            Token token = null;
            for (Map.Entry<Integer, Token> tokenPos : errorTokens.entrySet()) {
                if(tokenPos.getKey() > chIdx) continue;
                int dist = chIdx - tokenPos.getKey();
                if(dist <= tokenPos.getValue().length()) {
                    token = tokenPos.getValue();
                    break;
                }
            }
            if(token == null) return;
            popupMsg.setText(token.getHint().errorText);
            popup.show(codeArea, pos.getX(), pos.getY() + 10);
        });
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, e -> {
            popup.hide();
        });

        programInput.setListener(new ProgramInputDevice.InputListener() {
            @Override
            public void onReadChar(char c) {
                if(c==27) return;
                if(programContext!=null) programContext.getOutputDevices().println(String.valueOf(c));
            }

            @Override
            public void onReadString(String s) {
                if(s==null) return;
                if(programContext!=null) programContext.getOutputDevices().println(s);
            }
        });
        stackSizeTF.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*") || Integer.valueOf(newValue)<100) {
                stackSizeTF.setText(oldValue);
            }
        });
    }

    private void updateCaretPos(int pos) {
        String text = codeArea.getText();
        text = text.substring(0, pos>text.length()?text.length():pos);
        String[] lines = text.split("\n");
        int line = lines.length;
        int inLine = lines.length==0?0:lines[lines.length-1].length();
        caretPos.setText(Integer.toString(line)+":"+inLine);
    }

    public void onSaveAction(ActionEvent actionEvent) {
        saveFile();
    }

    public void onSaveAsAction(ActionEvent actionEvent) {
        if( !requestFileName()) return;
        saveFile();
    }

    public void newFile(ActionEvent actionEvent) {
        if(!fileSaved && !codeArea.getText().equals("")){
            if(!saveFile()) return;
        }
        codeArea.clear();
        fileSaved = true;
        file = null;
        ((Stage)root.getScene().getWindow()).setTitle(":new file:");
    }

    public void loadFile(ActionEvent actionEvent) {
        if(!fileSaved && !codeArea.getText().equals("")){
            if(!saveFile()) return;
        }
        if(!requestFileName(false)) return;
        try {
            byte[] encoded = Files.readAllBytes(file.toPath());
            codeArea.replaceText(new String(encoded, Charset.forName("UTF-8")));
            setFileSaved(true);
        } catch (IOException e) {
            alertReadError(e);
        }
    }

    public void runMenuAction(ActionEvent actionEvent) {
        run();
    }

    public void debugMenuAction(ActionEvent actionEvent) {
        debug();
    }

    private void alertReadError(IOException e) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText("Error reading file");
        alert.getDialogPane().setExpandableContent(new ScrollPane(new TextArea(e.toString())));
        alert.showAndWait();
    }

    public void stopMenuAction(ActionEvent actionEvent) {
        stop();
    }

    private StyleSpans<Collection<String>> computeHighlighting(String text) {
        errorTokens.clear();
        Lexer lexer = new Lexer(new UnmodifiableStringSourceCode(text), text);
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int tokensLength = 0;
        while (true){
            Token token = lexer.nextToken();
            if(token == null) break;
            if(token.getTokenType() == null) {
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
        }
        if(tokensLength == 0) {
            spansBuilder.add(Collections.emptyList(), text.length());
        }
        return spansBuilder.create();
    }

    private void addSpan(StyleSpansBuilder<Collection<String>> spansBuilder, Token token, String style, int start) {
        if(token.isPartial()) {
            spansBuilder.add(Arrays.asList("error", style), token.length());
        } else {
            spansBuilder.add(Collections.singleton(style), token.length());
        }
        if(token.getHint()!=null && token.getHint().errorText != null) {
            errorTokens.put(start, token);
        }
    }
}