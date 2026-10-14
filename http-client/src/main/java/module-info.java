module dev.apitestingplatform.httpclient {
    requires java.net.http;
    requires transitive dev.apitestingplatform.application;
    requires org.slf4j;
    exports dev.apitestingplatform.httpclient;
}

