package prolog.devices;

import javafx.application.Platform;
import ru.prolog.etc.exceptions.runtime.PrologRuntimeException;
import ru.prolog.util.io.ErrorListener;

import java.io.PrintWriter;
import java.io.StringWriter;

public class ErrorsOutputDevice extends ProgramOutputDevice implements ErrorListener {
    public ErrorsOutputDevice() {
    }

    public ErrorsOutputDevice(String text) {
        super(text);
    }

    @Override
    public void prologRuntimeException(PrologRuntimeException e) {
        Platform.runLater(() -> appendText(e.toString()+"\n\n"));

    }

    @Override
    public void runtimeException(RuntimeException e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        Platform.runLater(() -> appendText(writer.toString()+"\n\n"));
    }
}
