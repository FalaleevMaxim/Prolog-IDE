package prolog.devices;

import ru.prolog.util.io.OutputDevice;

import javafx.scene.control.TextArea;

public class ProgramOutputDevice extends TextArea implements OutputDevice {
    public ProgramOutputDevice() {
    }

    public ProgramOutputDevice(String text) {
        super(text);
    }

    @Override
    public synchronized void print(String s) {
        appendText(s);
    }

    @Override
    public synchronized void println(String s) {
        appendText(s);
        appendText("\n");
    }
}