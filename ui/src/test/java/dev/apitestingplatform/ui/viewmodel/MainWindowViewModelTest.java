package dev.apitestingplatform.ui.viewmodel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainWindowViewModelTest {
    @Test
    void exposesSafeInitialRequestAndResponseState() {
        MainWindowViewModel viewModel = new MainWindowViewModel();

        assertThat(viewModel.requestMethodProperty().get()).isEqualTo(HttpMethodOption.GET);
        assertThat(viewModel.operationInProgressProperty().get()).isFalse();
        assertThat(viewModel.responseStatusProperty().get()).isEqualTo("—");
        assertThat(viewModel.responseTimeProperty().get()).isEqualTo("—");
        assertThat(viewModel.responseSizeProperty().get()).isEqualTo("—");
    }

    @Test
    void tracksAnAsynchronousOperationLifecycle() {
        MainWindowViewModel viewModel = new MainWindowViewModel();

        viewModel.beginOperation();

        assertThat(viewModel.operationInProgressProperty().get()).isTrue();
        assertThat(viewModel.statusMessageProperty().get()).isEqualTo("Sending request…");

        viewModel.finishOperation("Ready");

        assertThat(viewModel.operationInProgressProperty().get()).isFalse();
        assertThat(viewModel.statusMessageProperty().get()).isEqualTo("Ready");
    }
}
