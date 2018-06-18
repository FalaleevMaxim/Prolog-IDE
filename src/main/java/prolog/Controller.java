package prolog;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import prolog.devices.ErrorsOutputDevice;
import prolog.devices.ProgramInputDevice;
import prolog.devices.ProgramOutputDevice;
import ru.prolog.compiler.CompileException;
import ru.prolog.compiler.PrologCompiler;
import ru.prolog.logic.context.program.BaseProgramContextDecorator;
import ru.prolog.logic.context.program.ProgramContext;
import ru.prolog.logic.model.exceptions.ModelStateException;
import ru.prolog.logic.model.program.Program;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.ResourceBundle;

public class Controller implements Initializable{
    public ProgramInputDevice programInput;
    public ProgramOutputDevice programOutput;
    public TextArea codeArea;
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
            codeArea.setText(new String(encoded, Charset.forName("UTF-8")));
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
        if(programThreadGroup==null) programThreadGroup = new ThreadGroup("program");
        programThread = new Thread(programThreadGroup, () -> programContext.execute(), "program", Integer.valueOf(stackSizeTF.getText())*1024);
        programThread.setUncaughtExceptionHandler((thread, throwable) -> {
            if(throwable instanceof StackOverflowError) {
                Platform.runLater(() -> errorsOutput.println("Stack overflow error!"));
            }else{
                Platform.runLater(() -> errorsOutput.runtimeException(new RuntimeException("Error in program thread", throwable)));
            }
            programStopped();
        });
        errorsOutput.println("Program running!");
        programThread.start();
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
        programThread.interrupt();
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
            codeArea.setText(new String(encoded, Charset.forName("UTF-8")));
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
}
