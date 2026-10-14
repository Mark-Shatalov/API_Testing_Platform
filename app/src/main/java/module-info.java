module dev.apitestingplatform.app {
    requires javafx.controls;
    requires dev.apitestingplatform.ui;
    requires dev.apitestingplatform.httpclient;
    requires dev.apitestingplatform.persistence.sqlite;
    requires dev.apitestingplatform.security.windows;
    requires dev.apitestingplatform.importexport;
    exports dev.apitestingplatform.app;
}

