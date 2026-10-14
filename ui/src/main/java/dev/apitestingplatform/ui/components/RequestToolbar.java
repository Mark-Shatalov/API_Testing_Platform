package dev.apitestingplatform.ui.components;

import dev.apitestingplatform.ui.viewmodel.HttpMethodOption;
import dev.apitestingplatform.ui.viewmodel.MainWindowViewModel;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Method, URL, send, and cancellation controls for the active request. */
public final class RequestToolbar extends HBox {
    public RequestToolbar(
            MainWindowViewModel viewModel,
            Runnable sendAction,
            Runnable cancelAction) {
        ComboBox<HttpMethodOption> methodSelector = new ComboBox<>(
                FXCollections.observableArrayList(HttpMethodOption.values()));
        methodSelector.valueProperty().bindBidirectional(viewModel.requestMethodProperty());
        methodSelector.setPrefWidth(112);
        methodSelector.getStyleClass().add("method-selector");

        TextField urlInput = new TextField();
        urlInput.setPromptText("Enter request URL, for example https://api.example.com/users");
        urlInput.textProperty().bindBidirectional(viewModel.requestUrlProperty());
        urlInput.getStyleClass().add("url-input");
        HBox.setHgrow(urlInput, Priority.ALWAYS);

        ProgressIndicator progress = new ProgressIndicator();
        progress.setMaxSize(19, 19);
        progress.visibleProperty().bind(viewModel.operationInProgressProperty());
        progress.managedProperty().bind(progress.visibleProperty());

        Button sendButton = new Button("Send");
        sendButton.setDefaultButton(true);
        sendButton.setOnAction(event -> sendAction.run());
        sendButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> viewModel.requestUrlProperty().get().isBlank()
                        || viewModel.operationInProgressProperty().get(),
                viewModel.requestUrlProperty(),
                viewModel.operationInProgressProperty()));
        sendButton.getStyleClass().add("send-button");

        Button cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(event -> cancelAction.run());
        cancelButton.disableProperty().bind(viewModel.operationInProgressProperty().not());
        cancelButton.getStyleClass().add("cancel-button");

        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        getStyleClass().add("request-toolbar");
        getChildren().addAll(methodSelector, urlInput, progress, sendButton, cancelButton);
    }
}

