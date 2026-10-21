module dev.apitestingplatform.application {
    requires transitive dev.apitestingplatform.domain;
    requires com.fasterxml.jackson.core;
    exports dev.apitestingplatform.application;
    exports dev.apitestingplatform.application.http;
    exports dev.apitestingplatform.application.storage;
}

