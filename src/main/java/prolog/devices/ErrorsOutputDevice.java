package prolog.devices;

import ru.prolog.logic.exceptions.PrologRuntimeException;
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
        appendText(e.toString());
        appendText("\n\n");
    }

    @Override
    public void runtimeException(RuntimeException e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        appendText(writer.toString());
        appendText("\n\n");
    }
}
