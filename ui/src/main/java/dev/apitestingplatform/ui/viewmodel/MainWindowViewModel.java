package dev.apitestingplatform.ui.viewmodel;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Presentation state for the main window. Application use cases will update this model
 * when request execution is implemented.
 */
public final class MainWindowViewModel {
    private final StringProperty requestUrl = new SimpleStringProperty("");
    private final ObjectProperty<HttpMethodOption> requestMethod =
            new SimpleObjectProperty<>(HttpMethodOption.GET);
    private final BooleanProperty operationInProgress = new SimpleBooleanProperty(false);
    private final StringProperty statusMessage = new SimpleStringProperty("Ready");
    private final StringProperty responseStatus = new SimpleStringProperty("—");
    private final StringProperty responseTime = new SimpleStringProperty("—");
    private final StringProperty responseSize = new SimpleStringProperty("—");

    public StringProperty requestUrlProperty() {
        return requestUrl;
    }

    public ObjectProperty<HttpMethodOption> requestMethodProperty() {
        return requestMethod;
    }

    public BooleanProperty operationInProgressProperty() {
        return operationInProgress;
    }

    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    public StringProperty responseStatusProperty() {
        return responseStatus;
    }

    public StringProperty responseTimeProperty() {
        return responseTime;
    }

    public StringProperty responseSizeProperty() {
        return responseSize;
    }

    public void beginOperation() {
        operationInProgress.set(true);
        statusMessage.set("Sending request…");
    }

    public void finishOperation(String message) {
        operationInProgress.set(false);
        statusMessage.set(message);
    }

    public void showMessage(String message) {
        statusMessage.set(message);
    }
}

